# TCK FactoryFlow — AI Project Context

Last updated: 2026-10-31
Purpose: Continue Android + Cloudflare backend migration across ChatGPT conversations without repeating terminal audits.

## 1. Project
- Android app: TCK FactoryFlow (Bottle Manufacturing Management)
- Local repository: ~/TCK-FactoryFlow
- Android package: com.tck.factoryflow
- Cloudflare Worker directory: ~/TCK-FactoryFlow/cloudflare-worker
- Firebase project ID: tck-admin-ac864
- Cloudflare Worker name: tck-factoryflow-api
- Worker URL: https://tck-factoryflow-api.tckfactoryflow.workers.dev
- Current deployed Worker version: 5465f05d-c1ca-463c-b90a-886864a50c07
- Organisation ID used for test: tck-factory-01
- Factory ID used for test: factory-01

## 2. Architecture decision
Goal: Migrate backend data operations from Firebase Cloud Functions and direct Firestore SDK calls to Cloudflare Workers + Firestore REST API.

Keep Firebase Authentication for login and Firebase ID token issuance.
Cloudflare Worker verifies Firebase ID tokens and performs trusted Firestore operations.
Do NOT remove Firebase Authentication.
Eventually remove Firebase Cloud Functions dependency from Android where no longer required.
Do not expose service-account secrets or Cloudflare secrets in Android.

## 3. Confirmed working Cloudflare endpoints
- GET /api/auth/verify
- GET /api/firestore/test
- POST /api/inventory/snapshot
- POST /api/products/upsert
- POST /api/production/post

All tested through Android Cloudflare Diagnostics.
Authorization:
  Authorization: Bearer <Firebase ID token>
For POST:
  Content-Type: application/json

Test Production response confirmed:
  HTTP 200
  success: true
  status: POSTED
  duplicate: false
  unpackedGoodBottles updated

## 4. Cloudflare Worker fixes completed
- Fixed Firestore REST documents:batchGet request format.
- Firestore batchGet response is multiline individual JSON objects; implemented brace-depth parser.
- Product snapshot lookup now works.
- Production POST returns HTTP 200.
- Removed temporary BATCHGET DEBUG and EXPECTED PATHS console logs.
- Clean Worker deployed successfully.
- Latest deployed version ID: 5465f05d-c1ca-463c-b90a-886864a50c07.

Worker source:
  cloudflare-worker/src/index.js
  cloudflare-worker/src/firestore.js

## 5. Production submit issue — diagnosis and partial fix
Actual Android Production form showed "Not found" because it was calling Firebase Cloud Functions:
  functions().getHttpsCallable("postProduction").call(payload).await()

Diagnostics worked because they directly called Cloudflare Worker.

File:
  app/src/main/java/com/tck/factoryflow/sync/CloudInventoryRepository.kt

A backup was created:
  CloudInventoryRepository.kt.backup

Imports added:
  FirebaseAuth
  HttpURLConnection
  URL

postProduction() was replaced with direct Cloudflare POST:
  https://tck-factoryflow-api.tckfactoryflow.workers.dev/api/production/post

It:
- Builds production JSON with organisationId, factoryId, productId, productionDate, producedBottles, goodBottles, rejectedBottles, machineName, lineName, remarks.
- Creates idempotencyKey.
- Gets Firebase currentUser ID token.
- Sends POST with Authorization Bearer token.
- Checks HTTP status and response success.
- Returns idempotency key.

IMPORTANT: This Android change has NOT YET been built or tested through the actual Production UI. Do not assume fixed until app build and real submission confirm it.

## 6. Android backend audit results

Audit command used:
grep -Rni "getHttpsCallable\|FirebaseFunctions" app/src/main/java/com/tck/factoryflow/ --include="*.kt"
and direct Firestore references search.

### Firebase Cloud Functions still present
File:
  app/src/main/java/com/tck/factoryflow/sync/CloudInventoryRepository.kt

Remaining calls:
- upsertProduct
- postPacking
- getInventorySnapshot
- postDispatch
- reverseDispatch
- reversePacking

Note: postProduction has been migrated to Cloudflare, so it should no longer show getHttpsCallable.

File:
  app/src/main/java/com/tck/factoryflow/auth/FactoryAuthRepository.kt

Remaining calls:
- bootstrapAdmin
- provisionMember (2 locations)
Also direct Firestore member lookups.

### Direct Firestore SDK usage
File:
  app/src/main/java/com/tck/factoryflow/sync/SyncOutboxWorker.kt
- Direct member read
- Direct event read/write
- Firestore transaction

Files:
  app/src/main/java/com/tck/factoryflow/cloud/FirestoreCloudRepository.kt
  app/src/main/java/com/tck/factoryflow/cloud/CloudFirestoreRepository.kt
- Generic direct Firestore SDK operations.

## 7. Bulk migration goal
User explicitly wants bulk coordinated changes rather than one file at a time, to reduce repeated build/test cycles and conversation usage.

Before editing:
1. Audit all Android Firebase Functions and Firestore operations.
2. Audit Cloudflare Worker existing routes and determine missing endpoints.
3. Create a reusable Android Cloudflare API client, e.g.:
   app/src/main/java/com/tck/factoryflow/cloud/CloudflareApiClient.kt
4. Client should:
   - Get Firebase ID token
   - Make GET/POST requests
   - Add Bearer token
   - Handle HTTP errors and JSON
   - Use configurable Worker base URL (currently constant)
5. Migrate all backend operations to Cloudflare:
   - Product upsert
   - Production posting (already partially migrated)
   - Packing posting
   - Inventory snapshot
   - Dispatch posting
   - Reverse dispatch
   - Reverse packing
   - Authentication bootstrap admin
   - Provision member
   - Member profile/role lookup
   - Sync outbox/events
   - Generic Firestore repositories
   - Dashboard, reports, history read paths
6. Ensure Cloudflare Worker supports every needed endpoint and correct Firestore permissions/transactions.
7. Avoid changing Firebase Auth login flow unless necessary.
8. Remove unused Firebase Functions/Firestore imports/dependencies only after confirming no remaining usage.
9. Keep offline/local Room DB functionality and local cache.
10. Make a full backup or git branch before bulk edits.
11. Only after coordinated edits, run one final Gradle build:
   ./gradlew assembleDebug
12. Install and test all workflows against Worker:
   Login, role/member, products, production, packing, dispatch, reversals, inventory, history, reports, sync.

## 8. Working style requested by user
- Explain in Tanglish (Tamil + English).
- User wants bulk updates and fewer repeated commands.
- Do not build after every small code edit.
- Prefer one audit, one coordinated bulk patch, then one final build/test cycle.
- Terminal commands must be copy-paste-safe.
- Be cautious with Python heredoc edits because user terminal paste sometimes truncates; verify files after changes.
- Do not deploy Worker until syntax checks and dry-run pass.
- User will provide terminal output/screenshots for verification.

## 9. Immediate next task
Audit Cloudflare Worker routes to see all supported operations:
  cd ~/TCK-FactoryFlow/cloudflare-worker
  grep -nE 'pathname|/api/|case "|async function|function ' src/index.js

Then inspect complete Worker src/index.js and Android repositories/auth/sync files.
Create an endpoint capability matrix (existing vs missing).
Prepare one coordinated Android + Worker bulk migration patch.
Do not start with a small isolated edit or build now.

## 10. Important production behavior
Production Worker handler uses Firestore transaction:
- root: organisations/{orgId}/factories/{factoryId}
- productions/{idempotencyKey}
- products/{productId}
- stock/{productId}
- optional machines/{machineId}
It checks product active, writes production record, updates stock.
Diagnostics used product TEST-001 and test production key TEST-PRODUCTION-<timestamp>.
Do not repeatedly post diagnostics to production without considering stock side effects.
