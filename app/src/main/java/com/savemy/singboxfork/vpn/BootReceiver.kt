package com.savemy.singboxfork.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.savemy.singboxfork.BoxApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? BoxApp ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    app.ready.await()
                    if (app.repository.state.value.settings.startOnBoot && VpnService.prepare(context) == null) {
                        runCatching { ContextCompat.startForegroundService(context, Intent(context, BoxVpnService::class.java)) }
                            .onFailure { app.engine.message("Android запретил автозапуск VPN. Откройте приложение и подключитесь вручную.") }
                    }
                }
            } finally { pending.finish() }
        }
    }
}
