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
    val ready=CompletableDeferred<Unit>()
    override fun onCreate() {
        super.onCreate(); repository=Repository(this); journal=Journal(this); engine=Engine(this,repository,journal)
        scope.launch {
            repository.load(); ready.complete(Unit)
            if(repository.loadError.value==null) {
                val s=repository.state.value.settings
                RefreshWorker.schedule(this@BoxApp,s.intervalMinutes)
                if(s.subscriptions.isNotEmpty()) SubscriptionWorker.schedule(this@BoxApp,s.subIntervalMinutes)
                else SubscriptionWorker.cancel(this@BoxApp)
            }
        }
    }
}
