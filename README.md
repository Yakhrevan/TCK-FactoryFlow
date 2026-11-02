# TCK FactoryFlow

Native Android app for bottle-manufacturing operations. Active branch: `feature/factoryflow-bottle-manufacturing`; `main` remains untouched.

## Current development scope

- Kotlin, Jetpack Compose Material 3 and Room local persistence.
- Dashboard, product master, production records, packing, finished stock, dispatch, electricity and saved-record reports.
- Product configuration editing with prior-value history and audit records.
- Atomic local Room transactions for packing and multi-line dispatch.
- Multi-product dispatch batches with per-line product/pack-size snapshots and an idempotency key.
- Dispatch reversal with a mandatory reason, compensating inventory movements and audit record.
- Customer creation and selection in dispatch.
- Stock validation, complete-pack / loose-bottle calculations, mixed pack/loose dispatch, net loose-stock display, and payload-bound duplicate-dispatch protection.
- Custom date-range reports, equal-length previous-period comparison, CSV export/backup.
- Firebase email/password sign-in scaffold, organisation/role custom-claim reading, Firestore event rules, and WorkManager outbox retry scaffold.
- JVM tests for manufacturing rules and report date/CSV formatting.
- GitHub Actions workflow for Android unit tests and debug APK assembly.

## Local Android build

Open in Android Studio with JDK 17 and Android SDK 35. Gradle wrapper files should be generated/committed before relying on wrapper commands; CI currently installs Gradle 8.11.1 and runs:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

## Firebase setup

Firebase is optional for local development. To enable cloud sign-in/sync:

1. Register Android app ID `com.tck.factoryflow` in the organisation's Firebase project.
2. Add the environment-specific `app/google-services.json` locally. It is intentionally not committed.
3. Enable Email/Password sign-in.
4. Assign `organisationId` and `role` custom claims from a trusted Admin SDK process.
5. Deploy and emulator-test `firestore.rules`.
6. Implement and deploy the trusted backend endpoint for shared inventory posting before using multi-device stock as authoritative.

## Important integrity boundary

Room transactions are atomic on one device. They do not prevent overselling across different devices. The current outbox worker retries idempotent event writes, but this is not a substitute for a server-side inventory transaction. Firestore rules deliberately deny direct client writes to stock/master records. Do not deploy as a shared authoritative factory ledger until the trusted backend, organisation membership checks, role authorization, migration tests and concurrency tests are complete.

## Still needed before production

- Trusted backend/Cloud Functions for atomic organisation-scoped stock posting and server-enforced role authorization.
- Complete user administration and customer editing/deactivation UI.
- Idempotency keys for every stock-changing workflow, including production and packing.
- Reversal/correction workflows for production, packing and meter entries.
- Automated migration tests against populated v1 databases, Firestore emulator rules tests, UI/integration tests and physical-device QA.
- Verify successful CI build, install/debug APK, backup restore test, and release signing.


## Current implementation and verification notes

### Pack-size configuration

Fresh local databases seed the example products with `bottlesPerPack = 0`, meaning **not configured**. A pack size must be explicitly entered by an authorised factory user before packing or dispatch calculations are enabled. Zero is a sentinel for missing configuration, not a valid conversion. Existing installations retain their previously stored product values to avoid silently overwriting potentially valid factory configuration; review those values in Product Master after upgrading.

### Inventory corrections

Production and packing corrections are append-only audit/ledger events. Packing reversal is blocked when the packs have already been dispatched. Dispatch reversal restores complete-pack stock only through a reasoned compensating movement and is idempotent locally. These Room transactions are local to one device and do not provide cross-device authoritative stock.

### Cloud backend

Firebase Authentication is retained for user sign-in. All application backend operations are routed through the Cloudflare Worker (`tck-factoryflow-api`), which verifies Firebase ID tokens and accesses Cloud Firestore through the Firebase service account.

Implemented Worker endpoints include:
- Authentication: member lookup, admin bootstrap, member provisioning
- Inventory: snapshot retrieval
- Products: product upsert
- Production and packing: posting and reversal
- Dispatch: posting and reversal
- Sync: idempotent event synchronization

The Android application uses `CloudflareApiClient` and `CloudInventoryRepository` for backend communication. Direct Firebase Cloud Functions and Android Firestore SDK operations have been removed from the application architecture.

Offline mode is read-only for cloud operations. Write operations require network connectivity. Do not treat local Room records as authoritative cross-device inventory.

Before deployment, configure Worker secrets and bindings securely. Never commit service-account credentials or secret values. Deploy the Worker only after reviewing changes and obtaining explicit approval.

### CI checks

The Android workflow now includes JVM unit tests, debug and unsigned-release APK builds, Room instrumentation tests on an emulator, TypeScript compilation, and Firestore rules tests using the emulator. A green result must be confirmed on the latest workflow run before considering this stage verified. The unsigned release APK is not Play Store ready.
