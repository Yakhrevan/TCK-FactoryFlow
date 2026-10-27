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

## Authoritative production and packing backend (in progress)

- Added callable `postProduction` to validate production quantities, require active factory membership and product configuration, enforce an idempotency fingerprint, and atomically update the factory's unpacked-good-bottle balance with an append-only inventory ledger entry.
- Added callable `postPacking` to atomically consume unpacked good bottles, calculate complete packs and remainder bottles from the server-owned product pack size, and update pack/loose stock and ledger records.
- Added callable `reversePacking` for manager-authorised, reason-required packing reversal. It refuses reversal if the relevant finished stock has already been consumed and is idempotent.
- Rejected-bottle ledger movements are recorded as negative bottle quantities; production dates are checked against real calendar dates.
- Added Firestore rules regression coverage confirming clients cannot write production, packing, or inventory transaction documents directly.

**Integration limitation:** these trusted callable functions are backend foundations, not yet an end-to-end Android rollout. Android currently posts production and packing locally and writes generic outbox events. Before enabling cloud posting in the UI, implement stable cloud product IDs/catalog provisioning, factory ID and membership provisioning, Firebase Functions client dependency, callable invocation, pending/confirmed/error UI states, and a migration/backfill plan. Do not send local Room auto-increment IDs as cloud product IDs, and do not treat a generic outbox event as a committed inventory transaction. During rollout, avoid dual-posting the same operation locally and in cloud; use the server result as the authority and reconcile local views from confirmed server records.

## Important operational rules

1. Pack size must be configured for each product; never assume a pack size based on bottle volume.
2. A loose-bottle dispatch may use only available loose stock. The app does not silently open a complete pack. Add an explicit, audited pack-break operation if the factory wants this behaviour.
3. Sales orders currently measure quantities in packs. Loose bottles are therefore supported on direct dispatches only; do not allocate loose quantities to pack-based sales-order fulfilment without a schema/UI design for that business rule.
4. Local Room transactions protect one device. They do not provide cross-device stock locking. Treat Firestore stock as authoritative only after production/packing posting, provisioning, and offline conflict handling are connected and tested.
5. Existing records and product pack sizes must be preserved. Do not use destructive migrations or edit `main`.

## Remaining release gates

- Confirm Android compile, unit tests, TypeScript compile, Firestore rules tests, and emulator instrumentation on the latest branch commit.
- Add regression tests for mixed pack/loose dispatch, stock exhaustion, idempotent retry with same payload, idempotency-key reuse with changed payload, and reversal of a fully dispatched batch.
- Connect the Android app to the trusted callable functions for authoritative dispatch and then implement the same server-side transaction pattern for production and packing.
- Complete staff provisioning with explicit factory scope, password/credential policy for local-only operation, role-gated role switching, and organisation isolation.
- Test database backup/restore on a populated database and across supported schema versions.
- Add an explicit audited pack-break workflow only if operators need to dispatch bottles from a sealed pack.
- Validate all machine/line, electricity, quality, order, stock movement, dispatch, and reports links with real factory scenarios before release.
- Test offline create/edit/retry/conflict handling on two devices; a pending outbox entry is not a confirmed cloud transaction.
