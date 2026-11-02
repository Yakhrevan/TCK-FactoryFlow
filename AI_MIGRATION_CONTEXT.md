# TCK FactoryFlow — AI Project Context

## 1. Project
- Android app: TCK FactoryFlow
- Repository: `~/TCK-FactoryFlow`
- Branch: `feature/full-firestore-architecture`
- Firebase project: `tck-admin-ac864`
- Cloudflare Worker: `tck-factoryflow-api`
- Worker URL: `https://tck-factoryflow-api.tckfactoryflow.workers.dev`
- Worker source: `cloudflare-worker/src/index.js`
- Worker config: `cloudflare-worker/wrangler.jsonc`
- Organisation ID: `tck-factory-01`
- Factory ID: `factory-01`

## 2. Current architecture
- Firebase Authentication is retained for login and identity.
- Android obtains Firebase ID tokens and sends authenticated requests to Cloudflare Worker.
- Worker verifies Firebase ID tokens and accesses Firestore through the Firebase service account.
- Firestore backend operations are routed through Worker endpoints.
- Room is retained for local operational data and read-only offline cache.
- Cloud write operations require network connectivity.
- Do not treat local Room data as authoritative cross-device inventory.

## 3. Implemented Cloudflare Worker endpoints
- `GET /`
- `POST /api/auth/member`
- `POST /api/auth/bootstrap-admin`
- `POST /api/auth/provision-member`
- `POST /api/sync/event`
- `GET /api/auth/verify`
- `GET /api/firestore/test`
- `POST /api/inventory/snapshot`
- `POST /api/products/upsert`
- `POST /api/production/post`
- `POST /api/packing/post`
- `POST /api/dispatch/post`
- `POST /api/dispatch/reverse`
- `POST /api/packing/reverse`

## 4. Android cloud implementation
- `CloudflareApiClient.kt`: authenticated HTTP client for Worker calls.
- `CloudInventoryRepository.kt`: product, inventory, production, packing, dispatch, and reversal operations.
- `FactoryAuthRepository.kt`: Firebase Auth with Worker-based member lookup, admin bootstrap, and member provisioning.
- `SyncOutboxWorker.kt`: Worker-based member/event synchronization.
- `CloudConnection.kt`: online checks for cloud writes.
- Sales-order fulfilment and dispatch integration implemented; full workflow verification remains pending.

## 5. Security and configuration
- Bootstrap admin email is restricted to `admin@tckfactory.com`.
- Bootstrap organisation/factory/name values are server-controlled.
- Bootstrap lock uses a Firestore transaction and create-only semantics.
- Sync event processing includes idempotency protections.
- Worker secret `FIREBASE_SERVICE_ACCOUNT` is configured in Cloudflare.
- Do not print, commit, or expose secret values or service-account private keys.
- Android and diagnostic files currently reference the same Worker URL.
- Do not deploy Worker without explicit user approval.

## 6. Firestore rules and CI
- Rules tests are in `rules-tests/`.
- CI runs Firestore rules tests through the emulator.
- Last local rules test result recorded: 7 passed, 0 failed.
- CI includes Android JVM tests, debug/unsigned-release builds, Room instrumentation tests, TypeScript compilation, and rules tests.
- Confirm the latest CI run before considering the project verified.

## 7. Development status and remaining audit
Migration from Firebase Functions/direct Android Firestore SDK to Cloudflare Worker is substantially implemented. Continue reviewing feature completeness and stale documentation before final testing.

Audit areas:
1. App Check implementation and dependencies.
2. Remaining legacy Firebase configuration, files, and references.
3. Worker endpoint validation, transactions, authorization, and consistency.
4. Android DAO/entity/repository/UI workflow completeness.
5. Sales orders, fulfilment, dispatch, reports, dashboard, and exports.
6. Documentation and backup/archive decisions.
7. Any missing feature implementation discovered during review.

## 8. Important production behavior
Production Worker uses a Firestore transaction under:
`organisations/{orgId}/factories/{factoryId}`

Relevant documents include:
- `productions/{idempotencyKey}`
- `products/{productId}`
- `stock/{productId}`
- Optional `machines/{machineId}`

It validates product state, writes production records, and updates stock. Diagnostic submissions may create real stock side effects. Do not repeatedly post diagnostic production requests to production data.

Packing and dispatch operations must preserve inventory integrity, idempotency, and audit history. Reversal operations must use compensating transactions and validate current stock/dispatch state.

## 9. Backups and repository housekeeping
- Historical source backups are preserved under `project-backups/pre-cleanup/`.
- Do not delete these backups without explicit review.
- Empty legacy `functions/` directory was removed.
- Obsolete `functions/node_modules/` and `functions/lib/` ignore entries were removed.
- `app/google-services.json` is tracked and is Android client configuration; never confuse it with a service-account private key.
- Review `git status` before staging or committing. Do not commit automatically.

## 10. User's development workflow preference
- Explain in concise Tanglish.
- Give one terminal command per turn and wait for output.
- Complete development and integration work first; comprehensive testing comes at the end.
- Avoid repeated Gradle builds after small changes.
- Do not deploy Worker without approval.
- Commands must be copy-paste-safe; verify file edits after execution.

## 11. Final comprehensive testing phase
When development is complete, run:
1. `node --check cloudflare-worker/src/index.js`
2. `node --check cloudflare-worker/src/firestore.js`
3. `./gradlew assembleDebug`
4. Firestore rules emulator tests
5. Latest CI workflow verification
6. Manual Android + Worker integration tests:
   - Authentication and admin bootstrap
   - Member provisioning and role permissions
   - Product master/upsert
   - Production posting and inventory
   - Packing and packing reversal
   - Dispatch, multi-line sales-order fulfilment, dispatch reversal
   - Inventory snapshot, history, dashboard, reports, exports
   - Sync/idempotency, offline/read-only behavior
7. Bug fixing and final release readiness review

