#!/usr/bin/env python3
"""TCK FactoryFlow bulk development patch (no build/test/deploy)."""
from pathlib import Path
import shutil, time, sys

ROOT = Path.cwd()
DB = ROOT / 'app/src/main/java/com/tck/factoryflow/data/FactoryDatabase.kt'
MAIN = ROOT / 'app/src/main/java/com/tck/factoryflow/MainActivity.kt'
README = ROOT / 'README.md'
for p in (DB, MAIN):
    if not p.exists():
        sys.exit(f'Missing required file: {p}')

def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise RuntimeError(f'{label}: expected exactly 1 anchor, found {n}. No files changed.')
    return text.replace(old, new, 1)

db = DB.read_text()
main = MAIN.read_text()

# DAO additions: order cancellation, customer active/archive toggling, plan cancellation.
anchor = ' @Query("UPDATE sales_orders SET status=:status WHERE id=:id") abstract suspend fun updateSalesOrderStatus(id:Long,status:String)'
addition = '''
 @Query("SELECT * FROM sales_order_lines WHERE orderId=:orderId") abstract suspend fun salesOrderLinesByOrder(orderId:Long):List<SalesOrderLineEntity>
 @Query("SELECT COALESCE(SUM(packsDispatched),0) FROM sales_order_lines WHERE orderId=:orderId") abstract suspend fun dispatchedPacksForOrder(orderId:Long):Int
 @Transaction open suspend fun cancelSalesOrderSafely(orderId:Long):Boolean {
  val order=salesOrderById(orderId)?:error("Sales order not found")
  require(order.status in setOf("OPEN","PARTIAL")){"Only open/partial orders can be cancelled"}
  val lines=salesOrderLinesByOrder(orderId)
  require(lines.isNotEmpty()){"Order has no lines"}
  require(lines.all{it.packsDispatched==0}){"Order has dispatched quantity; cancellation blocked"}
  updateSalesOrderStatus(orderId,"CANCELLED")
  return true
 }
 @Query("UPDATE production_plans SET status='CANCELLED' WHERE id=:id AND status='PLANNED'") abstract suspend fun cancelPlannedProduction(id:Long):Int
 @Query("SELECT COUNT(*) FROM production_entries WHERE productId=:productId AND productionDate=:date") abstract suspend fun productionCountForPlan(productId:Long,date:String):Int
 @Query("SELECT * FROM customers WHERE id=:id LIMIT 1") abstract suspend fun customerById(id:Long):CustomerEntity?
 @Query("SELECT * FROM customers WHERE active=0 ORDER BY name COLLATE NOCASE") abstract fun observeArchivedCustomers():Flow<List<CustomerEntity>>
'''
db = replace_once(db, anchor, anchor + addition, 'DAO sales-order anchor')

# Customer archive query must not break order/dispatch history: only active flag changes.

# Add Sales Order cancellation action in each order card after dispatch action row.
old_order_ui = '''TextButton(onClick={selectedOrderId=o.id;dialog="dispatch"}){Icon(Icons.Default.LocalShipping,null);Text(" Create dispatch")}};if(lines.isNotEmpty())LinearProgressIndicator'''
new_order_ui = '''TextButton(onClick={selectedOrderId=o.id;dialog="dispatch"}){Icon(Icons.Default.LocalShipping,null);Text(" Create dispatch")};if(o.status in setOf("OPEN","PARTIAL")&&fulfilled==0)TextButton(onClick={scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_ORDERS)){"Your role cannot cancel sales orders"};dao.cancelSalesOrderSafely(o.id);dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="sales-order-cancel:${o.id}",organisationId=session?.organisationId?:"local-org",eventType="SALES_ORDER_CANCELLED",payloadJson=JSONObject().put("orderId",o.id).put("orderNumber",o.orderNumber).toString()));dao.insertAudit(AuditEventEntity(eventType="SALES_ORDER_CANCELLED",entityType="sales_order",entityId=o.id.toString(),beforeJson=o.status,afterJson="CANCELLED",reason="Sales order cancelled before dispatch",actorId=session?.uid?:"local"));SyncScheduler.runNow(context);message="Sales order cancelled"}.onFailure{message=it.message}}){Text("Cancel order")}};if(lines.isNotEmpty())LinearProgressIndicator'''
main = replace_once(main, old_order_ui, new_order_ui, 'Sales Order card UI anchor')

# Production plan cancellation: only untouched PLANNED plans; audit/outbox added.
old_plan_ui = '''if(p.status=="PLANNED")TextButton(onClick={scope.launch{runCatching{dao.updateProductionPlanStatus(p.id,"IN_PROGRESS");message="Plan marked in progress"}.onFailure{message=it.message}}}){Text("Start plan")};if(p.status=="IN_PROGRESS")TextButton'''
new_plan_ui = '''if(p.status=="PLANNED")Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){TextButton(onClick={scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot update plans"};dao.updateProductionPlanStatus(p.id,"IN_PROGRESS");dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="production-plan-start:${p.id}",organisationId=session?.organisationId?:"local-org",eventType="PRODUCTION_PLAN_STARTED",payloadJson=JSONObject().put("planId",p.id).toString()));dao.insertAudit(AuditEventEntity(eventType="PRODUCTION_PLAN_STARTED",entityType="production_plan",entityId=p.id.toString(),beforeJson="PLANNED",afterJson="IN_PROGRESS",reason="Production plan started",actorId=session?.uid?:"local"));message="Plan marked in progress"}.onFailure{message=it.message}}}){Text("Start plan")};TextButton(onClick={scope.launch{runCatching{require(roleAllows(session,FactoryAction.CREATE_PRODUCTION)){"Your role cannot cancel plans"};require(dao.productionCountForPlan(p.productId,p.planDate)==0){"Production already recorded for this product/date; cancellation blocked"};require(dao.cancelPlannedProduction(p.id)==1){"Only planned items can be cancelled"};dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="production-plan-cancel:${p.id}",organisationId=session?.organisationId?:"local-org",eventType="PRODUCTION_PLAN_CANCELLED",payloadJson=JSONObject().put("planId",p.id).toString()));dao.insertAudit(AuditEventEntity(eventType="PRODUCTION_PLAN_CANCELLED",entityType="production_plan",entityId=p.id.toString(),beforeJson="PLANNED",afterJson="CANCELLED",reason="Production plan cancelled before production",actorId=session?.uid?:"local"));SyncScheduler.runNow(context);message="Production plan cancelled"}.onFailure{message=it.message}}}){Text("Cancel")}};if(p.status=="IN_PROGRESS")TextButton'''
main = replace_once(main, old_plan_ui, new_plan_ui, 'Production plan card UI anchor')

# Customer archive action from customer card; locate exact edit button fragment.
old_customer_card = '''TextButton(onClick={editingCustomer=customer;dialog="customerEdit"}){Icon(Icons.Default.Edit,null);Text("Edit")};TextButton(onClick={dialog="dispatch"}){Text("Dispatch")}}'''
new_customer_card = '''TextButton(onClick={editingCustomer=customer;dialog="customerEdit"}){Icon(Icons.Default.Edit,null);Text("Edit")};TextButton(onClick={scope.launch{runCatching{require(roleAllows(session,FactoryAction.MANAGE_PRODUCTS)){"Your role cannot archive customers"};dao.updateCustomer(customer.id,customer.name,customer.phone,customer.address,customer.gstNumber,false);dao.enqueueSyncEvent(SyncOutboxEntity(eventKey="customer-archive:${customer.id}",organisationId=session?.organisationId?:"local-org",eventType="CUSTOMER_ARCHIVED",payloadJson=JSONObject().put("customerId",customer.id).put("name",customer.name).toString()));dao.insertAudit(AuditEventEntity(eventType="CUSTOMER_ARCHIVED",entityType="customer",entityId=customer.id.toString(),beforeJson="active",afterJson="archived",reason="Customer archived; transaction history retained",actorId=session?.uid?:"local"));SyncScheduler.runNow(context);message="Customer archived"}.onFailure{message=it.message}}}){Text("Archive")};TextButton(onClick={dialog="dispatch"}){Text("Dispatch")}}'''
main = replace_once(main, old_customer_card, new_customer_card, 'Customer card UI anchor')

# Add restoration affordance in customer form edit branch is not guessed; archive remains reversible through DB flag.

# Write files only after all anchors validate.
stamp = time.strftime('%Y%m%d-%H%M%S')
for p in (DB, MAIN):
    backup = p.with_name(p.name + f'.backup-bulk-dev-{stamp}')
    shutil.copy2(p, backup)
DB.write_text(db)
MAIN.write_text(main)

# Append/update a development status document.
check = ROOT / 'BULK_DEVELOPMENT_STATUS.md'
content = '''# TCK FactoryFlow — Bulk Development Status\n\n## Implemented by bulk patch\n- Sales order cancellation is restricted to OPEN/PARTIAL orders with zero dispatched packs; order history is retained.\n- Production plan cancellation is restricted to PLANNED plans with no production entries for that product/date.\n- Customer archive is a reversible active-flag change; customer transaction history is retained.\n- Cancellation/archive actions enqueue sync events and audit records.\n- Production plan start now also records audit and sync events.\n\n## Remaining development before final testing\n- Customer restore UI (archived-customer view/action).\n- Sales order header/line edit workflow with dispatched-quantity safeguards.\n- Production plan edit workflow and cloud sync contract.\n- Confirm Worker handlers/schema for newly emitted event types.\n- Review all remaining TODO/placeholder UI actions and role gates.\n- Documentation: deployment, admin onboarding, backup/restore, operational workflows.\n\n## Final phase (not run by this script)\n- Gradle assembleDebug and lint.\n- Room schema/migration verification.\n- Cloudflare Worker tests and Android↔Worker integration tests.\n- Role/permission, offline queue, idempotency, reversal, and stock-integrity tests.\n- Manual end-to-end factory workflow; fix issues; then user-approved deployment.\n\n**This patch script does not build, test, deploy, commit, or push.**\n'''
check.write_text(content)
print('Bulk patch applied successfully.')
print('Backups created with suffix:', f'.backup-bulk-dev-{stamp}')
print('Created:', check)
print('Build/test/deploy NOT run.')
