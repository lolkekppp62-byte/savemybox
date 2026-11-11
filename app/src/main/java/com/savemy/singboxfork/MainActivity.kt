package com.savemy.singboxfork

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.savemy.singboxfork.ui.BoxScreen
import com.savemy.singboxfork.vpn.BoxVpnService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get()=application as BoxApp
    private val notification=registerForActivityResult(ActivityResultContracts.RequestPermission()) { startVpn() }
    private val permission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==RESULT_OK) askNotification() else app.engine.message("Разрешение VPN не получено")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BoxScreen(app,onConnect={
            val intent=VpnService.prepare(this)
            if(intent==null) askNotification() else permission.launch(intent)
        },onDisconnect={ startService(Intent(this,BoxVpnService::class.java).setAction("stop")) }) }
    }
    private fun askNotification() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startVpn()
    }
    private fun startVpn() { runCatching { ContextCompat.startForegroundService(this,Intent(this,BoxVpnService::class.java)) }.onFailure { app.engine.message("Android не разрешил запуск VPN") } }
}
