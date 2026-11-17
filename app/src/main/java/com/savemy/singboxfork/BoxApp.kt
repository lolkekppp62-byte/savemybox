package com.savemy.singboxfork

import android.app.Application
import com.savemy.singboxfork.core.*
import com.savemy.singboxfork.data.DefaultSubscriptionsRegistry
import com.savemy.singboxfork.data.Repository
import com.savemy.singboxfork.work.RefreshWorker
import com.savemy.singboxfork.work.SubscriptionWorker
import kotlinx.coroutines.*

class BoxApp : Application() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    lateinit var repository: Repository; private set
    lateinit var journal: Journal; private set
    lateinit var engine: Engine; private set
    val ready=CompletableDeferred<Unit>()
    override fun onCreate() {
        super.onCreate(); repository=Repository(this); journal=Journal(this); engine=Engine(this,repository,journal)
        scope.launch {
            repository.load(); ready.complete(Unit)
            if(repository.loadError.value==null) {
                // Каталог бесплатных подписок подставляется один раз, на первом запуске.
                val seeded=DefaultSubscriptionsRegistry.seed(repository.state.value.settings.subscriptions)
                if(seeded.size!=repository.state.value.settings.subscriptions.size) {
                    repository.updateSettings { it.copy(subscriptions=seeded) }
                    journal.add("Каталог подписок: добавлено слотов ${seeded.size}")
                }
                val s=repository.state.value.settings
                RefreshWorker.schedule(this@BoxApp,s.intervalMinutes)
                if(s.subscriptions.isEmpty()) SubscriptionWorker.cancelAll(this@BoxApp)
                else { SubscriptionWorker.sync(this@BoxApp,s.subscriptions);SubscriptionWorker.refreshOnStart(this@BoxApp,s.subscriptions) }
            }
        }
    }
}
