package com.savemy.singboxfork.work

import android.content.Context
import androidx.work.*
import com.savemy.singboxfork.BoxApp
import com.savemy.singboxfork.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Фоновая загрузка подписок. Расписание у каждой подписки своё:
 * выкл / при запуске / каждые 6, 12, 24 часа.
 */
class SubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BoxApp
        app.ready.await()
        if (app.repository.loadError.value != null) return Result.failure()
        val settings = app.repository.state.value.settings
        val target = inputData.getString(KEY_SUB)
        val subs = settings.subscriptions.filter { it.enabled && (target == null || it.id == target) }
            .filter { it.sources().isNotEmpty() }
        if (subs.isEmpty()) return Result.success()
        return try {
            subs.forEach { sub -> refresh(app, sub) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun refresh(app: BoxApp, sub: Subscription) {
        val urls = sub.sources()
        var fetched = 0
        val merged = ArrayList<Node>()
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val raw = withContext(Dispatchers.IO) {
                    URL(url).openConnection().apply {
                        connectTimeout = 15_000; readTimeout = 30_000
                        setRequestProperty("User-Agent", "SavemyBox/1.0")
                    }.getInputStream().use { stream ->
                        SubscriptionManager.readList(stream)
                    }
                }
                // Base64, .txt-список, JSON и набор URI-ссылок разбирает один импортёр.
                merged += SubscriptionManager.parse(raw, Mark.NONE)
                fetched++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (fetched == 0 && urls.isNotEmpty()) throw (lastError ?: IllegalStateException("Не удалось обновить бокс"))
        if (merged.isNotEmpty()) app.repository.addFromSubscription(sub.id, merged)
        app.repository.updateSettings { s ->
            s.copy(subscriptions = s.subscriptions.map { if (it.id == sub.id) it.copy(lastUpdated = System.currentTimeMillis()) else it })
        }
    }

    companion object {
        const val KEY_SUB = "subId"
        private const val TAG = "subscription-refresh"
        private fun workName(id: String) = "sub-refresh-$id"

        /** Расписание подписки; выключенная или «выкл» снимает периодическую задачу. */
        fun schedule(context: Context, sub: Subscription) {
            val manager = WorkManager.getInstance(context)
            val minutes = SubscriptionManager.minutes(sub.refresh)
            if (!sub.enabled || minutes == null || sub.sources().isEmpty()) {
                manager.cancelUniqueWork(workName(sub.id))
                return
            }
            val work = PeriodicWorkRequestBuilder<SubscriptionWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_SUB to sub.id))
                .addTag(TAG)
                .build()
            manager.enqueueUniquePeriodicWork(workName(sub.id), ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        fun sync(context: Context, subs: List<Subscription>) = subs.forEach { schedule(context, it) }

        /** «Обновить все»: каждый бокс скачивается своей задачей, параллельно. */
        fun refreshAllNow(context: Context, subs: List<Subscription>) {
            subs.filter { it.enabled && it.sources().isNotEmpty() }.forEach { refreshNow(context, it.id) }
        }

        /** Боксы с обновлением «при запуске» дёргаются один раз после старта приложения. */
        fun refreshOnStart(context: Context, subs: List<Subscription>) {
            subs.filter { it.enabled && it.refresh == SubscriptionRefresh.ON_START && it.sources().isNotEmpty() }
                .forEach { refreshNow(context, it.id) }
        }

        /** Немедленное обновление: разовая задача вместо ожидания расписания. */
        fun refreshNow(context: Context, subId: String? = null) {
            val request = OneTimeWorkRequestBuilder<SubscriptionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .apply { subId?.let { setInputData(workDataOf(KEY_SUB to it)) } }
                .addTag(TAG)
                .build()
            val name = subId?.let(::workName)?.plus("-now") ?: "sub-refresh-now"
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }

        /** Подписка удалена — снимаем её расписание и возможное ручное обновление. */
        fun cancel(context: Context, id: String) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(workName(id))
            manager.cancelUniqueWork(workName(id) + "-now")
        }

        /** Подписок нет — снимаем периодические задачи и хвосты ручных обновлений. */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }
    }
}
