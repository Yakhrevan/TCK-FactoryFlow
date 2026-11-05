# TCK FactoryFlow — Security Audit & Threat Assessment
**Classification:** Internal Confidential  
**Assessment Date:** November 2026  
**Auditors:** Senior Security Engineer & Cloud Systems Architect

---

## 1. Threat Model & Security Posture

TCK FactoryFlow is an industrial IoT and MES/ERP application with strict data integrity requirements. The primary threats evaluated in this audit include:
1. **Unauthorized Stock Mutation:** Attempting to alter physical inventory counts or bypass inventory accounting without physical verification.
2. **Tenant Data Leakage:** Attempting to read or modify another factory or organisation's records by manipulating request parameters.
3. **Privilege Elevation:** Attempting to execute administrator functions (e.g. member provisioning, stock adjustments) using an operator or viewer token.
4. **Offline Write Hijacking / Replay:** Attempting to replay historical transactions or forge offline writes.
5. **Credential Leakage:** Exposure of API keys, private keys, service account JSON, or tokens in git repositories, logs, or client binaries.

---

## 2. Security Controls Evaluation

### 2.1 Secret Management
- **Result:** **PASSED**
- **Findings:**
  - Zero private keys or Google Service Account secrets exist in the Android source tree (`app/src/main/`).
  - Google Service Account credentials (`FIREBASE_SERVICE_ACCOUNT`) are managed exclusively in Cloudflare Worker encrypted secrets.
  - `app/google-services.json` contains only client-side Firebase app identifiers (`mobilesdk_app_id`, `project_number`, `current_key`), which is standard and safe for client distribution.
  - `.gitignore` properly excludes `.env`, `*.jks`, `*.keystore`, and temporary credential files.

### 2.2 Client-Side Isolation & Firestore Direct Access
- **Result:** **PASSED**
- **Findings:**
  - Android application does not include `com.google.firebase:firebase-firestore` in its Gradle dependencies.
  - Zero calls to `FirebaseFirestore.getInstance()` exist in `app/`.
  - Android client cannot perform direct database writes even if modified or reverse-engineered.
  - `firestore.rules` enforces `allow write: if false;` on all factory and organisation collections, preventing unauthorized direct mutations from any authenticated client.

### 2.3 Authentication & Session Security
- **Result:** **PASSED WITH ACTION ITEMS**
- **Findings:**
  - Firebase ID tokens use RS256 cryptography and are validated on every request by Cloudflare Worker using `jose` against Google's public JWKS.
  - Token expiration (1 hour) is automatically handled in `CloudflareApiClient.kt` via proactive 401 retry with token refresh (`getIdToken(true)`).
  - Deactivated accounts are blocked: Worker checks `member.fields.active === true` on every protected operation.
  - **Action Item (Resolved in Audit):** Canonicalize admin bootstrap email check between `admin@tck-factory.com` and `admin@tckfactory.com`.

### 2.4 Organization & Factory Isolation (Multi-Tenancy)
- **Result:** **PASSED**
- **Findings:**
  - Worker's `authorizeMember()` function does not trust client-supplied roles or factory IDs.
  - It fetches the authoritative member document `organisations/{orgId}/members/{user.sub}` from Firestore using the server's elevated service account.
  - Asserts that:
    1. The member document's `organisationId` exactly matches the requested `organisationId`.
    2. The member's `factoryIds` array explicitly contains the requested `factoryId`.
    3. The member's `role` is permitted for the endpoint.
  - Tenant boundary cannot be bypassed by altering the request payload.

### 2.5 Idempotency & Replay Attack Prevention
- **Result:** **PASSED**
- **Findings:**
  - All critical business mutations (production runs, packing batches, dispatches, stock adjustments, reversals) require a client-generated `idempotencyKey` (SHA-256 hash of canonical payload or UUID).
  - Worker reads the target document in an atomic Firestore transaction (`firestoreBeginTransaction` / `firestoreCommit`).
  - If a document with the same key already exists, the Worker verifies the request fingerprint:
    - If fingerprint matches, returns existing success response without re-executing inventory side effects (`duplicate: true`).
    - If fingerprint differs, rejects with `HTTP 409 Conflict: Idempotency conflict`.
  - Concurrent duplicate submissions are safely rejected with `409 Conflict`.

### 2.6 Input Validation & Safe Boundaries
- **Result:** **PASSED**
- **Findings:**
  - Path traversal protection: Path arguments are validated to prevent slash characters (`/`) or excessive length (> 150 chars).
  - Numeric validation: Quantities are checked with `Number.isSafeInteger()` and must be non-negative.
  - Production math invariant: `producedBottles >= goodBottles + rejectedBottles`.
  - Stock non-negativity: Stock adjustments and dispatches reject any operation that would reduce inventory balances below zero.
  - Text fields are truncated or sanitized before Firestore serialization.

### 2.7 Logging & Error Masking
- **Result:** **PASSED**
- **Findings:**
  - Passwords and auth tokens are not logged in plain text.
  - User-facing error responses return sanitized messages without stack traces or sensitive internal paths.
  - Production logging in Worker uses structured JSON without dumping request authentication headers.

---

## 3. Vulnerability Remediation Summary

| ID | Vulnerability / Risk | Severity | Status | Remediation Plan |
|---|---|---|---|---|
| SEC-01 | Bootstrap email mismatch could lock out administrator | Medium | **Remediated** | Accept both canonical email variants in Worker & Android auth repo. |
| SEC-02 | Unsynced customer creation bypassing server authorization | Medium | **Remediated** | Connect customer dialog directly to `/api/customers/upsert` with session check. |
| SEC-03 | Missing role definitions for Supervisor and Quality Inspector | Low | **Remediated** | Expand `FactoryRole` enum and Worker authorizer whitelist. |
| SEC-04 | Offline writes queueing in sync outbox without server check | Low | **Remediated** | Enforce cloud-only check in all write dialogs with explicit UI blocking when offline. |
