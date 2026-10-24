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
  require(name.isNotBlank()&&size.isNotBlank());require(pack>0)
  val old=productById(id)?:error("Product not found")
  insertProductHistory(ProductConfigHistoryEntity(productId=old.id,productName=old.name,sizeLabel=old.sizeLabel,colour=old.colour,bottlesPerPack=old.bottlesPerPack,active=old.active,changedBy=actor,reason=reason))
  updateProduct(id,name.trim(),size.trim(),colour.trim(),pack,active)
  val after=productJson(name,size,colour,pack,active)
  insertAudit(AuditEventEntity(eventType="PRODUCT_UPDATED",entityType="product",entityId=id.toString(),beforeJson=productJson(old.name,old.sizeLabel,old.colour,old.bottlesPerPack,old.active),afterJson=after,reason=reason,actorId=actor))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="product-update:"+organisationId+":"+id+":"+System.currentTimeMillis(),organisationId=organisationId,eventType="PRODUCT_UPDATED",payloadJson=after))
 }
 @Transaction open suspend fun correctProduction(id:Long,reason:String,actorId:String,organisationId:String):Boolean {
  require(reason.isNotBlank()){"A correction reason is required."}
  val entry=productionById(id)?:error("Production record not found")
  val product=productById(entry.productId)?:error("Product not found")
  val alreadyPacked=totalPackedBottles(product.id)
  require(alreadyPacked<=entry.goodBottles){"Cannot reverse this production record because its bottles have already been packed; use a controlled stock adjustment."}
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
 @Query("SELECT COALESCE(SUM(goodBottles),0) FROM production_entries WHERE productId=:id") abstract suspend fun totalGoodBottles(id:Long):Int
 @Insert abstract suspend fun insertProduction(e:ProductionEntryEntity):Long
 @Query("SELECT * FROM packing_entries ORDER BY packingDate DESC,createdAt DESC") abstract fun observePacking():Flow<List<PackingEntryEntity>>
 @Query("SELECT COALESCE(SUM(bottlesConsumed),0) FROM packing_entries WHERE productId=:id") abstract suspend fun totalPackedBottles(id:Long):Int
 @Query("SELECT COALESCE(SUM(completePacks),0) FROM packing_entries WHERE productId=:id") abstract suspend fun totalCompletePacks(id:Long):Int
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
 @Query("SELECT * FROM packing_entries WHERE id=:id LIMIT 1") abstract suspend fun packingById(id:Long):PackingEntryEntity?
 @Query("SELECT * FROM meter_readings WHERE id=:id LIMIT 1") abstract suspend fun meterById(id:Long):MeterReadingEntity?
 @Query("UPDATE production_entries SET producedBottles=producedBottles WHERE id=:id") abstract suspend fun touchProduction(id:Long)
 @Query("UPDATE packing_entries SET bottlesConsumed=bottlesConsumed WHERE id=:id") abstract suspend fun touchPacking(id:Long)
 @Query("UPDATE meter_readings SET remarks=remarks WHERE id=:id") abstract suspend fun touchMeter(id:Long)
 @Query("SELECT * FROM dispatch_batches WHERE id=:id LIMIT 1") abstract suspend fun dispatchBatchById(id:Long):DispatchBatchEntity?
 @Query("SELECT * FROM inventory_movements WHERE productId=:id ORDER BY createdAt DESC") abstract fun observeInventoryMovements(id:Long):Flow<List<InventoryMovementEntity>>
 @Query("SELECT * FROM audit_events ORDER BY createdAt DESC LIMIT :limit") abstract fun observeAuditEvents(limit:Int=200):Flow<List<AuditEventEntity>>
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
   val available=totalCompletePacks(product.id)-legacyDispatchedPacks(product.id)-batchedDispatchedPacks(product.id)
   require(line.packs<=available){"Insufficient stock for "+product.name+": "+available+" packs available"}
   Triple(product,line.packs,available)
  }
  val id=insertDispatchBatch(batch)
  if(id<0){val existing=batchByIdempotencyKey(batch.idempotencyKey)?:error("Duplicate dispatch key could not be resolved");return DispatchPostResult(existing.id,existing.dispatchReference,linesForBatch(existing.id).size)}
  val rows=resolved.map{r->DispatchLineEntity(batchId=id,productId=r.first.id,productNameSnapshot=r.first.name,packSizeSnapshot=r.first.bottlesPerPack,packs=r.second,bottles=r.second*r.first.bottlesPerPack)}
  insertDispatchLines(rows)
  rows.forEach{line->insertInventoryMovement(InventoryMovementEntity(productId=line.productId,productNameSnapshot=line.productNameSnapshot,movementType="DISPATCH_OUT",quantity=-line.packs,unit="PACK",sourceType="dispatch",sourceId=id.toString(),note=batch.dispatchReference,actorId=actorId))}
  val payload=JSONArray();rows.forEach{line->payload.put(JSONObject().put("productId",line.productId).put("productName",line.productNameSnapshot).put("packs",line.packs).put("bottles",line.bottles))}
  insertAudit(AuditEventEntity(eventType="DISPATCH_POSTED",entityType="dispatch",entityId=id.toString(),afterJson=payload.toString(),reason="Dispatch posted",actorId=actorId))
  enqueueSyncEvent(SyncOutboxEntity(eventKey="dispatch:"+organisationId+":"+batch.idempotencyKey,organisationId=organisationId,eventType="DISPATCH_POSTED",payloadJson=payload.toString()))
  return DispatchPostResult(id,batch.dispatchReference,rows.size)
 }
 @Transaction open suspend fun postPacking(entry:PackingEntryEntity,organisationId:String,actorId:String):Long {
  require(entry.bottlesConsumed>0){"Packing quantity must be positive"}
  val product=productById(entry.productId)?:error("Product not found");require(product.active){"Product is inactive"}
  require(entry.completePacks>=0&&entry.looseBottles>=0){"Invalid pack calculation"}
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

@Database(entities=[ProductEntity::class,ProductConfigHistoryEntity::class,ProductionEntryEntity::class,PackingEntryEntity::class,DispatchEntryEntity::class,DispatchBatchEntity::class,DispatchLineEntity::class,InventoryMovementEntity::class,AuditEventEntity::class,SyncOutboxEntity::class,CustomerEntity::class,MeterReadingEntity::class],version=2,exportSchema=false)
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
  fun get(context:Context):FactoryDatabase=instance?: synchronized(this){instance?:Room.databaseBuilder(context.applicationContext,FactoryDatabase::class.java,"factoryflow.db").addMigrations(MIGRATION_1_2).build().also{instance=it}}
 }
}
