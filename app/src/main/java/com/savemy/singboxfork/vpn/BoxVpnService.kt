package com.savemy.singboxfork.vpn

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.savemy.singboxfork.BoxApp
import com.savemy.singboxfork.MainActivity
import com.savemy.singboxfork.core.Phase
import kotlinx.coroutines.*

class BoxVpnService : VpnService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val app get()=application as BoxApp
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("vpn","VPN-соединение",NotificationManager.IMPORTANCE_LOW))
        scope.launch { app.engine.state.collect { if(it.phase==Phase.CONNECTED) notifyStatus(it.notice ?: "Подключено") } }
    }
    private fun notification(text: String): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,BoxVpnService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,"vpn").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Савеми Бокс").setContentText(text)
            .setContentIntent(open).setOngoing(true).addAction(0,"Отключить",stop).build()
    }
    private fun notifyStatus(text: String) { getSystemService(NotificationManager::class.java).notify(1,notification(text)) }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="stop") { scope.launch { app.engine.disconnect(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }; return START_NOT_STICKY }
        if(Build.VERSION.SDK_INT>=34) startForeground(1,notification("Подключение…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(1,notification("Подключение…"))
        scope.launch {
            app.ready.await()
            if(app.engine.state.value.phase!=Phase.CONNECTED) app.engine.connect(this@BoxVpnService)
            if(app.engine.state.value.phase==Phase.ERROR) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private var tun: android.os.ParcelFileDescriptor?=null
    fun closeTun() { runCatching { tun?.close() }; tun=null }
    fun openTun(options: io.nekohasekai.libbox.TunOptions): Int {
        check(prepare(this)==null) { "Разрешение VPN отозвано" }
        closeTun()
        val ks=app.repository.state.value.settings.killSwitch
        val builder=Builder().setSession("Савеми Бокс").setMtu(options.mtu).setBlocking(ks)
        if(Build.VERSION.SDK_INT>=29) builder.setMetered(false)
        val v4=options.inet4Address; while(v4.hasNext()) { val a=v4.next(); builder.addAddress(a.address(),a.prefix()) }
        val v6=options.inet6Address; while(v6.hasNext()) { val a=v6.next(); builder.addAddress(a.address(),a.prefix()) }
        builder.addRoute("0.0.0.0",0); builder.addRoute("::",0); builder.addDnsServer("172.19.0.2")
        val excluded=options.excludePackage
        while(excluded.hasNext()) {
            try { builder.addDisallowedApplication(excluded.next()) }
            catch(_:android.content.pm.PackageManager.NameNotFoundException) { /* Неустановленные пакеты сохраняются для будущих установок. */ }
        }
        tun=builder.establish() ?: error("Android не создал VPN")
        return tun!!.fd
    }
    override fun onRevoke() { scope.launch { app.engine.disconnect(); stopSelf() } }
    override fun onDestroy() { scope.cancel(); app.scope.launch { app.engine.disconnect(this@BoxVpnService) }; super.onDestroy() }
}
