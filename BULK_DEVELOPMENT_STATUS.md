# TCK FactoryFlow — Bulk Development Status

## Implemented by bulk patch
- Sales order cancellation is restricted to OPEN/PARTIAL orders with zero dispatched packs; order history is retained.
- Production plan cancellation is restricted to PLANNED plans with no production entries for that product/date.
- Customer archive is a reversible active-flag change; customer transaction history is retained.
- Cancellation/archive actions enqueue sync events and audit records.
- Production plan start now also records audit and sync events.

## Remaining development before final testing
- Customer restore UI (archived-customer view/action).
- Sales order header/line edit workflow with dispatched-quantity safeguards.
- Production plan edit workflow and cloud sync contract.
- Confirm Worker handlers/schema for newly emitted event types.
- Review all remaining TODO/placeholder UI actions and role gates.
- Documentation: deployment, admin onboarding, backup/restore, operational workflows.

## Final phase (not run by this script)
- Gradle assembleDebug and lint.
- Room schema/migration verification.
- Cloudflare Worker tests and Android↔Worker integration tests.
- Role/permission, offline queue, idempotency, reversal, and stock-integrity tests.
- Manual end-to-end factory workflow; fix issues; then user-approved deployment.

**This patch script does not build, test, deploy, commit, or push.**


## Phase 2 — Bulk Development
- [ ] Customer restore UI
- [ ] Sales order edit with dispatched quantity safeguards
- [ ] Production plan edit
- [ ] Cloudflare Worker sync event handlers
- [ ] Remaining UI placeholders and role gates
- [ ] Deployment, onboarding, backup/restore documentation

## Phase 2 — Implementation backlog (2026-11-01)

### Customer
- [ ] Archived customer list and Restore action.
- [ ] Restore must update `active=true` transactionally, enqueue an idempotent sync event,
      and write an audit record. Preserve historical orders/dispatches.

### Sales orders
- [ ] Edit order header and lines only when no packs have been dispatched.
- [ ] If dispatched quantity exists, block edit (do not rewrite snapshots/history).
- [ ] Update order + lines in one Room transaction.
- [ ] Enqueue `SALES_ORDER_UPDATED` with stable event key and write audit before/after.

### Production plans
- [ ] Edit only `PLANNED` plans with no production entries linked to the plan.
- [ ] Update plan fields atomically; enqueue `PRODUCTION_PLAN_UPDATED`; write audit.
- [ ] Keep cancel restrictions from Phase 1.

### Sync / audit / UI
- [ ] Confirm Worker `/api/sync/event` accepts each emitted event type and persists idempotently.
- [ ] Review pending UI actions, role gates, error states, and loading states.
- [ ] Check event payloads contain organization/factory identifiers and stable entity IDs.

### Documentation
- [ ] Update README with role matrix, operational workflow, offline/read-only behavior,
      sync troubleshooting, backup/restore, onboarding, and deployment steps.

### Final verification (deferred by user preference)
- [ ] Gradle assembleDebug + lint.
- [ ] Room schema/migration checks.
- [ ] Worker tests and Android↔Worker integration.
- [ ] Role, offline queue, idempotency, dispatch/production integrity tests.
- [ ] Manual end-to-end workflow and bug fixing.
- [ ] Deploy only after explicit approval.
