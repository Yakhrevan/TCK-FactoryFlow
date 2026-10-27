# FactoryFlow R&D audit and implementation log

Branch: `feature/factoryflow-bottle-manufacturing`  
Scope: bottle manufacturing, production, packing, finished stock, customer dispatch, staff access, backups, and synchronization. The `main` branch is intentionally untouched.

## Implemented in this pass

- **Backup compatibility:** backup validation now expects Room schema version 9, matching `FactoryDatabase`; previously valid current-version snapshots were rejected as version 5.
- **Cloud membership checks:** callable functions require an explicit `factoryIds` array containing the requested factory. Missing factory scope no longer grants access.
- **Dispatch retry safety:** an idempotency key is now bound to the actor, organisation, factory, customer, reference, date, line quantities, and remarks. Reusing a key for a different request is rejected.
- **Cloud dispatch quantities:** callable dispatch supports complete packs, loose bottles, or a mix; it checks and updates both authoritative stock balances and records separate pack/bottle ledger movements. Reversal restores both quantities.
- **Local dispatch UI:** dispatch form accepts complete packs and loose bottle counts per product, shows the bottle equivalent, reviews the quantities before posting, and prevents loose-bottle dispatch against a sales order until order lines support bottle quantities.
- **Local inventory accounting:** dispatch lines retain total bottle quantity and pack-size snapshot; loose dispatch is derived from that immutable snapshot. Stock cards show net loose bottles after posted dispatches, and reversal records a compensating loose-bottle movement.
- **Access control hardening:** cloud sign-in requires a provisioned, active membership document with an explicit supported role. Offline sign-in no longer creates a new administrator/manager/production session for an unknown email; it requires an existing active local staff record and checks the selected portal against that record's role.

## Authoritative production and packing integration (implemented; release verification pending)

- Added callable `postProduction` to validate production quantities, require active factory membership and product configuration, enforce an idempotency fingerprint, and atomically update the factory's unpacked-good-bottle balance with an append-only inventory ledger entry.
- Added callable `postPacking` to atomically consume unpacked good bottles, calculate complete packs and remainder bottles from the server-owned product pack size, and update pack/loose stock and ledger records.
- Added callable `reversePacking` for manager-authorised, reason-required packing reversal. It refuses reversal if the relevant finished stock has already been consumed and is idempotent.
- Rejected-bottle ledger movements are recorded as negative bottle quantities; production dates are checked against real calendar dates.
- Added Firestore rules regression coverage confirming clients cannot write production, packing, or inventory transaction documents directly.

**Android integration added on this branch:** production and packing forms now call the trusted Firebase Functions before recording a local mirror; cloud posting failures are surfaced and do not create a local confirmed stock transaction. Packing mirrors use server-calculated pack/loose quantities and the server's pack-size snapshot. The Stock page fetches server stock for cloud sessions, displays a refresh action, and shows a dash rather than substituting local totals when the server snapshot is unavailable. A Room migration adds a deterministic product code and cloud operation keys without deleting existing records. The app reconciles local product IDs to matching cloud catalog entries and uploads local catalog entries that do not exist in the cloud catalog. A trusted product-provisioning callable manages the product master, a stock-snapshot callable reads server balances, and staff membership is provisioned through the Admin SDK rather than a Firestore client write.

**Release limitation:** the integration is not yet production-verified. Existing cloud membership documents must be provisioned with an explicit `factoryIds` array (for this installation, `factory-01`); missing factory scope is intentionally rejected. Confirm the deployed Cloud Functions region matches the Android default region, deploy the new functions, configure App Check, seed the correct organisation/factory/product catalog and stock documents, and run the two-device concurrency/retry tests before treating cloud stock as operational. Cloud dispatch remains blocked in the Android UI until its callable is connected.

## Important operational rules

1. Pack size must be configured for each product; never assume a pack size based on bottle volume.
2. A loose-bottle dispatch may use only available loose stock. The app does not silently open a complete pack. Add an explicit, audited pack-break operation if the factory wants this behaviour.
3. Sales orders currently measure quantities in packs. Loose bottles are therefore supported on direct dispatches only; do not allocate loose quantities to pack-based sales-order fulfilment without a schema/UI design for that business rule.
4. Local Room transactions protect one device. They do not provide cross-device stock locking. Treat Firestore stock as authoritative only after production/packing posting, provisioning, and offline conflict handling are connected and tested.
5. Existing records and product pack sizes must be preserved. Do not use destructive migrations or edit `main`.

## Remaining release gates

- Confirm Android compile, unit tests, TypeScript compile, Firestore rules tests, and emulator instrumentation on the latest branch commit.
- Add regression tests for mixed pack/loose dispatch, stock exhaustion, idempotent retry with same payload, idempotency-key reuse with changed payload, and reversal of a fully dispatched batch.
- Connect the Android app to the trusted callable function for authoritative dispatch and implement a cloud-confirmed dispatch mirror; production and packing callable integration is now present but requires full CI and two-device validation.
- Provision existing Firebase memberships with explicit factory scope, verify trusted staff provisioning end-to-end, and complete password/credential policy for local-only operation, role-gated role switching, and organisation isolation.
- Test database backup/restore on a populated database and across supported schema versions.
- Add an explicit audited pack-break workflow only if operators need to dispatch bottles from a sealed pack.
- Validate all machine/line, electricity, quality, order, stock movement, dispatch, and reports links with real factory scenarios before release.
- Test offline create/edit/retry/conflict handling on two devices; a pending outbox entry is not a confirmed cloud transaction.
