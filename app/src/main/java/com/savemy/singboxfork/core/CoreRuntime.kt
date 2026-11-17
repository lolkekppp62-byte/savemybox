package com.savemy.singboxfork.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.savemy.singboxfork.data.*
import com.savemy.singboxfork.vpn.BoxVpnService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.go
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.util.UUID

/**
 * Жизненный цикл ядра: командный сервер libbox, временный порт, локальный API и перезагрузка
 * конфигурации. Состояние VPN, проверки и Smart Routing остаются в Engine.
 */
class CoreRuntime(
    private val context: Context,
    private val platform: AndroidPlatform,
    private val handler: CommandServerHandler
) {
    private val coreLog = File(context.filesDir, "core.log")
    /** rule-set читаются из внешнего каталога приложения: пользователь кладёт .srs файлы сам. */
    private val rulesDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "rulesets")
    private var server: CommandServer? = null
    private var api: LocalApi? = null
    private var signature = ""
    private var initialized = false
    var port = 0
        private set
    var secret = ""
        private set

    private fun setup() {
        if (initialized) return
        go.Seq.setContext(context)
        Libbox.setup(SetupOptions().apply {
            basePath = context.filesDir.path
            workingPath = context.filesDir.path
            tempPath = context.cacheDir.path
            fixAndroidStack = true
            logMaxLines = 300
        })
        initialized = true
    }

    private fun fingerprint(data: AppData) = data.nodes.joinToString { it.id + it.outbound + it.mark } + data.settings.toString()

    private fun mtu(s: Settings): Int {
        if (!s.adaptiveMtu) return s.mtu
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val m = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true }
            .mapNotNull { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) cm.getLinkProperties(it)?.mtu?.takeIf { n -> n > 0 } else null }.minOrNull() ?: 1500
        return (m - 100).coerceIn(1280, minOf(s.mtu, 1500))
    }

    private fun spec(data: AppData, vpn: Boolean) = CoreSpec(
        data, vpn, port, secret, coreLog.path, mtu(data.settings), android.os.Build.VERSION.SDK_INT >= 29,
        ruleSetsDir = rulesDir.path,
        advancedJson = data.settings.advancedJson.takeIf { data.settings.advancedMode && it.isNotBlank() }
    )

    fun start(data: AppData, vpn: BoxVpnService?) {
        setup()
        platform.vpn = vpn
        port = ServerSocket(0).use { it.localPort }
        secret = UUID.randomUUID().toString()
        if (coreLog.length() > 512_000) coreLog.writeText("")
        server = CommandServer(handler, platform).also { it.start() }
        api = LocalApi(port, secret)
        server!!.startOrReloadService(ConfigFactory.build(spec(data, vpn != null)), OverrideOptions())
        signature = fingerprint(data)
    }

    /** true — конфигурация разошлась с запущенной и требует перезагрузки службы. */
    fun stale(data: AppData) = signature != fingerprint(data)

    /** Переключение узла через локальный API не меняет конфигурацию: подпись обновляется без перезагрузки. */
    fun acknowledge(data: AppData) {
        signature = fingerprint(data)
    }

    fun reload(data: AppData) {
        server!!.startOrReloadService(ConfigFactory.build(spec(data, true)), OverrideOptions())
        signature = fingerprint(data)
    }

    fun stop() {
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        server = null; api = null; signature = ""
        platform.close(); platform.vpn?.closeTun(); platform.vpn = null
    }

    fun cancelPending() {
        api?.cancelPending()
    }

    fun select(tag: String) {
        api?.select(tag)
    }

    /** Проба через ядро; клиент читает текущий api, поэтому отключение во время проверки даёт ошибку пробы. */
    val proxy: ProxyProbe
        get() = object : ProxyProbe {
            override suspend fun urlTest(tag: String, url: String, timeoutMs: Int): Long {
                val client = api ?: error("Локальный API не запущен")
                val text = withContext(Dispatchers.IO) { client.delay(tag, url, timeoutMs) }
                return codec.parseToJsonElement(text).jsonObject["delay"]?.jsonPrimitive?.long
                    ?: error("Локальный API не вернул задержку")
            }
        }
}
