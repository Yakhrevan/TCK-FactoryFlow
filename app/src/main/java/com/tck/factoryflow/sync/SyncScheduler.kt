package com.tck.factoryflow.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncScheduler {
 fun schedule(context:Context) {
  val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
  val request=PeriodicWorkRequestBuilder<SyncOutboxWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
  WorkManager.getInstance(context).enqueueUniquePeriodicWork("factoryflow-outbox-sync",ExistingPeriodicWorkPolicy.KEEP,request)
 }
}
