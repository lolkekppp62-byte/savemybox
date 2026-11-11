package com.savemy.singboxfork.work

import android.content.Context
import androidx.work.*
import com.savemy.singboxfork.BoxApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context,params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as BoxApp; app.ready.await()
        if(app.repository.loadError.value!=null) return Result.failure()
        return try { app.engine.checkAll(); Result.success() } catch(e:CancellationException) { throw e } catch(_:Exception) { Result.retry() }
    }
    companion object {
        fun schedule(context:Context,minutes:Long) {
            require(minutes in 15..43200)
            val work=PeriodicWorkRequestBuilder<RefreshWorker>(minutes,TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("node-refresh",ExistingPeriodicWorkPolicy.UPDATE,work)
        }
    }
}
