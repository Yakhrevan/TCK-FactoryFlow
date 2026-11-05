# TCK FactoryFlow — Full Project Audit
**Generated:** November 2026  
**Auditor:** Principal Software Architect & QA Lead  
**Scope:** Android App (Kotlin + Compose + Room), Cloudflare Worker API, Firebase Auth & Firestore Rules

---

## 1. Executive Summary

TCK FactoryFlow is an industrial-grade Manufacturing Execution System (MES) and Enterprise Resource Planning (ERP) platform designed for plastic bottle manufacturing operations. The target deployment enforces **Cloud-Only Authoritative Writes** with **Offline Read-Only Local Cache**.

This audit covers:
- Complete inspection of all Android source files (`app/src/main/java/com/tck/factoryflow/`)
- Complete inspection of Cloudflare Worker backend (`cloudflare-worker/src/index.js`, `cloudflare-worker/src/firestore.js`)
- Firestore security rules (`firestore.rules` and `rules-tests/`)
- Room entities, DAOs, and database migrations
- Compilation, JVM test execution, and Worker test suite validation

---

## 2. Source Code & Configuration Audit

### 2.1 Build & Dependencies
- **Gradle:** 8.11.1
- **Android Gradle Plugin (AGP):** 8.9.2
- **Kotlin:** 2.0.20 with Compose Compiler plugin
- **KSP:** 2.0.20-1.0.24
- **Compile SDK:** 35 | **Target SDK:** 35 | **Min SDK:** 26
- **Architecture Dependencies:**
  - Jetpack Compose BOM `2025.03.00`, Material 3, Navigation Compose `2.8.7`
  - AndroidX Room `2.6.1` with KSP compiler
  - Firebase Auth `33.12.0` (BOM), Play Integrity AppCheck, Debug AppCheck
  - WorkManager `2.10.0`
- **Firestore SDK on Android:** **NONE**. Verified zero usage of `com.google.firebase:firebase-firestore`. Direct client database writes are structurally impossible.
- **Build Status:** Verified passing. `./gradlew assembleDebug` and `./gradlew test` pass cleanly.

### 2.2 Cloudflare Worker Backend
- **Location:** `cloudflare-worker/src/index.js` (3,580 lines), `cloudflare-worker/src/firestore.js` (274 lines)
- **Runtime:** Cloudflare Workers (V8 isolates), ES Modules (`package.json`: `"type": "module"`)
- **Key Dependencies:** `jose` (v6.2.14) for RS256 Firebase ID token JWT verification against Google JWKS; Google OAuth assertion generator for Google Datastore / Firestore REST API.
- **REST Endpoints:** 36 endpoints covering Auth bootstrap, Member provision, Snapshot, Products, Production, Packing, Dispatch, Reversals, Stock Adjustments, Customers, Orders, Plans, Lines, Machines, Machine Events, Quality, Electricity, and Reports.
- **Tests:** `cloudflare-worker/test/worker.test.mjs` — 5/5 tests passing.

### 2.3 Firestore Rules & Database Architecture
- **Rules File:** `firestore.rules` (55 lines)
- **Rules Suite:** `rules-tests/firestore.rules.test.mjs`
- **Security Posture:**
  - Direct client writes to `organisations/{orgId}/factories/{factoryId}/{document=**}` are denied (`allow write: if false;`).
  - Read access strictly requires verified active organisation membership (`activeMember(orgId)`).
  - Client-side event inbox is append-only with strict field whitelisting.

---

## 3. Discovered Defects, Inconsistencies & Gaps

### Defect 1: Admin Email Mismatch in Bootstrap (CRITICAL)
- **Files:** `cloudflare-worker/src/index.js` (line 301), `StartupLoginScreen.kt` (line 41), `PasswordSecurity.kt` (line 14), `FactoryAuthRepository.kt` (line 67)
- **Issue:** Worker and `FactoryAuthRepository` check for `admin@tck-factory.com` (with hyphen), whereas `StartupLoginScreen.kt` and `PasswordSecurity.kt` use `admin@tckfactory.com` (no hyphen).
- **Impact:** An administrator logging in with the default email in the login screen would fail bootstrap authorization with `403 Bootstrap is restricted`.
- **Fix Required:** Standardize email validation to accept both canonical forms (`admin@tckfactory.com` and `admin@tck-factory.com`).

### Defect 2: Missing Cloud API Invocations in Customer Management
- **Files:** `MainActivity.kt` (lines 2051–2052, line 895)
- **Issue:** Creating, editing, and archiving customers in `MainActivity.kt` only writes to local Room and enqueues to `sync_outbox`. It does not call `CloudInventoryRepository.upsertCustomer()` or `archiveCustomer()`, even though Worker endpoints `/api/customers/upsert` and `/api/customers/archive` already exist.
- **Impact:** Customer records remain device-local and are never provisioned in Firestore `organisations/{orgId}/factories/{factoryId}/customers/`.
- **Fix Required:** Wire `CustomerForm` save, edit, and archive handlers directly to `CloudInventoryRepository` with online requirement.

### Defect 3: Missing Cloud API Invocations in Electricity & Meter Readings
- **Files:** `MainActivity.kt` (lines 2060–2115)
- **Issue:** Saving morning and evening meter readings writes to local Room `insertMeterReading` without calling `CloudInventoryRepository.postElectricityReading()`.
- **Impact:** Power consumption is only stored in local Room and never synced to the server.
- **Fix Required:** Call `CloudInventoryRepository.postElectricityReading()` on save.

### Defect 4: Missing Cloud Sync Pull for Customers, Orders, Plans, Machines
- **Files:** `MainActivity.kt` (lines 215–240), `CloudInventoryRepository.kt`
- **Issue:** `MainActivity.kt` only synchronizes products and stock from `/api/inventory/snapshot`. It does not pull down cloud customers (`/api/customers/list`), sales orders (`/api/orders/list`), production plans (`/api/plans/list`), machines (`/api/machines/list`), or production lines (`/api/lines/list`).
- **Impact:** Multi-device synchronization is incomplete; records created on one device are invisible on other devices.
- **Fix Required:** Implement fetch methods in `CloudInventoryRepository` and integrate them into the cloud sync lifecycle.

### Defect 5: Raw Material Inventory Not Yet Modeled
- **Files:** `FactoryDatabase.kt`, `CloudInventoryRepository.kt`, `cloudflare-worker/src/index.js`
- **Issue:** Only finished goods (unpacked bottles, complete packs, loose bottles) are tracked in stock. Raw materials (preforms, caps, resins, masterbatch) have no dedicated entities, stock balance, or consumption workflows.
- **Impact:** Raw material inventory and consumption must be completed as specified in prompt section 9.
- **Fix Required:** Add Raw Material master and movement tracking, integrating material receipt, stock balance, and production consumption.

### Defect 6: Role Definition Completeness
- **Files:** `FactoryPermissions.kt`, `cloudflare-worker/src/index.js`
- **Issue:** Prompt section 5 specifies roles: Admin, Manager, Supervisor, Production Operator, Quality Inspector, Packing Operator, Dispatch Operator, Inventory Manager, Viewer. Currently, `FactoryRole` enum has `OWNER, ADMIN, MANAGER, PRODUCTION_STAFF, PACKING_STAFF, STOREKEEPER, DISPATCH_STAFF, VIEWER`. Quality Inspector and Supervisor should be first-class roles.
- **Fix Required:** Expand `FactoryRole` and Worker `allowedRoles` to include `SUPERVISOR`, `QUALITY_INSPECTOR`, and `INVENTORY_MANAGER`.

---

## 4. Verification Checkpoint Status

| Checkpoint | Status | Notes |
|---|---|---|
| `./gradlew assembleDebug` | **PASSED** | Compiled APK generated without errors. |
| `./gradlew test` | **PASSED** | All unit tests passed (53 tasks, 32 executed). |
| Cloudflare Worker tests | **PASSED** | 5/5 unit tests passed. |
| Firestore Rules tests | **PASSED** | Verified in `rules-tests/`. |
| Direct Firestore SDK in Android | **VERIFIED ABSENT** | Zero occurrences in app. |
| Offline Write Blocking | **PARTIALLY ENFORCED** | Enforced on production/packing/dispatch/stock-adjust; needs enforcement on customer/meter dialogs. |

---

## 5. Next Steps
Proceed immediately to:
1. `docs/IMPLEMENTATION_STATUS.md`
2. `docs/PENDING_DEVELOPMENT.md`
3. `docs/ARCHITECTURE.md`
4. `docs/SECURITY_AUDIT.md`
5. `docs/TESTING_PLAN.md`
6. `docs/DEVELOPMENT_PROGRESS.md`
Followed by implementation in dependency order.
