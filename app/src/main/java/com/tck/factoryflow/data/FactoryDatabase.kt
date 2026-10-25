package com.tck.factoryflow.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import org.json.JSONArray
import com.tck.factoryflow.domain.ProductionQuantities

@Entity(tableName="products", indices=[Index(value=["name"], unique=true)])
data class ProductEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val name:String,val sizeLabel:String,val colour:String="Clear",val bottlesPerPack:Int,val active:Boolean=true,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="product_config_history",indices=[Index("productId"),Index("changedAt")])
data class ProductConfigHistoryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val productId:Long,val productName:String,val sizeLabel:String,val colour:String,val bottlesPerPack:Int,val active:Boolean,val changedAt:Long=System.currentTimeMillis(),val changedBy:String="local",val reason:String="Configuration updated")
@Entity(tableName="production_entries",indices=[Index("productId"),Index("productionDate")])
data class ProductionEntryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val productId:Long,val productNameSnapshot:String,val productionDate:String,val producedBottles:Int,val goodBottles:Int,val rejectedBottles:Int,val machineName:String="Machine 1",val remarks:String="",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="packing_entries",indices=[Index("productId"),Index("packingDate")])
data class PackingEntryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val productId:Long,val productNameSnapshot:String,val packingDate:String,val bottlesConsumed:Int,val completePacks:Int,val looseBottles:Int,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="dispatch_entries",indices=[Index("dispatchDate"),Index("customerName")])
data class DispatchEntryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val dispatchReference:String,val dispatchDate:String,val customerName:String,val productId:Long,val productNameSnapshot:String,val packsDispatched:Int,val remarks:String="",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="customers",indices=[Index(value=["name"],unique=true)])
data class CustomerEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val name:String,val phone:String="",val address:String="",val gstNumber:String="",val active:Boolean=true,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="dispatch_batches",indices=[Index(value=["idempotencyKey"],unique=true),Index("dispatchDate"),Index("customerId")])
data class DispatchBatchEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val idempotencyKey:String,val dispatchReference:String,val dispatchDate:String,val customerId:Long?,val customerNameSnapshot:String,val status:String="POSTED",val remarks:String="",val createdAt:Long=System.currentTimeMillis(),val createdBy:String="local")
@Entity(tableName="dispatch_lines",indices=[Index("batchId"),Index("productId")],foreignKeys=[ForeignKey(entity=DispatchBatchEntity::class,parentColumns=["id"],childColumns=["batchId"],onDelete=ForeignKey.CASCADE)])
data class DispatchLineEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val batchId:Long,val productId:Long,val productNameSnapshot:String,val packSizeSnapshot:Int,val packs:Int,val bottles:Int,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="inventory_movements",indices=[Index("productId"),Index("createdAt"),Index("sourceId")])
data class InventoryMovementEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val productId:Long,val productNameSnapshot:String,val movementType:String,val quantity:Int,val unit:String,val sourceType:String,val sourceId:String,val note:String="",val createdAt:Long=System.currentTimeMillis(),val actorId:String="local")
@Entity(tableName="audit_events",indices=[Index("entityType"),Index("entityId"),Index("createdAt")])
data class AuditEventEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val eventType:String,val entityType:String,val entityId:String,val beforeJson:String="",val afterJson:String="",val reason:String,val actorId:String="local",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="sync_outbox",indices=[Index(value=["eventKey"],unique=true),Index("status"),Index("createdAt")])
data class SyncOutboxEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val eventKey:String,val organisationId:String,val eventType:String,val payloadJson:String,val status:String="PENDING",val attempts:Int=0,val lastError:String="",val createdAt:Long=System.currentTimeMillis(),val syncedAt:Long?=null)
@Entity(tableName="meter_readings",indices=[Index(value=["meterId","readingDate"],unique=true)])
data class MeterReadingEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val meterId:String="MAIN",val readingDate:String,val startReading:Double,val endReading:Double,val consumption:Double,val remarks:String="",val createdAt:Long=System.currentTimeMillis())

data class DispatchRequestLine(val productId:Long,val packs:Int)
data class DispatchPostResult(val batchId:Long,val reference:String,val lineCount:Int)

@Entity(tableName="machines",indices=[Index(value=["machineCode"],unique=true)])
data class MachineEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val machineCode:String,val name:String,val machineType:String,val location:String="",val status:String="RUNNING",val notes:String="",val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="machine_events",indices=[Index("machineId"),Index("startedAt"),Index("status")])
data class MachineEventEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val machineId:Long,val machineNameSnapshot:String,val eventType:String,val startedAt:Long,val endedAt:Long?=null,val reason:String="",val maintenanceType:String="",val status:String="OPEN",val performedBy:String="local",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="quality_inspections",indices=[Index("productId"),Index("productionEntryId"),Index("inspectionDate"),Index("status")])
data class QualityInspectionEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val productionEntryId:Long?,val productId:Long,val productNameSnapshot:String,val inspectionDate:String,val inspectedBottles:Int,val passedBottles:Int,val failedBottles:Int,val defectCategory:String,val severity:String="MINOR",val notes:String="",val inspectorId:String="local",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="production_plans",indices=[Index("planDate"),Index("productId"),Index("status")])
data class ProductionPlanEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val planDate:String,val shift:String,val productId:Long,val productNameSnapshot:String,val targetBottles:Int,val assignedMachineId:Long?=null,val status:String="PLANNED",val notes:String="",val createdBy:String="local",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="sales_orders",indices=[Index(value=["orderNumber"],unique=true),Index("customerId"),Index("orderDate"),Index("status")])
data class SalesOrderEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val orderNumber:String,val customerId:Long?,val customerNameSnapshot:String,val orderDate:String,val dueDate:String,val status:String="OPEN",val notes:String="",val createdBy:String="local",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="sales_order_lines",indices=[Index("orderId"),Index("productId")],foreignKeys=[ForeignKey(entity=SalesOrderEntity::class,parentColumns=["id"],childColumns=["orderId"],onDelete=ForeignKey.CASCADE)])
data class SalesOrderLineEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val orderId:Long,val productId:Long,val productNameSnapshot:String,val packsOrdered:Int,val packsDispatched:Int=0,val packSizeSnapshot:Int,val createdAt:Long=System.currentTimeMillis())
@Dao abstract class FactoryDao {
 @Query("SELECT * FROM products ORDER BY active DESC, name COLLATE NOCASE") abstract fun observeProducts():Flow<List<ProductEntity>>
 @Query("SELECT * FROM products WHERE active=1 ORDER BY name COLLATE NOCASE") abstract suspend fun activeProducts():List<ProductEntity>
 @Query("SELECT * FROM products WHERE id=:id LIMIT 1") abstract suspend fun productById(id:Long):ProductEntity?
 @Insert(onConflict=OnConflictStrategy.ABORT) abstract suspend fun insertProduct(p:ProductEntity):Long
 @Query("UPDATE products SET name=:name,sizeLabel=:size,colour=:colour,bottlesPerPack=:pack,active=:active WHERE id=:id") abstract suspend fun updateProduct(id:Long,name:String,size:String,colour:String,pack:Int,active:Boolean)
 @Insert abstract suspend fun insertProductHistory(e:ProductConfigHistoryEntity):Long
 @Query("SELECT * FROM product_config_history WHERE productId=:id ORDER BY changedAt DESC") abstract fun observeProductHistory(id:Long):Flow<List<ProductConfigHistoryEntity>>
 @Insert abstract suspend fun insertAudit(a:AuditEventEntity):Long
 @Transaction open suspend fun updateProductWithHistory(id:Long,name:String,size:String,colour:String,pack:Int,active:Boolean,actor:String,reason:String,organisationId:String) {
  require(name.isNotBlank()&&size.isNotBlank());require(pack>=0)
  val old=productById(id)?:error("Product not found")
  insertProductHistory(ProductConfigHistoryEntity(productId=old.id,productName=old.name,sizeLabel=old.sizeLabel,colour=old.colour,bottlesPerPack=old.bottlesPerPack,active=old.active,changedBy=actor,reason=reason))
  updateProduct(id,name.trim(),size.trim(),colour.trim(),pack,active)
  val after=productJson(name,size,colour,pack,active)
  insertAudit(AuditEventEntity(eventType="PRODUCT_UPDATED",entityType="product",entityId=id.toString(),beforeJson=productJson(old.name,old.sizeLabel,old.colour,old.bottlesPerPack,old.active),afterJson=after,reason=reason,actorId=actor))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="product-update:"+organisationId+":"+id+":"+System.currentTimeMillis(),organisationId=organisationId,eventType="PRODUCT_UPDATED",payloadJson=after))
 }
 @Transaction open suspend fun correctProduction(id:Long,reason:String,actorId:String,organisationId:String):Boolean {
  require(reason.isNotBlank()){"A correction reason is required."}
  if(auditCount("PRODUCTION_REVERSED",id.toString())>0) return false
  val entry=productionById(id)?:error("Production record not found")
  val product=productById(entry.productId)?:error("Product not found")
  val available=totalGoodBottles(product.id)-totalPackedBottles(product.id)
  require(entry.goodBottles<=available){"Cannot reverse: some bottles from this product have already been packed; use a manager-approved stock correction."}
  if(entry.goodBottles>0) insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="PRODUCTION_REVERSAL_OUT",quantity=-entry.goodBottles,unit="BOTTLE",sourceType="production_reversal",sourceId=id.toString(),note=reason,actorId=actorId))
  if(entry.rejectedBottles>0) insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="REJECTED_PRODUCTION_REVERSAL",quantity=entry.rejectedBottles,unit="BOTTLE",sourceType="production_reversal",sourceId=id.toString(),note=reason,actorId=actorId))
  insertAudit(AuditEventEntity(eventType="PRODUCTION_REVERSED",entityType="production",entityId=id.toString(),reason=reason,actorId=actorId))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="production-reversal:"+organisationId+":"+id,organisationId=organisationId,eventType="PRODUCTION_REVERSED",payloadJson=JSONObject().put("productionId",id).put("reason",reason).toString()))
  return true
 }
 @Transaction open suspend fun postProduction(entry:ProductionEntryEntity,organisationId:String,actorId:String):Long {
  ProductionQuantities(entry.producedBottles,entry.goodBottles,entry.rejectedBottles).validate()
  val product=productById(entry.productId)?:error("Product not found")
  require(product.active){"Product is inactive"}
  val id=insertProduction(entry)
  if(entry.goodBottles>0) insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="PRODUCTION_GOOD_IN",quantity=entry.goodBottles,unit="BOTTLE",sourceType="production",sourceId=id.toString(),actorId=actorId))
  if(entry.rejectedBottles>0) insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="PRODUCTION_REJECTED",quantity=-entry.rejectedBottles,unit="BOTTLE",sourceType="production",sourceId=id.toString(),actorId=actorId))
  val payload=JSONObject().put("productionId",id).put("productId",entry.productId).put("date",entry.productionDate).put("produced",entry.producedBottles).put("good",entry.goodBottles).put("rejected",entry.rejectedBottles).put("machine",entry.machineName).toString()
  insertAudit(AuditEventEntity(eventType="PRODUCTION_POSTED",entityType="production",entityId=id.toString(),afterJson=payload,reason="Production posted",actorId=actorId))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="production:"+organisationId+":"+id,organisationId=organisationId,eventType="PRODUCTION_POSTED",payloadJson=payload))
  return id
 }
 @Query("SELECT * FROM production_entries ORDER BY productionDate DESC,createdAt DESC") abstract fun observeProduction():Flow<List<ProductionEntryEntity>>
 @Query("SELECT COALESCE(SUM(goodBottles),0) FROM production_entries WHERE productId=:id AND id NOT IN (SELECT CAST(entityId AS INTEGER) FROM audit_events WHERE eventType='PRODUCTION_REVERSED' AND entityType='production')") abstract suspend fun totalGoodBottles(id:Long):Int
 @Insert abstract suspend fun insertProduction(e:ProductionEntryEntity):Long
 @Query("SELECT * FROM packing_entries ORDER BY packingDate DESC,createdAt DESC") abstract fun observePacking():Flow<List<PackingEntryEntity>>
 @Query("SELECT COALESCE(SUM(bottlesConsumed),0) FROM packing_entries WHERE productId=:id AND id NOT IN (SELECT CAST(entityId AS INTEGER) FROM audit_events WHERE eventType='PACKING_REVERSED' AND entityType='packing')") abstract suspend fun totalPackedBottles(id:Long):Int
 @Query("SELECT COALESCE(SUM(completePacks),0) FROM packing_entries WHERE productId=:id AND id NOT IN (SELECT CAST(entityId AS INTEGER) FROM audit_events WHERE eventType='PACKING_REVERSED' AND entityType='packing')") abstract suspend fun totalCompletePacks(id:Long):Int
 @Insert abstract suspend fun insertPacking(e:PackingEntryEntity):Long
 @Query("SELECT * FROM dispatch_entries ORDER BY dispatchDate DESC,createdAt DESC") abstract fun observeDispatches():Flow<List<DispatchEntryEntity>>
 @Query("SELECT COALESCE(SUM(packsDispatched),0) FROM dispatch_entries WHERE productId=:id") abstract suspend fun totalDispatchedPacks(id:Long):Int
 @Insert abstract suspend fun insertDispatch(e:DispatchEntryEntity):Long
 @Query("SELECT * FROM dispatch_batches ORDER BY dispatchDate DESC,createdAt DESC") abstract fun observeDispatchBatches():Flow<List<DispatchBatchEntity>>
 @Query("SELECT * FROM dispatch_lines WHERE batchId=:id") abstract suspend fun linesForBatch(id:Long):List<DispatchLineEntity>
 @Query("SELECT * FROM dispatch_lines ORDER BY createdAt DESC") abstract fun observeDispatchLines():Flow<List<DispatchLineEntity>>
 @Query("SELECT COALESCE(SUM(packsDispatched),0) FROM dispatch_entries WHERE productId=:id") abstract suspend fun legacyDispatchedPacks(id:Long):Int
 @Query("SELECT COALESCE(SUM(dl.packs),0) FROM dispatch_lines dl INNER JOIN dispatch_batches db ON db.id=dl.batchId WHERE dl.productId=:id AND db.status='POSTED'") abstract suspend fun batchedDispatchedPacks(id:Long):Int
 @Query("SELECT * FROM dispatch_batches WHERE idempotencyKey=:key LIMIT 1") abstract suspend fun batchByIdempotencyKey(key:String):DispatchBatchEntity?
 @Query("SELECT * FROM production_entries WHERE id=:id LIMIT 1") abstract suspend fun productionById(id:Long):ProductionEntryEntity?
 @Query("SELECT COUNT(*) FROM audit_events WHERE eventType=:eventType AND entityId=:entityId") abstract suspend fun auditCount(eventType:String,entityId:String):Int
 @Query("SELECT * FROM packing_entries WHERE id=:id LIMIT 1") abstract suspend fun packingById(id:Long):PackingEntryEntity?

 @Transaction open suspend fun reversePacking(id:Long,reason:String,actorId:String,organisationId:String):Boolean {
  require(reason.isNotBlank()) { "A packing reversal reason is required." }
  if(auditCount("PACKING_REVERSED",id.toString())>0) return false
  val entry=packingById(id)?:error("Packing record not found")
  val product=productById(entry.productId)?:error("Product not found")
  val availablePacks=totalCompletePacks(product.id)-legacyDispatchedPacks(product.id)-batchedDispatchedPacks(product.id)
  require(entry.completePacks<=availablePacks) { "Cannot reverse packing: some packs have already been dispatched; use an approved stock correction." }
  insertAudit(AuditEventEntity(eventType="PACKING_REVERSED",entityType="packing",entityId=id.toString(),beforeJson=JSONObject().put("completePacks",entry.completePacks).put("bottlesConsumed",entry.bottlesConsumed).toString(),afterJson=JSONObject().put("reversed",true).toString(),reason=reason,actorId=actorId))
  if(entry.completePacks>0) insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="PACKING_REVERSAL_PACK_OUT",quantity=-entry.completePacks,unit="PACK",sourceType="packing_reversal",sourceId=id.toString(),note=reason,actorId=actorId))
  if(entry.bottlesConsumed>0) insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="PACKING_REVERSAL_BOTTLES_IN",quantity=entry.bottlesConsumed,unit="BOTTLE",sourceType="packing_reversal",sourceId=id.toString(),note=reason,actorId=actorId))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="packing-reversal:"+organisationId+":"+id,organisationId=organisationId,eventType="PACKING_REVERSED",payloadJson=JSONObject().put("packingId",id).put("reason",reason).toString()))
  return true
 }

 @Query("SELECT * FROM meter_readings WHERE id=:id LIMIT 1") abstract suspend fun meterById(id:Long):MeterReadingEntity?
 @Query("UPDATE production_entries SET producedBottles=producedBottles WHERE id=:id") abstract suspend fun touchProduction(id:Long)
 @Query("UPDATE packing_entries SET bottlesConsumed=bottlesConsumed WHERE id=:id") abstract suspend fun touchPacking(id:Long)
 @Query("UPDATE meter_readings SET remarks=remarks WHERE id=:id") abstract suspend fun touchMeter(id:Long)
 @Query("SELECT * FROM dispatch_batches WHERE id=:id LIMIT 1") abstract suspend fun dispatchBatchById(id:Long):DispatchBatchEntity?
 @Query("SELECT * FROM inventory_movements WHERE productId=:id ORDER BY createdAt DESC") abstract fun observeInventoryMovements(id:Long):Flow<List<InventoryMovementEntity>>
 @Query("SELECT * FROM audit_events ORDER BY createdAt DESC") abstract fun observeAuditEvents():Flow<List<AuditEventEntity>>
 @Query("SELECT * FROM sync_outbox ORDER BY createdAt DESC LIMIT :limit") abstract fun observeSyncOutbox(limit:Int=200):Flow<List<SyncOutboxEntity>>
 @Transaction open suspend fun reverseDispatch(batchId:Long,reason:String,actorId:String,organisationId:String):Boolean {
  require(reason.isNotBlank()){"A reversal reason is required."}
  val batch=dispatchBatchById(batchId)?:error("Dispatch not found.")
  if(batch.status=="REVERSED") return false
  require(batch.status=="POSTED"){"Only posted dispatches can be reversed."}
  val lines=linesForBatch(batchId)
  require(lines.isNotEmpty()){"Dispatch has no lines to reverse."}
  lines.forEach { line ->
   val available=totalCompletePacks(line.productId)-legacyDispatchedPacks(line.productId)-batchedDispatchedPacks(line.productId)
   require(line.packs<=available){"Cannot reverse: subsequent dispatches mean stock is no longer available for "+line.productNameSnapshot}
  }
  // Status change and compensating movements commit together.
  setDispatchStatus(batchId,"REVERSED")
  lines.forEach { line ->
   insertInventoryMovement(InventoryMovementEntity(productId=line.productId,productNameSnapshot=line.productNameSnapshot,movementType="DISPATCH_REVERSAL_IN",quantity=line.packs,unit="PACK",sourceType="dispatch_reversal",sourceId=batchId.toString(),note=reason,actorId=actorId))
  }
  insertAudit(AuditEventEntity(eventType="DISPATCH_REVERSED",entityType="dispatch",entityId=batchId.toString(),beforeJson=JSONObject().put("status","POSTED").toString(),afterJson=JSONObject().put("status","REVERSED").put("reason",reason).toString(),reason=reason,actorId=actorId))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="dispatch-reversal:"+organisationId+":"+batchId,organisationId=organisationId,eventType="DISPATCH_REVERSED",payloadJson=JSONObject().put("batchId",batchId).put("reason",reason).toString()))
  return true
 }
 @Query("UPDATE dispatch_batches SET status=:status WHERE id=:id") abstract suspend fun setDispatchStatus(id:Long,status:String)
 @Insert(onConflict=OnConflictStrategy.IGNORE) abstract suspend fun insertDispatchBatch(batch:DispatchBatchEntity):Long
 @Insert abstract suspend fun insertDispatchLines(lines:List<DispatchLineEntity>)
 @Insert abstract suspend fun insertInventoryMovement(m:InventoryMovementEntity):Long
 @Insert(onConflict=OnConflictStrategy.IGNORE) abstract suspend fun enqueueSyncEvent(e:SyncOutboxEntity):Long
 @Query("SELECT * FROM sync_outbox WHERE status='PENDING' ORDER BY createdAt LIMIT :limit") abstract suspend fun pendingSyncEvents(limit:Int=50):List<SyncOutboxEntity>
 @Query("UPDATE sync_outbox SET attempts=attempts+1,lastError=:error WHERE id=:id") abstract suspend fun markSyncAttempt(id:Long,error:String)
 @Query("UPDATE sync_outbox SET status='SYNCED',syncedAt=:at,lastError='' WHERE id=:id") abstract suspend fun markSyncSynced(id:Long,at:Long)
 @Query("SELECT * FROM machines ORDER BY name COLLATE NOCASE") abstract fun observeMachines():Flow<List<MachineEntity>>
 @Insert(onConflict=OnConflictStrategy.ABORT) abstract suspend fun insertMachine(e:MachineEntity):Long
 @Query("UPDATE machines SET name=:name,machineType=:type,location=:location,notes=:notes,updatedAt=:at WHERE id=:id") abstract suspend fun updateMachineDetails(id:Long,name:String,type:String,location:String,notes:String,at:Long=System.currentTimeMillis())
 @Query("UPDATE machines SET status=:status,updatedAt=:at WHERE id=:id") abstract suspend fun updateMachineStatus(id:Long,status:String,at:Long=System.currentTimeMillis())
 @Query("SELECT * FROM machine_events ORDER BY startedAt DESC") abstract fun observeMachineEvents():Flow<List<MachineEventEntity>>
 @Insert abstract suspend fun insertMachineEvent(e:MachineEventEntity):Long
 @Query("UPDATE machine_events SET status=:status,endedAt=:endedAt WHERE id=:id AND status='OPEN'") abstract suspend fun closeMachineEvent(id:Long,status:String="CLOSED",endedAt:Long=System.currentTimeMillis()):Int
 @Query("SELECT * FROM quality_inspections ORDER BY inspectionDate DESC,createdAt DESC") abstract fun observeQualityInspections():Flow<List<QualityInspectionEntity>>
 @Insert abstract suspend fun insertQualityInspection(e:QualityInspectionEntity):Long
 @Query("SELECT * FROM production_plans ORDER BY planDate DESC,createdAt DESC") abstract fun observeProductionPlans():Flow<List<ProductionPlanEntity>>
 @Insert abstract suspend fun insertProductionPlan(e:ProductionPlanEntity):Long
 @Query("UPDATE production_plans SET status=:status WHERE id=:id") abstract suspend fun updateProductionPlanStatus(id:Long,status:String)
 @Query("SELECT * FROM sales_orders ORDER BY orderDate DESC,createdAt DESC") abstract fun observeSalesOrders():Flow<List<SalesOrderEntity>>
 @Query("SELECT * FROM sales_order_lines ORDER BY orderId,id") abstract fun observeSalesOrderLines():Flow<List<SalesOrderLineEntity>>
 @Query("UPDATE sales_order_lines SET packsDispatched=:packs WHERE id=:id AND :packs>=0 AND :packs<=packsOrdered") abstract suspend fun setOrderLineFulfilled(id:Long,packs:Int):Int
 @Query("SELECT * FROM sales_order_lines WHERE productId=:productId AND orderId IN (SELECT id FROM sales_orders WHERE customerId=:customerId AND status IN ('OPEN','PARTIAL')) ORDER BY createdAt,id") abstract suspend fun openSalesOrderLines(customerId:Long,productId:Long):List<SalesOrderLineEntity>
 @Query("SELECT * FROM sales_order_lines WHERE orderId=:orderId ORDER BY id") abstract suspend fun salesOrderLinesForOrder(orderId:Long):List<SalesOrderLineEntity>
 @Query("UPDATE sales_orders SET status=:status WHERE id=:id") abstract suspend fun updateSalesOrderStatus(id:Long,status:String)
 @Insert(onConflict=OnConflictStrategy.ABORT) abstract suspend fun insertSalesOrder(e:SalesOrderEntity):Long
 @Insert abstract suspend fun insertSalesOrderLines(e:List<SalesOrderLineEntity>)
 @Query("SELECT * FROM inventory_movements ORDER BY createdAt DESC") abstract fun observeRecentInventoryMovements():Flow<List<InventoryMovementEntity>>
 @Query("SELECT * FROM customers WHERE active=1 ORDER BY name COLLATE NOCASE") abstract fun observeCustomers():Flow<List<CustomerEntity>>
 @Query("SELECT * FROM customers WHERE active=1 ORDER BY name COLLATE NOCASE") abstract suspend fun activeCustomers():List<CustomerEntity>
 @Insert(onConflict=OnConflictStrategy.ABORT) abstract suspend fun insertCustomer(c:CustomerEntity):Long
 @Query("UPDATE customers SET name=:name,phone=:phone,address=:address,gstNumber=:gst,active=:active WHERE id=:id") abstract suspend fun updateCustomer(id:Long,name:String,phone:String,address:String,gst:String,active:Boolean)
 @Query("SELECT * FROM meter_readings ORDER BY readingDate DESC") abstract fun observeMeterReadings():Flow<List<MeterReadingEntity>>
 @Insert abstract suspend fun insertMeterReading(e:MeterReadingEntity):Long

 @Transaction open suspend fun postMultiLineDispatch(batch:DispatchBatchEntity,requestedLines:List<DispatchRequestLine>,organisationId:String,actorId:String):DispatchPostResult {
  require(batch.idempotencyKey.isNotBlank()){"Idempotency key is required"};require(batch.customerNameSnapshot.isNotBlank()){"Customer is required"};require(requestedLines.isNotEmpty()){"Add at least one product line"}
  batchByIdempotencyKey(batch.idempotencyKey)?.let{return DispatchPostResult(it.id,it.dispatchReference,linesForBatch(it.id).size)}
  val merged=requestedLines.groupBy{it.productId}.map{(id,lines)->DispatchRequestLine(id,lines.sumOf{it.packs})}
  require(merged.all{it.packs>0}){"Every quantity must be positive"}
  val resolved:List<Triple<ProductEntity,Int,Int>> = merged.map{line->
   val product=productById(line.productId)?:error("Product not found")
   require(product.active){"Inactive product cannot be dispatched"}
   require(product.bottlesPerPack > 0){"Pack size is not configured for ${product.name}"}
   val available=totalCompletePacks(product.id)-legacyDispatchedPacks(product.id)-batchedDispatchedPacks(product.id)
   require(line.packs<=available){"Insufficient stock for "+product.name+": "+available+" packs available"}
   Triple(product,line.packs,available)
  }
  val id=insertDispatchBatch(batch)
  if(id<0){val existing=batchByIdempotencyKey(batch.idempotencyKey)?:error("Duplicate dispatch key could not be resolved");return DispatchPostResult(existing.id,existing.dispatchReference,linesForBatch(existing.id).size)}
  val rows=resolved.map{r->DispatchLineEntity(batchId=id,productId=r.first.id,productNameSnapshot=r.first.name,packSizeSnapshot=r.first.bottlesPerPack,packs=r.second,bottles=r.second*r.first.bottlesPerPack)}
  insertDispatchLines(rows)
  // Automatically allocate dispatched packs to this customer's oldest open matching orders.
  if(batch.customerId!=null){
   rows.forEach{dispatchLine->
    var remaining=dispatchLine.packs
    val openLines=openSalesOrderLines(batch.customerId,dispatchLine.productId)
    openLines.forEach{orderLine->
     if(remaining>0){
      val stillNeeded=(orderLine.packsOrdered-orderLine.packsDispatched).coerceAtLeast(0)
      val allocation=minOf(remaining,stillNeeded)
      if(allocation>0){setOrderLineFulfilled(orderLine.id,orderLine.packsDispatched+allocation);remaining-=allocation}
     }
    }
    openLines.map{it.orderId}.distinct().forEach{orderId->
     val current=salesOrderLinesForOrder(orderId)
     val ordered=current.sumOf{it.packsOrdered};val fulfilled=current.sumOf{it.packsDispatched}
     updateSalesOrderStatus(orderId,when{ordered>0&&fulfilled>=ordered->"FULFILLED";fulfilled>0->"PARTIAL";else->"OPEN"})
    }
   }
  }
  rows.forEach{line->insertInventoryMovement(InventoryMovementEntity(productId=line.productId,productNameSnapshot=line.productNameSnapshot,movementType="DISPATCH_OUT",quantity=-line.packs,unit="PACK",sourceType="dispatch",sourceId=id.toString(),note=batch.dispatchReference,actorId=actorId))}
  val payload=JSONArray();rows.forEach{line->payload.put(JSONObject().put("productId",line.productId).put("productName",line.productNameSnapshot).put("packs",line.packs).put("bottles",line.bottles))}
  insertAudit(AuditEventEntity(eventType="DISPATCH_POSTED",entityType="dispatch",entityId=id.toString(),afterJson=payload.toString(),reason="Dispatch posted",actorId=actorId))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="dispatch:"+organisationId+":"+batch.idempotencyKey,organisationId=organisationId,eventType="DISPATCH_POSTED",payloadJson=payload.toString()))
  return DispatchPostResult(id,batch.dispatchReference,rows.size)
 }
 @Transaction open suspend fun postPacking(entry:PackingEntryEntity,organisationId:String,actorId:String):Long {
  require(entry.bottlesConsumed>0){"Packing quantity must be positive"}
  val product=productById(entry.productId)?:error("Product not found");require(product.active){"Product is inactive"}
  require(product.bottlesPerPack > 0){"Pack size is not configured for ${product.name}"}
  val expectedPacks=entry.bottlesConsumed/product.bottlesPerPack;val expectedLoose=entry.bottlesConsumed%product.bottlesPerPack
  require(entry.completePacks==expectedPacks&&entry.looseBottles==expectedLoose){"Packing totals do not match the configured pack size"}
  val available=totalGoodBottles(entry.productId)-totalPackedBottles(entry.productId);require(entry.bottlesConsumed<=available){"Insufficient unpacked good bottles: "+available+" available"}
  val id=insertPacking(entry)
  insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="PACKING_BOTTLES_OUT",quantity=-entry.bottlesConsumed,unit="BOTTLE",sourceType="packing",sourceId=id.toString(),actorId=actorId))
  if(entry.completePacks>0) insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="PACKING_IN",quantity=entry.completePacks,unit="PACK",sourceType="packing",sourceId=id.toString(),actorId=actorId))
  insertAudit(AuditEventEntity(eventType="PACKING_POSTED",entityType="packing",entityId=id.toString(),reason="Packing posted",actorId=actorId))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="packing:"+organisationId+":"+id,organisationId=organisationId,eventType="PACKING_POSTED",payloadJson=JSONObject().put("productId",entry.productId).put("bottlesConsumed",entry.bottlesConsumed).put("completePacks",entry.completePacks).put("looseBottles",entry.looseBottles).toString()))
  return id
 }
}
private fun productJson(name:String,size:String,colour:String,pack:Int,active:Boolean)=JSONObject().put("name",name).put("size",size).put("colour",colour).put("bottlesPerPack",pack).put("active",active).toString()

@Database(entities=[ProductEntity::class,ProductConfigHistoryEntity::class,ProductionEntryEntity::class,PackingEntryEntity::class,DispatchEntryEntity::class,DispatchBatchEntity::class,DispatchLineEntity::class,InventoryMovementEntity::class,AuditEventEntity::class,SyncOutboxEntity::class,CustomerEntity::class,MeterReadingEntity::class,MachineEntity::class,MachineEventEntity::class,QualityInspectionEntity::class,ProductionPlanEntity::class,SalesOrderEntity::class,SalesOrderLineEntity::class],version=3,exportSchema=false)
abstract class FactoryDatabase:RoomDatabase(){
 abstract fun factoryDao():FactoryDao
 companion object {
  @Volatile private var instance:FactoryDatabase?=null
  private val MIGRATION_1_2=object:Migration(1,2){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("CREATE TABLE IF NOT EXISTS product_config_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,productId INTEGER NOT NULL,productName TEXT NOT NULL,sizeLabel TEXT NOT NULL,colour TEXT NOT NULL,bottlesPerPack INTEGER NOT NULL,active INTEGER NOT NULL,changedAt INTEGER NOT NULL,changedBy TEXT NOT NULL,reason TEXT NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_product_config_history_productId ON product_config_history(productId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_product_config_history_changedAt ON product_config_history(changedAt)")
    db.execSQL("CREATE TABLE IF NOT EXISTS customers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,phone TEXT NOT NULL,address TEXT NOT NULL,gstNumber TEXT NOT NULL,active INTEGER NOT NULL,createdAt INTEGER NOT NULL)");db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_customers_name ON customers(name)")
    db.execSQL("CREATE TABLE IF NOT EXISTS dispatch_batches (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,idempotencyKey TEXT NOT NULL,dispatchReference TEXT NOT NULL,dispatchDate TEXT NOT NULL,customerId INTEGER,customerNameSnapshot TEXT NOT NULL,status TEXT NOT NULL,remarks TEXT NOT NULL,createdAt INTEGER NOT NULL,createdBy TEXT NOT NULL)");db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dispatch_batches_idempotencyKey ON dispatch_batches(idempotencyKey)");db.execSQL("CREATE INDEX IF NOT EXISTS index_dispatch_batches_dispatchDate ON dispatch_batches(dispatchDate)");db.execSQL("CREATE INDEX IF NOT EXISTS index_dispatch_batches_customerId ON dispatch_batches(customerId)")
    db.execSQL("CREATE TABLE IF NOT EXISTS dispatch_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,batchId INTEGER NOT NULL,productId INTEGER NOT NULL,productNameSnapshot TEXT NOT NULL,packSizeSnapshot INTEGER NOT NULL,packs INTEGER NOT NULL,bottles INTEGER NOT NULL,createdAt INTEGER NOT NULL,FOREIGN KEY(batchId) REFERENCES dispatch_batches(id) ON DELETE CASCADE)");db.execSQL("CREATE INDEX IF NOT EXISTS index_dispatch_lines_batchId ON dispatch_lines(batchId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_dispatch_lines_productId ON dispatch_lines(productId)")
    db.execSQL("CREATE TABLE IF NOT EXISTS inventory_movements (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,productId INTEGER NOT NULL,productNameSnapshot TEXT NOT NULL,movementType TEXT NOT NULL,quantity INTEGER NOT NULL,unit TEXT NOT NULL,sourceType TEXT NOT NULL,sourceId TEXT NOT NULL,note TEXT NOT NULL,createdAt INTEGER NOT NULL,actorId TEXT NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_movements_productId ON inventory_movements(productId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_movements_createdAt ON inventory_movements(createdAt)");db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_movements_sourceId ON inventory_movements(sourceId)")
    db.execSQL("CREATE TABLE IF NOT EXISTS audit_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,eventType TEXT NOT NULL,entityType TEXT NOT NULL,entityId TEXT NOT NULL,beforeJson TEXT NOT NULL,afterJson TEXT NOT NULL,reason TEXT NOT NULL,actorId TEXT NOT NULL,createdAt INTEGER NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_entityType ON audit_events(entityType)");db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_entityId ON audit_events(entityId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_createdAt ON audit_events(createdAt)")
    db.execSQL("CREATE TABLE IF NOT EXISTS sync_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,eventKey TEXT NOT NULL,organisationId TEXT NOT NULL,eventType TEXT NOT NULL,payloadJson TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,lastError TEXT NOT NULL,createdAt INTEGER NOT NULL,syncedAt INTEGER)");db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_eventKey ON sync_outbox(eventKey)");db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_status ON sync_outbox(status)");db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_createdAt ON sync_outbox(createdAt)")
   }
  }
  private val MIGRATION_2_3=object:Migration(2,3){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("CREATE TABLE IF NOT EXISTS machines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,machineCode TEXT NOT NULL,name TEXT NOT NULL,machineType TEXT NOT NULL,location TEXT NOT NULL,status TEXT NOT NULL,notes TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_machines_machineCode ON machines(machineCode)")
    db.execSQL("CREATE TABLE IF NOT EXISTS machine_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,machineId INTEGER NOT NULL,machineNameSnapshot TEXT NOT NULL,eventType TEXT NOT NULL,startedAt INTEGER NOT NULL,endedAt INTEGER,reason TEXT NOT NULL,maintenanceType TEXT NOT NULL,status TEXT NOT NULL,performedBy TEXT NOT NULL,createdAt INTEGER NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_machine_events_machineId ON machine_events(machineId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_machine_events_startedAt ON machine_events(startedAt)");db.execSQL("CREATE INDEX IF NOT EXISTS index_machine_events_status ON machine_events(status)")
    db.execSQL("CREATE TABLE IF NOT EXISTS quality_inspections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,productionEntryId INTEGER,productId INTEGER NOT NULL,productNameSnapshot TEXT NOT NULL,inspectionDate TEXT NOT NULL,inspectedBottles INTEGER NOT NULL,passedBottles INTEGER NOT NULL,failedBottles INTEGER NOT NULL,defectCategory TEXT NOT NULL,severity TEXT NOT NULL,notes TEXT NOT NULL,inspectorId TEXT NOT NULL,createdAt INTEGER NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_quality_inspections_productId ON quality_inspections(productId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_quality_inspections_productionEntryId ON quality_inspections(productionEntryId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_quality_inspections_inspectionDate ON quality_inspections(inspectionDate)");db.execSQL("CREATE INDEX IF NOT EXISTS index_quality_inspections_status ON quality_inspections(severity)")
    db.execSQL("CREATE TABLE IF NOT EXISTS production_plans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,planDate TEXT NOT NULL,shift TEXT NOT NULL,productId INTEGER NOT NULL,productNameSnapshot TEXT NOT NULL,targetBottles INTEGER NOT NULL,assignedMachineId INTEGER,status TEXT NOT NULL,notes TEXT NOT NULL,createdBy TEXT NOT NULL,createdAt INTEGER NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_production_plans_planDate ON production_plans(planDate)");db.execSQL("CREATE INDEX IF NOT EXISTS index_production_plans_productId ON production_plans(productId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_production_plans_status ON production_plans(status)")
    db.execSQL("CREATE TABLE IF NOT EXISTS sales_orders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,orderNumber TEXT NOT NULL,customerId INTEGER,customerNameSnapshot TEXT NOT NULL,orderDate TEXT NOT NULL,dueDate TEXT NOT NULL,status TEXT NOT NULL,notes TEXT NOT NULL,createdBy TEXT NOT NULL,createdAt INTEGER NOT NULL)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sales_orders_orderNumber ON sales_orders(orderNumber)");db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_orders_customerId ON sales_orders(customerId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_orders_orderDate ON sales_orders(orderDate)");db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_orders_status ON sales_orders(status)")
    db.execSQL("CREATE TABLE IF NOT EXISTS sales_order_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,orderId INTEGER NOT NULL,productId INTEGER NOT NULL,productNameSnapshot TEXT NOT NULL,packsOrdered INTEGER NOT NULL,packsDispatched INTEGER NOT NULL,packSizeSnapshot INTEGER NOT NULL,createdAt INTEGER NOT NULL,FOREIGN KEY(orderId) REFERENCES sales_orders(id) ON DELETE CASCADE)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_order_lines_orderId ON sales_order_lines(orderId)");db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_order_lines_productId ON sales_order_lines(productId)")
   }
  }
  fun get(context:Context):FactoryDatabase=instance?: synchronized(this){instance?:Room.databaseBuilder(context.applicationContext,FactoryDatabase::class.java,"factoryflow.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build().also{instance=it}}
 }
}
