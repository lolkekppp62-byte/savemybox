package com.savemy.singboxfork.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.savemy.singboxfork.BoxApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? BoxApp ?: return
        if (app.repository.state.value.settings.startOnBoot) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, BoxVpnService::class.java))
            }
        }
    }
}
