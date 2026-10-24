package com.tck.factoryflow.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.tck.factoryflow.data.FactoryDatabase
import kotlinx.coroutines.tasks.await

class SyncOutboxWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
 override suspend fun doWork():Result {
  if(FirebaseApp.getApps(applicationContext).isEmpty()) return Result.retry()
  val user=runCatching{FirebaseAuth.getInstance().currentUser}.getOrNull()?:return Result.retry()
  val token=runCatching{user.getIdToken(false).await()}.getOrElse{return Result.retry()}
  val organisationId=token.claims["organisationId"] as? String ?:return Result.failure()
  val dao=FactoryDatabase.get(applicationContext).factoryDao()
  var failed=false
  for(event in runCatching{dao.pendingSyncEvents(50)}.getOrElse{return Result.retry()}) {
   if(event.organisationId!=organisationId) {
    dao.markSyncAttempt(event.id,"Signed-in account does not belong to the queued organisation.")
    failed=true
    continue
   }
   try {
    val ref=FirebaseFirestore.getInstance().collection("organisations").document(organisationId).collection("events").document(event.eventKey)
    FirebaseFirestore.getInstance().runTransaction { transaction ->
     val existing=transaction.get(ref)
     if(!existing.exists()) transaction.set(ref,mapOf("eventKey" to event.eventKey,"eventType" to event.eventType,"payload" to event.payloadJson,"actorUid" to user.uid,"organisationId" to organisationId,"createdAt" to FieldValue.serverTimestamp()))
     null
    }.await()
    dao.markSyncSynced(event.id,System.currentTimeMillis())
   } catch(e:Exception) {
    dao.markSyncAttempt(event.id,e.message?.take(500)?:e.javaClass.simpleName)
    failed=true
   }
  }
  return if(failed) Result.retry() else Result.success()
 }
}
