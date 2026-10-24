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
- Stock validation, complete-pack / loose-bottle calculations, and duplicate-dispatch protection.
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
