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
  val dao=FactoryDatabase.get(applicationContext).factoryDao()
  var organisationId=token.claims["organisationId"] as? String
  if(organisationId.isNullOrBlank()) {
   val memberDoc=runCatching{
    FirebaseFirestore.getInstance().collection("organisations").document("tck-factory-01").collection("members").document(user.uid).get().await()
   }.getOrNull()
   organisationId=memberDoc?.getString("organisationId")?:"tck-factory-01"
  }
  var failed=false
  for(event in runCatching{dao.pendingSyncEvents(50)}.getOrElse{return Result.retry()}) {
   val targetOrg=if(event.organisationId=="local-org"||event.organisationId.isBlank()) organisationId else event.organisationId
   // Never silently relabel a queued event from another organisation as belonging to the signed-in user.
   if(targetOrg.isBlank()||targetOrg!=organisationId) {
    dao.markSyncNeedsReview(event.id,"Organisation scope mismatch: event belongs to '$targetOrg' but the signed-in account belongs to '$organisationId'. Sign in with the correct factory account or resolve this event with an administrator.")
    continue
   }
   try {
    val ref=FirebaseFirestore.getInstance().collection("organisations").document(targetOrg).collection("events").document(event.eventKey)
    FirebaseFirestore.getInstance().runTransaction { transaction ->
     val existing=transaction.get(ref)
     if(!existing.exists()) {
      transaction.set(ref,mapOf("eventKey" to event.eventKey,"eventType" to event.eventType,"payload" to event.payloadJson,"actorUid" to user.uid,"organisationId" to targetOrg,"createdAt" to FieldValue.serverTimestamp()))
     } else {
      val existingType=existing.getString("eventType")
      val existingPayload=existing.getString("payload")
      require(existingType==event.eventType&&existingPayload==event.payloadJson&&existing.getString("organisationId")==targetOrg){"Idempotency conflict: a different event already uses this key or organisation."}
     }
     null
    }.await()
    dao.markSyncSynced(event.id,System.currentTimeMillis())
   } catch(e:Exception) {
    val reason=e.message?.take(500)?:e.javaClass.simpleName
    if(reason.contains("Idempotency conflict",ignoreCase=true)) dao.markSyncNeedsReview(event.id,reason)
    else if(event.attempts>=7) dao.markSyncNeedsReview(event.id,"Automatic retry limit reached after "+(event.attempts+1)+" attempts. Last error: "+reason)
    else { dao.markSyncAttempt(event.id,reason); failed=true }
   }
  }
  return if(failed) Result.retry() else Result.success()
 }
}
