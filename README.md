# TCK FactoryFlow

Native Android application for bottle manufacturing. Current development branch: `feature/factoryflow-bottle-manufacturing`; `main` is intentionally untouched.

## Implemented in the first increment

- Kotlin, Jetpack Compose Material 3, and Room local persistence.
- TCK-inspired navy, blue and cyan UI with restrained gold accents and a scalable brand mark.
- Starter products: 300ml, 500ml, 1 Ltr, 200ml Green, 200ml Milk White, 200ml Clear.
- Product creation and product-specific bottles-per-pack settings; deactivation for products no longer in use.
- Production records with date, product, machine, good/rejected quantities and remarks.
- Automatic complete-pack and loose-bottle calculation using floor division and remainder.
- Packing validation against eligible good bottles not already packed.
- Finished-stock overview and dispatch validation to prevent overselling.
- Electricity readings with decimal support and decreasing-reading validation.
- Dashboard and reports calculated from persisted records only.
- JVM unit tests for packing, production validation, electricity and dispatch rules.
- GitHub Actions workflow for Android unit tests and debug APK assembly.

## Build

Open the repository in Android Studio with Android SDK 35 and JDK 17, sync Gradle, and run the `app` configuration. The CI workflow runs `gradle testDebugUnitTest` and `gradle assembleDebug` using Gradle 8.11.1.

## Production readiness gaps

This is an initial offline-first increment, not yet ready as a shared factory system. Firebase Authentication, organisation-scoped backend authorization, cloud sync, atomic server-side inventory transactions, audit and reversal workflows, product configuration version history, multi-line dispatch, full weekly/monthly/custom-range reports, export/backup, explicit Room migrations, and comprehensive integration/UI tests remain. Do not use it as the authoritative shared factory ledger until those controls are implemented and tested.
