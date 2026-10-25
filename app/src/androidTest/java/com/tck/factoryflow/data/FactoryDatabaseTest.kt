package com.tck.factoryflow.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async

@RunWith(AndroidJUnit4::class)
class FactoryDatabaseTest {
 private lateinit var db:FactoryDatabase
 private lateinit var dao:FactoryDao
 @Before fun setUp(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),FactoryDatabase::class.java).allowMainThreadQueries().build();dao=db.factoryDao()}
 @After fun tearDown(){db.close()}
 @Test fun productPackSizePersistsAndHistoryCapturesOldValue() {
  kotlinx.coroutines.runBlocking {
   val id=dao.insertProduct(ProductEntity(name="300ml",sizeLabel="300ml",bottlesPerPack=300))
   dao.updateProductWithHistory(id,"300ml","300ml","Clear",250,true,"tester","pack size changed","org-test")
   assertEquals(250,dao.productById(id)!!.bottlesPerPack)
   // The history stream is validated through the persisted query on an in-memory database in a coroutine.
   val history=dao.observeProductHistory(id)
   val savedHistory=history.first()
   assertEquals(300,savedHistory.first().bottlesPerPack)
  }
 }
 @Test fun duplicateDispatchIdempotencyKeyPostsOnce() {
  kotlinx.coroutines.runBlocking {
   val product=dao.insertProduct(ProductEntity(name="500ml",sizeLabel="500ml",bottlesPerPack=100))
   dao.insertPacking(PackingEntryEntity(productId=product,productNameSnapshot="500ml",packingDate="2026-10-24",bottlesConsumed=300,completePacks=3,looseBottles=0))
   val batch=DispatchBatchEntity(idempotencyKey="same-key",dispatchReference="DSP-1",dispatchDate="2026-10-24",customerId=null,customerNameSnapshot="Nachiyar Cooldrinks")
   val lines=listOf(DispatchRequestLine(product,1))
   val first=dao.postMultiLineDispatch(batch,lines,"org-test","tester")
   val second=dao.postMultiLineDispatch(batch,lines,"org-test","tester")
   assertEquals(first.batchId,second.batchId)
   assertEquals(1,dao.linesForBatch(first.batchId).size)
  }
 }
 @Test fun insufficientFinishedStockRejected() {
  kotlinx.coroutines.runBlocking {
   val product=dao.insertProduct(ProductEntity(name="1L",sizeLabel="1L",bottlesPerPack=100))
   val batch=DispatchBatchEntity(idempotencyKey="too-many",dispatchReference="DSP-2",dispatchDate="2026-10-24",customerId=null,customerNameSnapshot="Customer")
   assertThrows(IllegalStateException::class.java){ kotlinx.coroutines.runBlocking { dao.postMultiLineDispatch(batch,listOf(DispatchRequestLine(product,1)),"org-test","tester") } }
  }
 }
 @Test fun concurrentDispatchesCannotOversellLastPack() {
  kotlinx.coroutines.runBlocking {
   val product=dao.insertProduct(ProductEntity(name="Concurrent 500ml",sizeLabel="500ml",bottlesPerPack=100))
   dao.insertPacking(PackingEntryEntity(productId=product,productNameSnapshot="Concurrent 500ml",packingDate="2026-10-24",bottlesConsumed=100,completePacks=1,looseBottles=0))
   val requests=(1..2).map { index ->
    async(kotlinx.coroutines.Dispatchers.IO) {
     runCatching {
      dao.postMultiLineDispatch(
       DispatchBatchEntity(idempotencyKey="concurrent-$index",dispatchReference="DSP-$index",dispatchDate="2026-10-24",customerId=null,customerNameSnapshot="Customer"),
       listOf(DispatchRequestLine(product,1)),"org-test","tester"
      )
     }
    }
   }
   val results=requests.map { it.await() }
   assertEquals("Exactly one concurrent dispatch should succeed",1,results.count{it.isSuccess})
   assertEquals("Exactly one concurrent dispatch should be rejected",1,results.count{it.isFailure})
   assertEquals(1,dao.batchedDispatchedPacks(product))
  }
 }
 @Test fun packingRejectsUnconfiguredPackSizeAndMismatchedCalculation() {
  kotlinx.coroutines.runBlocking {
   val product=dao.insertProduct(ProductEntity(name="Unconfigured",sizeLabel="Bottle",bottlesPerPack=0))
   dao.insertProduction(ProductionEntryEntity(productId=product,productNameSnapshot="Unconfigured",productionDate="2026-10-24",producedBottles=10,goodBottles=10,rejectedBottles=0))
   assertThrows(IllegalArgumentException::class.java) {
    kotlinx.coroutines.runBlocking {
     dao.postPacking(PackingEntryEntity(productId=product,productNameSnapshot="Unconfigured",packingDate="2026-10-24",bottlesConsumed=10,completePacks=0,looseBottles=10),"org-test","tester")
    }
   }
  }
 }

 @Test fun packingReversalRestoresBottleAvailabilityAndIsIdempotent() {
  kotlinx.coroutines.runBlocking {
   val product=dao.insertProduct(ProductEntity(name="Reverse packing test",sizeLabel="Bottle",bottlesPerPack=10))
   dao.insertProduction(ProductionEntryEntity(productId=product,productNameSnapshot="Reverse packing test",productionDate="2026-10-24",producedBottles=20,goodBottles=20,rejectedBottles=0))
   val packingId=dao.postPacking(PackingEntryEntity(productId=product,productNameSnapshot="Reverse packing test",packingDate="2026-10-24",bottlesConsumed=10,completePacks=1,looseBottles=0),"org-test","tester")
   assertEquals(10,dao.totalPackedBottles(product))
   assertTrue(dao.reversePacking(packingId,"test correction","tester","org-test"))
   assertFalse(dao.reversePacking(packingId,"duplicate correction","tester","org-test"))
   assertEquals(0,dao.totalPackedBottles(product))
   assertEquals(0,dao.totalCompletePacks(product))
   assertEquals(20,dao.totalGoodBottles(product))
  }
 }

 @Test fun migrationsThreeToFiveAddOrderLinkAndMaintenanceCompletionColumns() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val name="factoryflow-migration-test.db"
  val helper=androidx.sqlite.framework.FrameworkSQLiteOpenHelperFactory().create(
   androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
    object:androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3){
     override fun onCreate(db:androidx.sqlite.db.SupportSQLiteDatabase){
      db.execSQL("CREATE TABLE dispatch_batches (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,dispatchDate TEXT NOT NULL)")
      db.execSQL("CREATE TABLE machine_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,machineId INTEGER NOT NULL,machineNameSnapshot TEXT NOT NULL,eventType TEXT NOT NULL,startedAt INTEGER NOT NULL,endedAt INTEGER,reason TEXT NOT NULL,maintenanceType TEXT NOT NULL,status TEXT NOT NULL,performedBy TEXT NOT NULL,createdAt INTEGER NOT NULL)")
     }
     override fun onUpgrade(db:androidx.sqlite.db.SupportSQLiteDatabase,oldVersion:Int,newVersion:Int){}
    }
   ).build()
  )
  try{
   val database=helper.writableDatabase
   FactoryDatabase.MIGRATION_3_4.migrate(database)
   FactoryDatabase.MIGRATION_4_5.migrate(database)
   val dispatchColumns=mutableSetOf<String>()
   database.query("PRAGMA table_info(dispatch_batches)").use{while(it.moveToNext())dispatchColumns.add(it.getString(1))}
   val machineColumns=mutableSetOf<String>()
   database.query("PRAGMA table_info(machine_events)").use{while(it.moveToNext())machineColumns.add(it.getString(1))}
   assertTrue(dispatchColumns.contains("salesOrderId"))
   assertTrue(machineColumns.containsAll(setOf("completionNotes","partsUsed","completedBy")))
  }finally{helper.close();context.deleteDatabase(name)}
 }
 @Test fun explicitOrderDispatchLinksAndReconcilesFulfilment() {
  kotlinx.coroutines.runBlocking {
   val product=dao.insertProduct(ProductEntity(name="Order 500ml",sizeLabel="500ml",bottlesPerPack=10))
   val customer=dao.insertCustomer(CustomerEntity(name="Order Customer",phone="",address="",gstNumber=""))
   dao.insertProduction(ProductionEntryEntity(productId=product,productNameSnapshot="Order 500ml",productionDate="2026-10-25",producedBottles=50,goodBottles=50,rejectedBottles=0))
   dao.postPacking(PackingEntryEntity(productId=product,productNameSnapshot="Order 500ml",packingDate="2026-10-25",bottlesConsumed=50,completePacks=5,looseBottles=0),"org-test","tester")
   val orderId=dao.insertSalesOrder(SalesOrderEntity(orderNumber="SO-TEST-1",customerId=customer,customerNameSnapshot="Order Customer",orderDate="2026-10-25",dueDate="2026-10-28"))
   val lineId=dao.insertSalesOrderLines(listOf(SalesOrderLineEntity(orderId=orderId,productId=product,productNameSnapshot="Order 500ml",packsOrdered=3,packSizeSnapshot=10))).let{dao.salesOrderLinesForOrder(orderId).single().id}
   val result=dao.postSalesOrderDispatch(DispatchBatchEntity(idempotencyKey="order-dispatch-1",dispatchReference="DSP-ORDER-1",dispatchDate="2026-10-25",customerId=customer,customerNameSnapshot="Order Customer",salesOrderId=orderId),listOf(DispatchRequestLine(product,2)),"org-test","tester")
   assertEquals(orderId,dao.dispatchBatchById(result.batchId)!!.salesOrderId)
   assertEquals(2,dao.salesOrderLinesForOrder(orderId).single{it.id==lineId}.packsDispatched)
   assertEquals("PARTIAL",dao.salesOrderById(orderId)!!.status)
   assertThrows(IllegalArgumentException::class.java) {
    kotlinx.coroutines.runBlocking {
     dao.postSalesOrderDispatch(DispatchBatchEntity(idempotencyKey="order-dispatch-over",dispatchReference="DSP-ORDER-2",dispatchDate="2026-10-25",customerId=customer,customerNameSnapshot="Order Customer",salesOrderId=orderId),listOf(DispatchRequestLine(product,2)),"org-test","tester")
    }
   }
  }
 }
 @Test fun machineEventCloseStoresCompletionAndParts() {
  kotlinx.coroutines.runBlocking {
   val machineId=dao.insertMachine(MachineEntity(machineCode="FILL-01",name="Filling Machine",machineType="Filling"))
   val eventId=dao.insertMachineEvent(MachineEventEntity(machineId=machineId,machineNameSnapshot="Filling Machine",eventType="MAINTENANCE",startedAt=System.currentTimeMillis()-3600000,reason="Routine maintenance",maintenanceType="Preventive maintenance",performedBy="tester"))
   assertEquals(1,dao.closeMachineEvent(eventId,completionNotes="Seal replaced and tested",partsUsed="Silicone seal",completedBy="tester"))
   val saved=dao.observeMachineEvents().first().single()
   assertEquals("CLOSED",saved.status)
   assertEquals("Seal replaced and tested",saved.completionNotes)
   assertEquals("Silicone seal",saved.partsUsed)
   assertEquals("tester",saved.completedBy)
   assertNotNull(saved.endedAt)
  }
 }
 @Test fun qualityInspectionLinksToProductionBatchWithoutChangingProductionRejects() {
  kotlinx.coroutines.runBlocking {
   val product=dao.insertProduct(ProductEntity(name="Quality 300ml",sizeLabel="300ml",bottlesPerPack=20))
   val productionId=dao.insertProduction(ProductionEntryEntity(productId=product,productNameSnapshot="Quality 300ml",productionDate="2026-10-25",producedBottles=100,goodBottles=95,rejectedBottles=5))
   dao.insertQualityInspection(QualityInspectionEntity(productionEntryId=productionId,productId=product,productNameSnapshot="Quality 300ml",inspectionDate="2026-10-25",inspectedBottles=10,passedBottles=8,failedBottles=2,defectCategory="Leakage",severity="MAJOR",inspectorId="tester"))
   val inspection=dao.observeQualityInspections().first().single()
   assertEquals(productionId,inspection.productionEntryId)
   assertEquals(2,inspection.failedBottles)
   assertEquals(5,dao.totalRejectedBottles(product))
  }
 }

}
