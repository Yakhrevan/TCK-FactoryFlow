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

### Firebase backend

The `functions` directory contains callable Cloud Functions for transactional multi-line dispatch posting and reversal. They require Firebase Authentication, App Check, an active organisation member record, a role allowed to perform the operation, configured product pack sizes, and pre-existing authoritative stock balance documents. The expected paths and fields are documented in `functions/src/index.ts`.

Before deploying:
1. Create the Firebase project and configure Android's `google-services.json` locally; do not commit secrets.
2. Create `organisations/{orgId}/members/{uid}` membership documents through a trusted provisioning process. Include `organisationId`, `role`, `active` and permitted `factoryIds`.
3. Seed factory product and stock documents through a trusted backend process. Stock documents must contain non-negative integer `completePacks` and `looseBottles` balances (legacy missing loose-bottle balances are treated as zero); the callable refuses missing/invalid pack balances. Membership documents must include `organisationId`, `role`, `active`, and a `factoryIds` array containing the permitted factory IDs.
4. Configure App Check for the Android app and enable the Firestore and Functions APIs.
5. Deploy rules and functions with the Firebase CLI, then test with non-production accounts and seeded test data.

The Android UI currently uses Room for local operational records. It has not yet been wired to call these Cloud Functions for dispatch posting, and the full production/packing authoritative cloud transaction path, member invitation/provisioning, complete role-permission matrix, offline conflict resolution, and cloud backup/restore remain follow-up work. Direct local dispatch now supports complete packs and loose bottles separately; loose dispatch consumes only existing loose stock and never silently breaks a complete pack. Sales-order lines remain pack-based. Do not treat local records as cloud-confirmed inventory.

### CI checks

The Android workflow now includes JVM unit tests, debug and unsigned-release APK builds, Room instrumentation tests on an emulator, TypeScript compilation, and Firestore rules tests using the emulator. A green result must be confirmed on the latest workflow run before considering this stage verified. The unsigned release APK is not Play Store ready.
