# TCK FactoryFlow — System Architecture Specification
**Organization:** `tck-factory-01` | **Factory:** `factory-01`  
**Worker:** `tck-factoryflow-api` (`https://tck-factoryflow-api.tckfactoryflow.workers.dev`)  
**Firebase Project:** `tck-admin-ac864`

---

## 1. High-Level Architectural Model

TCK FactoryFlow is structured around a strict **Cloud-Authoritative Write** and **Offline Read-Only Cache** design. Android clients are never granted direct write permissions to Cloud Firestore. All mutations are validated, authorized, and committed atomically by the HTTPS Cloudflare Worker backend.

```
+-----------------------------------------------------------------------------------+
|                               ANDROID CLIENT                                      |
|                                                                                   |
|  +--------------------+        +---------------------+        +----------------+  |
|  | Jetpack Compose UI | <----> |  FactoryViewModel   | <----> |   Room Cache   |  |
|  +--------------------+        +---------------------+        +----------------+  |
|            |                              |                            ^          |
|            v                              v                            | (sync)   |
|  +--------------------+        +---------------------+                 |          |
|  |   Firebase Auth    |        |  CloudInventoryRepo | ----------------+          |
|  |  (ID Token Bearer) |        | (CloudflareApiClient)                            |
|  +--------------------+        +---------------------+                            |
+-------------------------------------------|---------------------------------------+
                                            | HTTPS + Bearer ID Token
                                            v
+-----------------------------------------------------------------------------------+
|                              CLOUDFLARE WORKER                                    |
|                       (tck-factoryflow-api.workers.dev)                           |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | 1. Token Verification: jose RS256 JWKS against securetoken.google.com       |  |
|  | 2. Member Authorization: organisations/{org}/members/{uid} (active, role)   |  |
|  | 3. Factory Boundary: factoryIds whitelisting                                |  |
|  | 4. Idempotency Guard: SHA-256 canonical payload / client-supplied keys       |  |
|  | 5. Atomic Transactions: firestoreBeginTransaction, BatchGet, Commit/Rollback|  |
|  | 6. Audit & Movement Logging: organisations/{org}/factories/{f}/inventoryTxn |  |
|  +-----------------------------------------------------------------------------+  |
+-------------------------------------------|---------------------------------------+
                                            | Google OAuth Service Account Token
                                            | (Datastore / Firestore REST API v1)
                                            v
+-----------------------------------------------------------------------------------+
|                           GOOGLE CLOUD FIRESTORE                                  |
|                            (tck-admin-ac864)                                      |
|                                                                                   |
|  organisations/{orgId}/                                                           |
|    ├── members/{uid}                          [Managed by Admin SDK / Worker]     |
|    ├── events/{eventKey}                      [Append-only Client Outbox]         |
|    └── factories/{factoryId}/                                                     |
|          ├── products/{productId}             [Catalog Master]                    |
|          ├── stock/{productId}                [Authoritative Balances]            |
|          ├── productions/{idempotencyKey}     [Shop Floor Runs]                   |
|          ├── packings/{idempotencyKey}        [Bundling Records]                  |
|          ├── dispatches/{idempotencyKey}      [Customer Gate Passes]              |
|          ├── inventoryTransactions/{txnId}    [Immutable Movement Ledger]         |
|          ├── customers/{customerId}           [Customer Directory]                |
|          ├── salesOrders/{orderNumber}        [Sales Orders & Fulfilment]         |
|          ├── productionPlans/{planId}         [Shift Schedules]                   |
|          ├── lines/{lineCode}                 [Production Lines]                  |
|          ├── machines/{machineCode}           [Plant Machinery]                   |
|          ├── machineEvents/{eventId}          [Maintenance & Downtime]            |
|          ├── qualityInspections/{inspId}      [QC Reports]                        |
|          └── electricity/{readingId}          [Energy Readings]                   |
+-----------------------------------------------------------------------------------+
```

---

## 2. Authentication & Authorization Flow

### 2.1 User Authentication
1. User provides credentials on Android login screen.
2. Firebase Authentication SDK validates credentials against `tck-admin-ac864` Firebase project.
3. On success, an RS256 signed Firebase ID Token is issued containing:
   - `sub`: User UID
   - `email`: User email address
   - `iss`: `https://securetoken.google.com/tck-admin-ac864`
   - `aud`: `tck-admin-ac864`
   - Custom claims: `organisationId`, `role`, `factoryIds` (injected by Worker).

### 2.2 Server-Side Authorization (`authorizeMember`)
For every protected Worker route:
1. Extract Bearer token from HTTP `Authorization` header.
2. Verify token signature and expiration via `jose` library against Google's public JWKS certificates.
3. Extract `user.sub` from verified claims.
4. Fetch member profile from Firestore: `organisations/{orgId}/members/{user.sub}`.
5. Verify:
   - `f.active.booleanValue === true`
   - `f.organisationId.stringValue === orgId`
   - Target `factoryId` is included in `f.factoryIds.arrayValue.values`
   - Member `role` is permitted for the specific endpoint.

---

## 3. Storage Hierarchy & Document Design

### 3.1 Organization-Level Collections
- `organisations/{orgId}/members/{uid}`: Staff accounts, role definitions, assigned factory IDs, active/inactive flag.
- `organisations/{orgId}/events/{eventId}`: Append-only event queue written by offline sync workers.
- `organisations/{orgId}/system/bootstrap-admin`: Distributed lock ensuring administrator bootstrapping can execute only once.

### 3.2 Factory-Level Collections
All operational data lives under `organisations/{orgId}/factories/{factoryId}/`:
- **`products/{productId}`**: Master catalog containing name, size label, colour, default bottles/pack, active status, icon/image metadata.
- **`stock/{productId}`**: The single source of truth for physical inventory:
  - `unpackedGoodBottles`: Bottles blown and approved, awaiting packing.
  - `completePacks`: Bundles/cases sealed and ready for dispatch.
  - `looseBottles`: Excess loose bottles remaining from packing batches.
  - `updatedAt`: ISO-8601 timestamp of last stock modification.
- **`inventoryTransactions/{idempotencyKey}`**: Immutable double-entry inventory ledger. Each document records:
  - `productId`, `quantity`, `unit` (`PACK` or `BOTTLE`)
  - `transactionType` (`PRODUCTION_IN`, `PACKING_CONSUMED`, `PACKING_PACKS_IN`, `DISPATCH_OUT`, `MANUAL_ADJUSTMENT`)
  - `actorUid`, `actorRole`, `reason`, `createdAt`.

---

## 4. Transactional Integrity & Idempotency Rules

### 4.1 Production Run Lifecycle (`POST /api/production/post`)
1. Client generates idempotency key: `SHA256(canonicalPayload)`.
2. Worker starts Firestore transaction.
3. Reads existing production doc, product doc, stock doc, and machine doc in parallel via `firestoreBatchGet`.
4. Validations:
   - If production doc exists with matching fingerprint, return existing result (idempotent duplicate).
   - If machine is specified, assert `machine.status === "RUNNING"`.
   - Assert `product.active === true`.
   - Assert `produced = good + rejected`.
5. Atomic Commit:
   - Write `productions/{key}` with status `POSTED`.
   - Update `stock/{productId}`: `unpackedGoodBottles = unpackedGoodBottles + good`.
   - Write `inventoryTransactions/{key}`.

### 4.2 Packing Batch Lifecycle (`POST /api/packing/post`)
1. Client submits `bottlesConsumed`.
2. Worker begins transaction.
3. Reads product and stock doc.
4. Validates:
   - `bottlesConsumed <= stock.unpackedGoodBottles` (prevents negative unpacked stock).
   - Pack calculation: `packs = floor(consumed / packSize)`, `loose = consumed % packSize`.
5. Atomic Commit:
   - Write `packings/{key}`.
   - Update `stock/{productId}`:
     - `unpackedGoodBottles = unpackedGoodBottles - bottlesConsumed`
     - `completePacks = completePacks + packs`
     - `looseBottles = looseBottles + loose`
   - Write inventory ledger entry.

### 4.3 Dispatch Order Lifecycle (`POST /api/dispatch/post`)
1. Client submits multi-line dispatch with customer and optional sales order reference.
2. Worker begins transaction.
3. Reads stock docs for all referenced products.
4. Validates:
   - Requested packs and loose bottles do not exceed available `completePacks` and `looseBottles`.
5. Atomic Commit:
   - Write `dispatches/{key}`.
   - Deduct requested stock for every line.
   - Write dispatch lines into `inventoryTransactions`.

---

## 5. Offline Operation & Caching Rules

1. **Write Operations:**
   - **MUST** be performed online.
   - If device is offline, UI write actions are disabled or report an explicit error: `"Cloud connection required"`.
   - No local unauthorized writes to authoritative stock balances.
2. **Read Operations:**
   - Room SQLite database acts as a read-only cache when offline.
   - UI displays a visible offline badge: `"Offline Cache — Read-Only Mode"`.
   - Previous stock balances, catalog, machine records, and history remain viewable.
3. **Reconnection & Pull Synchronization:**
   - When network connectivity is restored, the client triggers `fetchStock()`, `fetchCustomers()`, and `fetchOrders()` to refresh the local Room cache with authoritative server records.
