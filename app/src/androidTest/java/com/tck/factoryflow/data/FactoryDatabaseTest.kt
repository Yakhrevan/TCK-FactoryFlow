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
   val batch=DispatchBatchEntity(idempotencyKey="same-key",dispatchReference="DSP-1",dispatchDate="2026-10-24",customerNameSnapshot="Nachiyar Cooldrinks")
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
   val batch=DispatchBatchEntity(idempotencyKey="too-many",dispatchReference="DSP-2",dispatchDate="2026-10-24",customerNameSnapshot="Customer")
   assertThrows(IllegalStateException::class.java){ kotlinx.coroutines.runBlocking { dao.postMultiLineDispatch(batch,listOf(DispatchRequestLine(product,1)),"org-test","tester") } }
  }
 }
 @Test fun concurrentDispatchesCannotOversellLastPack() {
  kotlinx.coroutines.runBlocking {
   val product=dao.insertProduct(ProductEntity(name="Concurrent 500ml",sizeLabel="500ml",bottlesPerPack=100))
   dao.insertPacking(PackingEntryEntity(productId=product,productNameSnapshot="Concurrent 500ml",packingDate="2026-10-24",bottlesConsumed=100,completePacks=1,looseBottles=0))
   val requests=(1..2).map { index ->
    kotlinx.coroutines.async(kotlinx.coroutines.Dispatchers.IO) {
     runCatching {
      dao.postMultiLineDispatch(
       DispatchBatchEntity(idempotencyKey="concurrent-$index",dispatchReference="DSP-$index",dispatchDate="2026-10-24",customerNameSnapshot="Customer"),
       listOf(DispatchRequestLine(product,1)),"org-test","tester"
      )
     }
    }
   }
   val results=requests.map { it.await() }
   assertEquals("Exactly one concurrent dispatch should succeed",1,results.count{it.isSuccess})
   assertEquals("Exactly one concurrent dispatch should be rejected",1,results.count{it.isFailure})
   assertEquals(1,dao.totalDispatchedPacks(product))
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

}
