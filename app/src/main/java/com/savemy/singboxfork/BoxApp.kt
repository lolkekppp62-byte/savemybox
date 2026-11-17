package com.savemy.singboxfork

import android.app.Application
import com.savemy.singboxfork.core.*
import com.savemy.singboxfork.data.Repository
import com.savemy.singboxfork.work.RefreshWorker
import com.savemy.singboxfork.work.SubscriptionWorker
import kotlinx.coroutines.*

class BoxApp : Application() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    lateinit var repository: Repository; private set
    lateinit var journal: Journal; private set
    lateinit var engine: Engine; private set
    lateinit var updater: Updater; private set
    val ready=CompletableDeferred<Unit>()
    override fun onCreate() {
        super.onCreate(); repository=Repository(this); journal=Journal(this); engine=Engine(this,repository,journal)
        updater=Updater(this,BuildConfig.VERSION_NAME)
        scope.launch {
            repository.load(); ready.complete(Unit)
            if(repository.loadError.value==null) {
                // Подписки по умолчанию не подставляются: публичные пулы умирают, и человек
                // получает список мёртвых узлов. Остатки прежнего каталога удаляются.
                val dead=repository.state.value.settings.subscriptions.filter { it.source=="catalog" }
                if(dead.isNotEmpty()) {
                    repository.updateSettings { s -> s.copy(subscriptions=s.subscriptions.filterNot { it.source=="catalog" }) }
                    dead.forEach { SubscriptionWorker.cancel(this@BoxApp,it.id) }
                    journal.add("Удалены нерабочие подписки каталога: ${dead.size}")
                }
                val s=repository.state.value.settings
                RefreshWorker.schedule(this@BoxApp,s.intervalMinutes)
                if(s.subscriptions.isEmpty()) SubscriptionWorker.cancelAll(this@BoxApp)
                else { SubscriptionWorker.sync(this@BoxApp,s.subscriptions);SubscriptionWorker.refreshOnStart(this@BoxApp,s.subscriptions) }
            }
        }
    }
}
