package com.savemy.singboxfork.work

import android.content.Context
import androidx.work.*
import com.savemy.singboxfork.BoxApp
import com.savemy.singboxfork.data.Importer
import com.savemy.singboxfork.data.Mark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

class SubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BoxApp
        app.ready.await()
        if (app.repository.loadError.value != null) return Result.failure()
        val settings = app.repository.state.value.settings
        return try {
            settings.subscriptions.forEach { sub ->
                val raw = withContext(Dispatchers.IO) {
                    URL(sub.url).openConnection().apply {
                        connectTimeout = 15_000; readTimeout = 30_000
                        setRequestProperty("User-Agent", "SavemyBox/1.0")
                    }.getInputStream().readBytes().decodeToString()
                }
                // Пробуем Base64 (стандартные подписки), иначе берём как есть
                val text = runCatching {
                    String(Base64.getDecoder().decode(raw.trim()))
                }.getOrElse {
                    runCatching { String(Base64.getMimeDecoder().decode(raw.trim())) }.getOrElse { raw }
                }
                val nodes = Importer.parse(text, Mark.NONE)
                if (nodes.isNotEmpty()) app.repository.addFromSubscription(sub.id, nodes)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context, minutes: Long) {
            val mins = minutes.coerceAtLeast(15)
            val work = PeriodicWorkRequestBuilder<SubscriptionWorker>(mins, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sub-refresh", ExistingPeriodicWorkPolicy.UPDATE, work)
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("sub-refresh")
        }
    }
}
