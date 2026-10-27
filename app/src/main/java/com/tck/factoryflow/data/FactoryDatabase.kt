package com.tck.factoryflow.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import org.json.JSONArray
import com.tck.factoryflow.domain.ProductionQuantities
import java.security.MessageDigest

fun stableProductCode(name:String,sizeLabel:String,colour:String):String {
    val canonical=listOf(name.trim().lowercase(),sizeLabel.trim().lowercase(),colour.trim().lowercase()).joinToString("|")
    val digest=MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    return "p-"+digest.take(32)
}

@Entity(tableName="products", indices=[Index(value=["name"], unique=true),Index(value=["productCode"], unique=true)])
data class ProductEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val name:String,
    val sizeLabel:String,
    val colour:String="Clear",
    val bottlesPerPack:Int,
    val active:Boolean=true,
    val createdAt:Long=System.currentTimeMillis(),
    val iconName:String="bottle_default",
    val imageUri:String="",
    @ColumnInfo(defaultValue="''") val productCode:String=stableProductCode(name,sizeLabel,colour)
)
@Entity(tableName="product_config_history",indices=[Index("productId"),Index("changedAt")])
data class ProductConfigHistoryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val productId:Long,val productName:String,val sizeLabel:String,val colour:String,val bottlesPerPack:Int,val active:Boolean,val changedAt:Long=System.currentTimeMillis(),val changedBy:String="local",val reason:String="Configuration updated")
@Entity(tableName="production_entries",indices=[Index("productId"),Index("productionDate"),Index("lineId"),Index("machineId"),Index("cloudOperationKey")])
data class ProductionEntryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val productId:Long,val productNameSnapshot:String,val productionDate:String,val producedBottles:Int,val goodBottles:Int,val rejectedBottles:Int,val machineName:String="Machine 1",val remarks:String="",val createdAt:Long=System.currentTimeMillis(),val lineId:Long?=null,val lineNameSnapshot:String="",val machineId:Long?=null,@ColumnInfo(defaultValue="''") val cloudOperationKey:String="")
@Entity(tableName="packing_entries",indices=[Index("productId"),Index("packingDate"),Index("cloudOperationKey")])
data class PackingEntryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val productId:Long,val productNameSnapshot:String,val packingDate:String,val bottlesConsumed:Int,val completePacks:Int,val looseBottles:Int,val createdAt:Long=System.currentTimeMillis(),@ColumnInfo(defaultValue="''") val cloudOperationKey:String="")
@Entity(tableName="dispatch_entries",indices=[Index("dispatchDate"),Index("customerName")])
data class DispatchEntryEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val dispatchReference:String,val dispatchDate:String,val customerName:String,val productId:Long,val productNameSnapshot:String,val packsDispatched:Int,val remarks:String="",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="customers",indices=[Index(value=["name"],unique=true)])
data class CustomerEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val name:String,val phone:String="",val address:String="",val gstNumber:String="",val active:Boolean=true,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="dispatch_batches",indices=[Index(value=["idempotencyKey"],unique=true),Index("dispatchDate"),Index("customerId")])
data class DispatchBatchEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val idempotencyKey:String,val dispatchReference:String,val dispatchDate:String,val customerId:Long?,val customerNameSnapshot:String,val status:String="POSTED",val remarks:String="",val createdAt:Long=System.currentTimeMillis(),val createdBy:String="local",val salesOrderId:Long?=null)
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

data class DispatchRequestLine(val productId:Long,val packs:Int,val looseBottles:Int=0)
data class DispatchPostResult(val batchId:Long,val reference:String,val lineCount:Int)

@Entity(tableName="production_lines",indices=[Index(value=["lineCode"],unique=true)])
data class ProductionLineEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val lineCode:String,val name:String,val description:String="",val status:String="ACTIVE",val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=System.currentTimeMillis(),val targetCapacity:Int=0,val capacityUnit:String="BOTTLES_PER_HOUR")
@Entity(tableName="machines",indices=[Index(value=["machineCode"],unique=true),Index("lineId")],foreignKeys=[ForeignKey(entity=ProductionLineEntity::class,parentColumns=["id"],childColumns=["lineId"],onDelete=ForeignKey.SET_NULL)])
data class MachineEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val machineCode:String,val name:String,val machineType:String,val location:String="",val status:String="RUNNING",val notes:String="",val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=System.currentTimeMillis(),val lineId:Long?=null)
@Entity(tableName="machine_events",indices=[Index("machineId"),Index("startedAt"),Index("status")])
data class MachineEventEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val machineId:Long,val machineNameSnapshot:String,val eventType:String,val startedAt:Long,val endedAt:Long?=null,val reason:String="",val maintenanceType:String="",val status:String="OPEN",val performedBy:String="local",val createdAt:Long=System.currentTimeMillis(),val completionNotes:String="",val partsUsed:String="",val completedBy:String="")
@Entity(tableName="quality_inspections",indices=[Index("productId"),Index("productionEntryId"),Index("inspectionDate")])
data class QualityInspectionEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val productionEntryId:Long?,val productId:Long,val productNameSnapshot:String,val inspectionDate:String,val inspectedBottles:Int,val passedBottles:Int,val failedBottles:Int,val defectCategory:String,val severity:String="MINOR",val notes:String="",val inspectorId:String="local",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="production_plans",indices=[Index("planDate"),Index("productId"),Index("status"),Index("lineId")])
data class ProductionPlanEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val planDate:String,val shift:String,val productId:Long,val productNameSnapshot:String,val targetBottles:Int,val assignedMachineId:Long?=null,val status:String="PLANNED",val notes:String="",val createdBy:String="local",val createdAt:Long=System.currentTimeMillis(),val lineId:Long?=null)
@Entity(tableName="sales_orders",indices=[Index(value=["orderNumber"],unique=true),Index("customerId"),Index("orderDate"),Index("status")])
data class SalesOrderEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val orderNumber:String,val customerId:Long?,val customerNameSnapshot:String,val orderDate:String,val dueDate:String,val status:String="OPEN",val notes:String="",val createdBy:String="local",val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="sales_order_lines",indices=[Index("orderId"),Index("productId")],foreignKeys=[ForeignKey(entity=SalesOrderEntity::class,parentColumns=["id"],childColumns=["orderId"],onDelete=ForeignKey.CASCADE)])
data class SalesOrderLineEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val orderId:Long,val productId:Long,val productNameSnapshot:String,val packsOrdered:Int,val packsDispatched:Int=0,val packSizeSnapshot:Int,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="staff_members",indices=[Index(value=["email"],unique=true),Index("role")])
data class StaffMemberEntity(@PrimaryKey(autoGenerate=true) val id:Long=0,val uid:String,val name:String,val email:String,val role:String,val allowedModules:String="ALL",val active:Boolean=true,val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=System.currentTimeMillis())
@Dao abstract class FactoryDao {
 @Query("SELECT * FROM staff_members ORDER BY name COLLATE NOCASE") abstract fun observeStaffMembers():Flow<List<StaffMemberEntity>>
 @Query("SELECT * FROM staff_members WHERE email=:email LIMIT 1") abstract suspend fun staffByEmail(email:String):StaffMemberEntity?
 @Insert(onConflict=OnConflictStrategy.REPLACE) abstract suspend fun insertStaffMember(staff:StaffMemberEntity):Long
 @Query("UPDATE staff_members SET name=:name,role=:role,allowedModules=:allowedModules,active=:active,updatedAt=:updatedAt WHERE id=:id") abstract suspend fun updateStaffMember(id:Long,name:String,role:String,allowedModules:String,active:Boolean,updatedAt:Long=System.currentTimeMillis()):Int
 @Query("UPDATE staff_members SET active=:active,updatedAt=:updatedAt WHERE id=:id") abstract suspend fun setStaffMemberActive(id:Long,active:Boolean,updatedAt:Long=System.currentTimeMillis()):Int
 @Query("DELETE FROM staff_members WHERE id=:id") abstract suspend fun deleteStaffMember(id:Long):Int
 @Query("DELETE FROM products WHERE id=:id") abstract suspend fun deleteProduct(id:Long):Int
 @Transaction open suspend fun removeProduct(id:Long,actor:String,reason:String,organisationId:String) {
  val old=productById(id)?:return
  if(!old.active) return
  // Product IDs are referenced by production, packing, stock movements, dispatches and reports.
  // Archive instead of deleting so historical records and foreign-key relationships remain intact.
  updateProductWithHistory(id,old.name,old.sizeLabel,old.colour,old.bottlesPerPack,false,actor,reason,organisationId,old.iconName,old.imageUri)
  insertAudit(AuditEventEntity(eventType="PRODUCT_ARCHIVED",entityType="product",entityId=id.toString(),beforeJson=productJson(old.name,old.sizeLabel,old.colour,old.bottlesPerPack,old.active),afterJson=productJson(old.name,old.sizeLabel,old.colour,old.bottlesPerPack,false),reason=reason,actorId=actor))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="product-archive:"+organisationId+":"+id+":"+System.currentTimeMillis(),organisationId=organisationId,eventType="PRODUCT_ARCHIVED",payloadJson=JSONObject().put("productId",id).put("name",old.name).put("active",false).toString()))
 }
 @Query("SELECT * FROM production_lines ORDER BY status DESC, name COLLATE NOCASE") abstract fun observeProductionLines():Flow<List<ProductionLineEntity>>
 @Query("SELECT * FROM production_lines WHERE status='ACTIVE' ORDER BY name COLLATE NOCASE") abstract suspend fun activeProductionLines():List<ProductionLineEntity>
 @Query("SELECT * FROM production_lines WHERE id=:id LIMIT 1") abstract suspend fun lineById(id:Long):ProductionLineEntity?
 @Insert(onConflict=OnConflictStrategy.ABORT) abstract suspend fun insertProductionLine(line:ProductionLineEntity):Long
 @Query("UPDATE production_lines SET name=:name,description=:description,targetCapacity=:capacity,capacityUnit=:unit,status=:status,updatedAt=:at WHERE id=:id") abstract suspend fun updateProductionLine(id:Long,name:String,description:String,capacity:Int,unit:String,status:String,at:Long=System.currentTimeMillis())
 @Query("DELETE FROM production_lines WHERE id=:id") abstract suspend fun deleteProductionLine(id:Long)
 @Query("DELETE FROM machines WHERE id=:id") abstract suspend fun deleteMachine(id:Long)
 @Transaction open suspend fun removeProductionLine(id:Long,actor:String,reason:String,organisationId:String) {
  val line=lineById(id)?:return
  deleteProductionLine(id)
  insertAudit(AuditEventEntity(eventType="PRODUCTION_LINE_DELETED",entityType="production_line",entityId=id.toString(),beforeJson=line.name,afterJson="DELETED",reason=reason,actorId=actor))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="line-delete:$organisationId:$id",organisationId=organisationId,eventType="PRODUCTION_LINE_DELETED",payloadJson=org.json.JSONObject().put("lineId",id).put("lineCode",line.lineCode).toString()))
 }
 @Transaction open suspend fun removeMachine(id:Long,actor:String,reason:String,organisationId:String) {
  val m=machineById(id)?:return
  deleteMachine(id)
  insertAudit(AuditEventEntity(eventType="MACHINE_DELETED",entityType="machine",entityId=id.toString(),beforeJson=m.name,afterJson="DELETED",reason=reason,actorId=actor))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="machine-delete:$organisationId:$id",organisationId=organisationId,eventType="MACHINE_DELETED",payloadJson=org.json.JSONObject().put("machineId",id).put("machineCode",m.machineCode).toString()))
 }
 @Query("UPDATE machines SET lineId=:lineId,updatedAt=:updatedAt WHERE id=:machineId") abstract suspend fun assignMachineToLine(machineId:Long,lineId:Long?,updatedAt:Long=System.currentTimeMillis())
 @Query("SELECT * FROM machines WHERE lineId=:lineId ORDER BY name COLLATE NOCASE") abstract fun observeMachinesForLine(lineId:Long):Flow<List<MachineEntity>>
 @Query("SELECT * FROM machines WHERE lineId=:lineId ORDER BY name COLLATE NOCASE") abstract suspend fun machinesForLine(lineId:Long):List<MachineEntity>
 @Query("SELECT * FROM machines WHERE lineId=:lineId AND status='RUNNING' ORDER BY name COLLATE NOCASE") abstract suspend fun activeMachinesForLine(lineId:Long):List<MachineEntity>
 @Query("SELECT * FROM machines WHERE status='RUNNING' ORDER BY name COLLATE NOCASE") abstract suspend fun activeMachines():List<MachineEntity>
 @Query("SELECT * FROM machines WHERE id=:id LIMIT 1") abstract suspend fun machineById(id:Long):MachineEntity?
 @Query("SELECT * FROM products ORDER BY active DESC, name COLLATE NOCASE") abstract fun observeProducts():Flow<List<ProductEntity>>
 @Query("SELECT * FROM products WHERE active=1 ORDER BY name COLLATE NOCASE") abstract suspend fun activeProducts():List<ProductEntity>
 @Query("SELECT * FROM products WHERE id=:id LIMIT 1") abstract suspend fun productById(id:Long):ProductEntity?
 @Query("SELECT * FROM products WHERE productCode=:code LIMIT 1") abstract suspend fun productByCode(code:String):ProductEntity?
 @Query("SELECT * FROM products WHERE name=:name COLLATE NOCASE LIMIT 1") abstract suspend fun productByName(name:String):ProductEntity?
 @Insert(onConflict=OnConflictStrategy.ABORT) abstract suspend fun insertProduct(p:ProductEntity):Long
 @Query("UPDATE products SET name=:name,sizeLabel=:size,colour=:colour,bottlesPerPack=:pack,active=:active WHERE id=:id") abstract suspend fun updateProduct(id:Long,name:String,size:String,colour:String,pack:Int,active:Boolean)
 @Query("UPDATE products SET name=:name,sizeLabel=:size,colour=:colour,bottlesPerPack=:pack,active=:active,iconName=:iconName,imageUri=:imageUri WHERE id=:id") abstract suspend fun updateProductDetails(id:Long,name:String,size:String,colour:String,pack:Int,active:Boolean,iconName:String,imageUri:String)
 @Insert abstract suspend fun insertProductHistory(e:ProductConfigHistoryEntity):Long
 @Query("SELECT * FROM product_config_history WHERE productId=:id ORDER BY changedAt DESC") abstract fun observeProductHistory(id:Long):Flow<List<ProductConfigHistoryEntity>>
 @Insert abstract suspend fun insertAudit(a:AuditEventEntity):Long
 @Transaction open suspend fun updateProductWithHistory(id:Long,name:String,size:String,colour:String,pack:Int,active:Boolean,actor:String,reason:String,organisationId:String,iconName:String="bottle_default",imageUri:String="") {
  require(name.isNotBlank()&&size.isNotBlank());require(pack>=0)
  val old=productById(id)?:error("Product not found")
  insertProductHistory(ProductConfigHistoryEntity(productId=old.id,productName=old.name,sizeLabel=old.sizeLabel,colour=old.colour,bottlesPerPack=old.bottlesPerPack,active=old.active,changedBy=actor,reason=reason))
  updateProductDetails(id,name.trim(),size.trim(),colour.trim(),pack,active,iconName,imageUri)
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
 @Transaction open suspend fun recordCloudProduction(entry:ProductionEntryEntity,actorId:String):Long {
  require(entry.cloudOperationKey.isNotBlank()){"Cloud operation key is required for a server-confirmed production record"}
  productionByCloudKey(entry.cloudOperationKey)?.let{return it.id}
  ProductionQuantities(entry.producedBottles,entry.goodBottles,entry.rejectedBottles).validate()
  val product=productById(entry.productId)?:error("Product not found")
  require(product.active){"Product is inactive"}
  val id=insertProduction(entry)
  insertAudit(AuditEventEntity(eventType="CLOUD_PRODUCTION_CONFIRMED",entityType="production",entityId=id.toString(),afterJson=JSONObject().put("cloudOperationKey",entry.cloudOperationKey).put("goodBottles",entry.goodBottles).toString(),reason="Confirmed by authoritative inventory server",actorId=actorId))
  return id
 }
 @Transaction open suspend fun recordCloudPacking(entry:PackingEntryEntity,actorId:String):Long {
  require(entry.cloudOperationKey.isNotBlank()){"Cloud operation key is required for a server-confirmed packing record"}
  packingByCloudKey(entry.cloudOperationKey)?.let{return it.id}
  require(entry.bottlesConsumed>0){"Packing quantity must be positive"}
  val product=productById(entry.productId)?:error("Product not found")
  require(product.active){"Product is inactive"}
  require(product.bottlesPerPack>0){"Pack size is not configured for "+product.name}
  require(entry.completePacks==entry.bottlesConsumed/product.bottlesPerPack&&entry.looseBottles==entry.bottlesConsumed%product.bottlesPerPack){"Packing totals do not match configured pack size"}
  val id=insertPacking(entry)
  insertAudit(AuditEventEntity(eventType="CLOUD_PACKING_CONFIRMED",entityType="packing",entityId=id.toString(),afterJson=JSONObject().put("cloudOperationKey",entry.cloudOperationKey).put("bottlesConsumed",entry.bottlesConsumed).toString(),reason="Confirmed by authoritative inventory server",actorId=actorId))
  return id
 }
 @Transaction open suspend fun postProduction(entry:ProductionEntryEntity,organisationId:String,actorId:String):Long {
  ProductionQuantities(entry.producedBottles,entry.goodBottles,entry.rejectedBottles).validate()
  val product=productById(entry.productId)?:error("Product not found")
  require(product.active){"Product is inactive"}
  val id=insertProduction(entry)
  if(entry.machineId!=null){
   val machine=machineById(entry.machineId)?:error("Selected machine not found")
   require(machine.status=="RUNNING"){"Machine ${machine.name} is ${machine.status}; only running machines can record new production runs."}
  }
  if(entry.goodBottles>0) insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="PRODUCTION_GOOD_IN",quantity=entry.goodBottles,unit="BOTTLE",sourceType="production",sourceId=id.toString(),actorId=actorId))
  if(entry.rejectedBottles>0) insertInventoryMovement(InventoryMovementEntity(productId=entry.productId,productNameSnapshot=entry.productNameSnapshot,movementType="PRODUCTION_REJECTED",quantity=-entry.rejectedBottles,unit="BOTTLE",sourceType="production",sourceId=id.toString(),actorId=actorId))
  val payload=JSONObject().put("productionId",id).put("productId",entry.productId).put("date",entry.productionDate).put("produced",entry.producedBottles).put("good",entry.goodBottles).put("rejected",entry.rejectedBottles).put("machine",entry.machineName).put("lineId",entry.lineId).put("machineId",entry.machineId).toString()
  insertAudit(AuditEventEntity(eventType="PRODUCTION_POSTED",entityType="production",entityId=id.toString(),afterJson=payload,reason="Production posted",actorId=actorId))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="production:"+organisationId+":"+id,organisationId=organisationId,eventType="PRODUCTION_POSTED",payloadJson=payload))
  return id
 }
 @Query("SELECT * FROM production_entries ORDER BY productionDate DESC,createdAt DESC") abstract fun observeProduction():Flow<List<ProductionEntryEntity>>
 @Query("SELECT COALESCE(SUM(goodBottles),0) FROM production_entries WHERE productId=:id AND id NOT IN (SELECT CAST(entityId AS INTEGER) FROM audit_events WHERE eventType='PRODUCTION_REVERSED' AND entityType='production')") abstract suspend fun totalGoodBottles(id:Long):Int
 @Insert abstract suspend fun insertProduction(e:ProductionEntryEntity):Long
 @Query("SELECT * FROM production_entries WHERE cloudOperationKey=:key LIMIT 1") abstract suspend fun productionByCloudKey(key:String):ProductionEntryEntity?
 @Query("SELECT * FROM packing_entries WHERE cloudOperationKey=:key LIMIT 1") abstract suspend fun packingByCloudKey(key:String):PackingEntryEntity?
 @Query("SELECT * FROM packing_entries ORDER BY packingDate DESC,createdAt DESC") abstract fun observePacking():Flow<List<PackingEntryEntity>>
 @Query("SELECT COALESCE(SUM(bottlesConsumed),0) FROM packing_entries WHERE productId=:id AND id NOT IN (SELECT CAST(entityId AS INTEGER) FROM audit_events WHERE eventType='PACKING_REVERSED' AND entityType='packing')") abstract suspend fun totalPackedBottles(id:Long):Int
 @Query("SELECT COALESCE(SUM(completePacks),0) FROM packing_entries WHERE productId=:id AND id NOT IN (SELECT CAST(entityId AS INTEGER) FROM audit_events WHERE eventType='PACKING_REVERSED' AND entityType='packing')") abstract suspend fun totalCompletePacks(id:Long):Int
 @Query("SELECT COALESCE(SUM(looseBottles),0) FROM packing_entries WHERE productId=:id AND id NOT IN (SELECT CAST(entityId AS INTEGER) FROM audit_events WHERE eventType='PACKING_REVERSED' AND entityType='packing')") abstract suspend fun totalLooseBottlesPacked(id:Long):Int
 @Query("SELECT COALESCE(SUM(bottles - packs * packSizeSnapshot),0) FROM dispatch_lines WHERE productId=:id AND batchId IN (SELECT id FROM dispatch_batches WHERE status='POSTED')") abstract suspend fun totalDispatchedLooseBottles(id:Long):Int
 @Insert abstract suspend fun insertPacking(e:PackingEntryEntity):Long
 @Query("SELECT * FROM dispatch_entries ORDER BY dispatchDate DESC,createdAt DESC") abstract fun observeDispatches():Flow<List<DispatchEntryEntity>>
 @Query("SELECT COALESCE(SUM(packsDispatched),0) FROM dispatch_entries WHERE productId=:id") abstract suspend fun totalDispatchedPacks(id:Long):Int
 @Insert abstract suspend fun insertDispatch(e:DispatchEntryEntity):Long
 @Query("SELECT * FROM dispatch_batches ORDER BY dispatchDate DESC,createdAt DESC") abstract fun observeDispatchBatches():Flow<List<DispatchBatchEntity>>
 @Query("SELECT * FROM sales_orders WHERE status IN ('OPEN','PARTIAL') ORDER BY dueDate,orderDate") abstract fun observeOpenSalesOrders():Flow<List<SalesOrderEntity>>
 @Query("SELECT * FROM sales_order_lines WHERE orderId=:orderId ORDER BY id") abstract fun observeSalesOrderLinesForOrder(orderId:Long):Flow<List<SalesOrderLineEntity>>
 @Query("SELECT * FROM dispatch_batches WHERE salesOrderId=:orderId AND status='POSTED' ORDER BY createdAt DESC") abstract fun observeDispatchesForOrder(orderId:Long):Flow<List<DispatchBatchEntity>>
 @Query("SELECT COALESCE(SUM(packs),0) FROM dispatch_lines WHERE batchId IN (SELECT id FROM dispatch_batches WHERE salesOrderId=:orderId AND status='POSTED') AND productId=:productId") abstract fun postedPacksForOrderProduct(orderId:Long,productId:Long):Int
 @Query("UPDATE sales_order_lines SET packsDispatched=:packs WHERE id=:lineId AND :packs>=0 AND :packs<=packsOrdered") abstract suspend fun setSalesOrderLineFulfilled(lineId:Long,packs:Int):Int
 @Query("SELECT * FROM sales_order_lines WHERE orderId=:orderId ORDER BY id") abstract suspend fun salesOrderLinesForOrder(orderId:Long):List<SalesOrderLineEntity>
 @Transaction open suspend fun postSalesOrderDispatch(batch:DispatchBatchEntity,requestedLines:List<DispatchRequestLine>,organisationId:String,actorId:String):DispatchPostResult {
  require(batch.salesOrderId!=null){"Select a sales order before dispatch"}
  val order=salesOrderById(batch.salesOrderId)?:error("Sales order not found")
  require(order.customerId==batch.customerId){"Dispatch customer does not match selected order"}
  require(order.status!="CANCELLED"&&order.status!="FULFILLED"){"Order is not open for dispatch"}
  val orderLines=salesOrderLinesForOrder(order.id)
  require(orderLines.isNotEmpty()){"Sales order has no product lines"}
  val merged=requestedLines.groupBy{it.productId}.map{(id,lines)->DispatchRequestLine(id,lines.sumOf{it.packs})}
  require(merged.isNotEmpty()&&merged.all{it.packs>0}){"Dispatch quantities must be positive"}
  merged.forEach{item->
   val ordered=orderLines.filter{it.productId==item.productId}.sumOf{it.packsOrdered}
   val already=postedPacksForOrderProduct(order.id,item.productId)
   require(ordered>0){"Product is not part of this order"}
   require(item.packs<=ordered-already){"Dispatch exceeds remaining order quantity"}
  }
  val result=postMultiLineDispatch(batch.copy(salesOrderId=order.id),merged,organisationId,actorId)
  val current=salesOrderLinesForOrder(order.id)
  val remainingByProduct=mutableMapOf<Long,Int>()
  val updated=current.map{item->
   val remaining=remainingByProduct.getOrPut(item.productId){postedPacksForOrderProduct(order.id,item.productId)}
   val allocated=minOf(item.packsOrdered,remaining.coerceAtLeast(0))
   remainingByProduct[item.productId]=(remaining-allocated).coerceAtLeast(0)
   item.copy(packsDispatched=allocated)
  }
  updated.forEach{setSalesOrderLineFulfilled(it.id,it.packsDispatched.coerceAtMost(it.packsOrdered))}
  val totalOrdered=updated.sumOf{it.packsOrdered};val totalDispatched=updated.sumOf{it.packsDispatched}
  updateSalesOrderStatus(order.id,when{totalOrdered>0&&totalDispatched>=totalOrdered->"FULFILLED";totalDispatched>0->"PARTIAL";else->"OPEN"})
  insertAudit(AuditEventEntity(eventType="ORDER_DISPATCH_LINKED",entityType="sales_order",entityId=order.id.toString(),afterJson="dispatchId=${result.batchId},reference=${batch.dispatchReference}",reason="Dispatch posted against explicit sales order",actorId=actorId))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="order-dispatch-link:"+order.id+":"+result.batchId,organisationId=organisationId,eventType="ORDER_DISPATCH_LINKED",payloadJson=JSONObject().put("orderId",order.id).put("dispatchId",result.batchId).put("dispatchReference",batch.dispatchReference).toString()))
  return result
 }

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
 @Query("SELECT COALESCE(SUM(rejectedBottles),0) FROM production_entries WHERE productId=:id") abstract suspend fun totalRejectedBottles(id:Long):Int
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
  // A reasoned reversal is a compensating transaction; original lines remain immutable.
  // The batch status change excludes its quantities from net stock, while movements preserve the audit trail.
  setDispatchStatus(batchId,"REVERSED")
  lines.forEach { line ->
   if(line.packs>0) insertInventoryMovement(InventoryMovementEntity(productId=line.productId,productNameSnapshot=line.productNameSnapshot,movementType="DISPATCH_REVERSAL_IN",quantity=line.packs,unit="PACK",sourceType="dispatch_reversal",sourceId=batchId.toString(),note=reason,actorId=actorId))
   val loose=(line.bottles-line.packs*line.packSizeSnapshot).coerceAtLeast(0)
   if(loose>0) insertInventoryMovement(InventoryMovementEntity(productId=line.productId,productNameSnapshot=line.productNameSnapshot,movementType="DISPATCH_LOOSE_REVERSAL_IN",quantity=loose,unit="BOTTLE",sourceType="dispatch_reversal",sourceId=batchId.toString(),note=reason,actorId=actorId))
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
 @Query("SELECT * FROM sync_outbox ORDER BY createdAt DESC") abstract fun observeSyncOutbox():Flow<List<SyncOutboxEntity>>
 @Query("UPDATE sync_outbox SET attempts=attempts+1,lastError=:error WHERE id=:id") abstract suspend fun markSyncAttempt(id:Long,error:String)
 @Query("UPDATE sync_outbox SET status='SYNCED',syncedAt=:at,lastError='' WHERE id=:id") abstract suspend fun markSyncSynced(id:Long,at:Long)
 @Query("UPDATE sync_outbox SET status='NEEDS_REVIEW',lastError=:reason WHERE id=:id") abstract suspend fun markSyncNeedsReview(id:Long,reason:String)
 @Query("SELECT * FROM machines ORDER BY name COLLATE NOCASE") abstract fun observeMachines():Flow<List<MachineEntity>>
 @Insert(onConflict=OnConflictStrategy.ABORT) abstract suspend fun insertMachine(e:MachineEntity):Long
 @Query("UPDATE machines SET name=:name,machineType=:type,location=:location,notes=:notes,updatedAt=:at WHERE id=:id") abstract suspend fun updateMachineDetails(id:Long,name:String,type:String,location:String,notes:String,at:Long=System.currentTimeMillis())
 @Query("UPDATE machines SET name=:name,machineType=:type,location=:location,notes=:notes,lineId=:lineId,updatedAt=:at WHERE id=:id") abstract suspend fun updateMachineWithLine(id:Long,name:String,type:String,location:String,notes:String,lineId:Long?,at:Long=System.currentTimeMillis())
 @Query("UPDATE machines SET status=:status,updatedAt=:at WHERE id=:id") abstract suspend fun updateMachineStatus(id:Long,status:String,at:Long=System.currentTimeMillis())
 @Query("SELECT * FROM machine_events ORDER BY startedAt DESC") abstract fun observeMachineEvents():Flow<List<MachineEventEntity>>
 @Insert abstract suspend fun insertMachineEvent(e:MachineEventEntity):Long
 @Query("UPDATE machine_events SET status=:status,endedAt=:endedAt,completionNotes=:completionNotes,partsUsed=:partsUsed,completedBy=:completedBy WHERE id=:id AND status='OPEN'") abstract suspend fun closeMachineEvent(id:Long,status:String="CLOSED",endedAt:Long=System.currentTimeMillis(),completionNotes:String="",partsUsed:String="",completedBy:String="local"):Int
 @Query("SELECT * FROM quality_inspections ORDER BY inspectionDate DESC,createdAt DESC") abstract fun observeQualityInspections():Flow<List<QualityInspectionEntity>>
 @Insert abstract suspend fun insertQualityInspection(e:QualityInspectionEntity):Long
 @Query("SELECT * FROM production_plans ORDER BY planDate DESC,createdAt DESC") abstract fun observeProductionPlans():Flow<List<ProductionPlanEntity>>
 @Insert abstract suspend fun insertProductionPlan(e:ProductionPlanEntity):Long
 @Query("UPDATE production_plans SET status=:status WHERE id=:id") abstract suspend fun updateProductionPlanStatus(id:Long,status:String)
 @Query("SELECT * FROM sales_orders ORDER BY orderDate DESC,createdAt DESC") abstract fun observeSalesOrders():Flow<List<SalesOrderEntity>>
 @Query("SELECT * FROM sales_orders WHERE id=:id LIMIT 1") abstract suspend fun salesOrderById(id:Long):SalesOrderEntity?
 @Query("SELECT * FROM sales_order_lines ORDER BY orderId,id") abstract fun observeSalesOrderLines():Flow<List<SalesOrderLineEntity>>
 @Query("UPDATE sales_order_lines SET packsDispatched=:packs WHERE id=:id AND :packs>=0 AND :packs<=packsOrdered") abstract suspend fun setOrderLineFulfilled(id:Long,packs:Int):Int
 @Query("SELECT * FROM sales_order_lines WHERE productId=:productId AND orderId IN (SELECT id FROM sales_orders WHERE customerId=:customerId AND status IN ('OPEN','PARTIAL')) ORDER BY createdAt,id") abstract suspend fun openSalesOrderLines(customerId:Long,productId:Long):List<SalesOrderLineEntity>
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
  val merged=requestedLines.groupBy{it.productId}.map{(id,lines)->DispatchRequestLine(id,lines.sumOf{it.packs},lines.sumOf{it.looseBottles})}
  require(merged.all{it.packs>=0&&it.looseBottles>=0&&(it.packs>0||it.looseBottles>0)}){"Each dispatch line must contain packs, loose bottles, or both"}
  val resolved:List<Triple<ProductEntity,DispatchRequestLine,Int>> = merged.map{line->
   val product=productById(line.productId)?:error("Product not found")
   require(product.active){"Inactive product cannot be dispatched"}
   require(product.bottlesPerPack > 0){"Pack size is not configured for ${product.name}"}
   val availablePacks=totalCompletePacks(product.id)-legacyDispatchedPacks(product.id)-batchedDispatchedPacks(product.id)
   val availableLoose=(totalLooseBottlesPacked(product.id)-totalDispatchedLooseBottles(product.id)).coerceAtLeast(0)
   require(line.packs<=availablePacks){"Insufficient complete-pack stock for ${product.name}: ${availablePacks} packs available"}
   require(line.looseBottles<=availableLoose){"Insufficient loose-bottle stock for ${product.name}: ${availableLoose} bottles available. Break a pack through the authorised stock workflow if required."}
   Triple(product,line,availablePacks)
  }
  val id=insertDispatchBatch(batch)
  if(id<0){val existing=batchByIdempotencyKey(batch.idempotencyKey)?:error("Duplicate dispatch key could not be resolved");return DispatchPostResult(existing.id,existing.dispatchReference,linesForBatch(existing.id).size)}
  val rows=resolved.map{r->DispatchLineEntity(batchId=id,productId=r.first.id,productNameSnapshot=r.first.name,packSizeSnapshot=r.first.bottlesPerPack,packs=r.second.packs,bottles=r.second.packs*r.first.bottlesPerPack+r.second.looseBottles)}
  insertDispatchLines(rows)
  // Automatically allocate dispatched packs to this customer's oldest open matching orders.
  if(batch.customerId!=null&&batch.salesOrderId==null){
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
  rows.forEach{line->
   if(line.packs>0) insertInventoryMovement(InventoryMovementEntity(productId=line.productId,productNameSnapshot=line.productNameSnapshot,movementType="DISPATCH_OUT",quantity=-line.packs,unit="PACK",sourceType="dispatch",sourceId=id.toString(),note=batch.dispatchReference,actorId=actorId))
   val loose=line.bottles-line.packs*line.packSizeSnapshot
   if(loose>0) insertInventoryMovement(InventoryMovementEntity(productId=line.productId,productNameSnapshot=line.productNameSnapshot,movementType="DISPATCH_LOOSE_OUT",quantity=-loose,unit="BOTTLE",sourceType="dispatch",sourceId=id.toString(),note=batch.dispatchReference,actorId=actorId))
  }
  val payload=JSONArray();rows.forEach{line->payload.put(JSONObject().put("productId",line.productId).put("productName",line.productNameSnapshot).put("packs",line.packs).put("looseBottles",line.bottles-line.packs*line.packSizeSnapshot).put("bottles",line.bottles))}
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

@Database(entities=[ProductEntity::class,ProductConfigHistoryEntity::class,ProductionEntryEntity::class,PackingEntryEntity::class,DispatchEntryEntity::class,DispatchBatchEntity::class,DispatchLineEntity::class,InventoryMovementEntity::class,AuditEventEntity::class,SyncOutboxEntity::class,CustomerEntity::class,MeterReadingEntity::class,ProductionLineEntity::class,MachineEntity::class,MachineEventEntity::class,QualityInspectionEntity::class,ProductionPlanEntity::class,SalesOrderEntity::class,SalesOrderLineEntity::class,StaffMemberEntity::class],version=11,exportSchema=false)
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
  internal val MIGRATION_4_5=object:Migration(4,5){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("ALTER TABLE machine_events ADD COLUMN completionNotes TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE machine_events ADD COLUMN partsUsed TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE machine_events ADD COLUMN completedBy TEXT NOT NULL DEFAULT ''")
   }
  }
  internal val MIGRATION_3_4=object:Migration(3,4){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("ALTER TABLE dispatch_batches ADD COLUMN salesOrderId INTEGER")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_dispatch_batches_salesOrderId ON dispatch_batches(salesOrderId)")
   }
  }
  internal val MIGRATION_5_6=object:Migration(5,6){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("CREATE TABLE IF NOT EXISTS production_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,lineCode TEXT NOT NULL,name TEXT NOT NULL,description TEXT NOT NULL,status TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_production_lines_lineCode ON production_lines(lineCode)")
    db.execSQL("CREATE TABLE IF NOT EXISTS machines_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,machineCode TEXT NOT NULL,name TEXT NOT NULL,machineType TEXT NOT NULL,location TEXT NOT NULL,status TEXT NOT NULL,notes TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,lineId INTEGER,FOREIGN KEY(lineId) REFERENCES production_lines(id) ON DELETE SET NULL)")
    db.execSQL("INSERT INTO machines_new (id,machineCode,name,machineType,location,status,notes,createdAt,updatedAt,lineId) SELECT id,machineCode,name,machineType,location,status,notes,createdAt,updatedAt,NULL FROM machines")
    db.execSQL("DROP TABLE machines")
    db.execSQL("ALTER TABLE machines_new RENAME TO machines")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_machines_machineCode ON machines(machineCode)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_machines_lineId ON machines(lineId)")
   }
  }
  internal val MIGRATION_6_7=object:Migration(6,7){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("ALTER TABLE production_lines ADD COLUMN targetCapacity INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE production_lines ADD COLUMN capacityUnit TEXT NOT NULL DEFAULT 'BOTTLES_PER_HOUR'")
    db.execSQL("ALTER TABLE production_entries ADD COLUMN lineId INTEGER")
    db.execSQL("ALTER TABLE production_entries ADD COLUMN lineNameSnapshot TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE production_entries ADD COLUMN machineId INTEGER")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_production_entries_lineId ON production_entries(lineId)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_production_entries_machineId ON production_entries(machineId)")
    db.execSQL("ALTER TABLE production_plans ADD COLUMN lineId INTEGER")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_production_plans_lineId ON production_plans(lineId)")
   }
  }
  internal val MIGRATION_7_8=object:Migration(7,8){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("ALTER TABLE products ADD COLUMN iconName TEXT NOT NULL DEFAULT 'bottle_default'")
    db.execSQL("ALTER TABLE products ADD COLUMN imageUri TEXT NOT NULL DEFAULT ''")
   }
  }
  internal val MIGRATION_8_9=object:Migration(8,9){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("CREATE TABLE IF NOT EXISTS staff_members (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,uid TEXT NOT NULL,name TEXT NOT NULL,email TEXT NOT NULL,role TEXT NOT NULL,allowedModules TEXT NOT NULL DEFAULT 'ALL',active INTEGER NOT NULL DEFAULT 1,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_staff_members_email ON staff_members(email)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_staff_members_role ON staff_members(role)")
   }
  }
  internal val MIGRATION_9_10=object:Migration(9,10){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("ALTER TABLE products ADD COLUMN productCode TEXT NOT NULL DEFAULT ''")
    db.query("SELECT id,name,sizeLabel,colour FROM products").use { cursor ->
     val update=db.compileStatement("UPDATE products SET productCode=? WHERE id=?")
     while(cursor.moveToNext()){
      val name=cursor.getString(1) ?: ""
      val size=cursor.getString(2) ?: ""
      val colour=cursor.getString(3) ?: "Clear"
      val canonical=listOf(name.trim().lowercase(),size.trim().lowercase(),colour.trim().lowercase()).joinToString("|")
      val digest=java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
      update.clearBindings(); update.bindString(1,"p-"+digest.take(32)); update.bindLong(2,cursor.getLong(0)); update.executeUpdateDelete()
     }
    }
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_productCode ON products(productCode)")
   }
  }
  internal val MIGRATION_10_11=object:Migration(10,11){
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){
    db.execSQL("ALTER TABLE production_entries ADD COLUMN cloudOperationKey TEXT NOT NULL DEFAULT ''")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_production_entries_cloudOperationKey ON production_entries(cloudOperationKey)")
    db.execSQL("ALTER TABLE packing_entries ADD COLUMN cloudOperationKey TEXT NOT NULL DEFAULT ''")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_packing_entries_cloudOperationKey ON packing_entries(cloudOperationKey)")
   }
  }
  fun closeForRestore(){ synchronized(this){instance?.close();instance=null} }
  fun get(context:Context):FactoryDatabase=instance?: synchronized(this){instance?:Room.databaseBuilder(context.applicationContext,FactoryDatabase::class.java,"factoryflow.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9,MIGRATION_9_10,MIGRATION_10_11).build().also{instance=it}}
 }
}
