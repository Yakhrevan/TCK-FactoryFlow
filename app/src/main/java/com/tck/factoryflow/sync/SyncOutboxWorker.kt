package com.tck.factoryflow.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.tck.factoryflow.cloud.CloudResult
import com.tck.factoryflow.cloud.CloudflareApiClient
import com.tck.factoryflow.data.FactoryDatabase
import org.json.JSONObject
import kotlinx.coroutines.tasks.await

class SyncOutboxWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
 override suspend fun doWork():Result {
  if(FirebaseApp.getApps(applicationContext).isEmpty()) return Result.retry()
  val user=runCatching{FirebaseAuth.getInstance().currentUser}.getOrNull()?:return Result.retry()
  var token=runCatching{user.getIdToken(false).await()}.getOrElse{return Result.retry()}
  var claimOrg = token.claims["organisationId"] as? String
  if (claimOrg.isNullOrBlank()) {
      token = runCatching{user.getIdToken(true).await()}.getOrElse{return Result.retry()}
      claimOrg = token.claims["organisationId"] as? String
  }
  val dao=FactoryDatabase.get(applicationContext).factoryDao()
  // Always verify active cloud membership before syncing any queued data.
  // Never infer or fall back to a default organisation.
  val memberResult = CloudflareApiClient.post(
   applicationContext,
   "/api/auth/member",
   JSONObject().apply {
       if (!claimOrg.isNullOrBlank()) {
           put("organisationId", claimOrg)
       }
   }
  )

  val member = when(memberResult) {
   is CloudResult.Success -> memberResult.data.optJSONObject("member")
   is CloudResult.Error -> return Result.retry()
  } ?: return Result.retry()

  if (!member.optBoolean("active", false)) return Result.retry()

  val organisationId = member.optString("organisationId")
   .takeIf { it.isNotBlank() } ?: return Result.retry()

  if (claimOrg != null && claimOrg != organisationId) return Result.retry()
  var failed=false
  for(event in runCatching{dao.pendingSyncEvents(50)}.getOrElse{return Result.retry()}) {
   val targetOrg=if(event.organisationId=="local-org"||event.organisationId.isBlank()) organisationId else event.organisationId
   // Never silently relabel a queued event from another organisation as belonging to the signed-in user.
   if(targetOrg.isBlank()||targetOrg!=organisationId) {
    dao.markSyncNeedsReview(event.id,"Organisation scope mismatch: event belongs to '$targetOrg' but the signed-in account belongs to '$organisationId'. Sign in with the correct factory account or resolve this event with an administrator.")
    continue
   }
   try {
    val syncResult=CloudflareApiClient.post(
     applicationContext,
     "/api/sync/event",
     JSONObject()
      .put("organisationId",targetOrg)
      .put("eventKey",event.eventKey)
      .put("eventType",event.eventType)
      .put("payload",event.payloadJson)
    )
    when(syncResult) {
     is CloudResult.Success -> {
      dao.markSyncSynced(event.id,System.currentTimeMillis())
     }
     is CloudResult.Error -> {
      val reason=syncResult.message.take(500)
      if(reason.contains("Idempotency conflict",ignoreCase=true) ||
         reason.contains("HTTP 409",ignoreCase=true)) {
       dao.markSyncNeedsReview(event.id,reason)
      } else if(event.attempts>=7) {
       dao.markSyncNeedsReview(
        event.id,
        "Automatic retry limit reached after "+(event.attempts+1)+
        " attempts. Last error: "+reason
       )
      } else {
       dao.markSyncAttempt(event.id,reason)
       failed=true
      }
     }
    }
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
