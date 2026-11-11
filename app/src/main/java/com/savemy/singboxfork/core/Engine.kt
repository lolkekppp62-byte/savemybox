package com.savemy.singboxfork.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.savemy.singboxfork.data.*
import com.savemy.singboxfork.vpn.BoxVpnService
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.util.UUID

enum class Phase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
data class ConnectionState(val phase:Phase=Phase.DISCONNECTED,val activeId:String?=null,val notice:String?=null)

/** Все изменения ядра сериализованы: проверка не может закрыть активный VPN. */
class Engine(private val context:Context,private val repo:Repository,private val journal:Journal) : CommandServerHandler {
    private val lock=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(ConnectionState())
    val state=mutable.asStateFlow()
    val checking=MutableStateFlow(false)
    private val platform=AndroidPlatform(context)
    private var server:CommandServer?=null
    private var api:LocalApi?=null
    private var port=0
    private var secret=""
    private var signature=""
    private var health:Job?=null
    @Volatile private var scanJob:Job?=null
    private var initialized=false
    private val coreLog=File(context.filesDir,"core.log")
    private fun setup() {
        if(initialized) return
        go.Seq.setContext(context)
        Libbox.setup(SetupOptions().apply {
            basePath=context.filesDir.path; workingPath=context.filesDir.path; tempPath=context.cacheDir.path
            fixAndroidStack=true; logMaxLines=300
        })
        initialized=true
    }
    private fun fingerprint(data:AppData)=data.nodes.joinToString { it.id+it.outbound+it.mark }+data.settings.toString()
    private fun start(data:AppData,vpn:BoxVpnService?) {
        setup(); platform.vpn=vpn
        port=ServerSocket(0).use { it.localPort }; secret=UUID.randomUUID().toString()
        if(coreLog.length()>512_000) coreLog.writeText("")
        server=CommandServer(this,platform).also { it.start() }
        api=LocalApi(port,secret)
        server!!.startOrReloadService(ConfigFactory.create(data,vpn!=null,port,secret,coreLog.path,mtu(data.settings),android.os.Build.VERSION.SDK_INT>=29),OverrideOptions())
        signature=fingerprint(data)
    }
    private fun mtu(s:Settings):Int {
        if(!s.adaptiveMtu) return s.mtu
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val m=cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)==true }
            .mapNotNull { cm.getLinkProperties(it)?.mtu?.takeIf { n -> n>0 } }.minOrNull() ?: 1500
        return (m-100).coerceIn(1280,minOf(s.mtu,1500))
    }
    private fun closeCore() {
        runCatching { server?.closeService() }; runCatching { server?.close() }; server=null;api=null;signature=""
        platform.close(); platform.vpn?.closeTun(); platform.vpn=null
    }
    suspend fun connect(vpn:BoxVpnService)=withContext(Dispatchers.IO) { lock.withLock {
        if(mutable.value.phase==Phase.CONNECTED) return@withLock
        mutable.value=ConnectionState(Phase.CONNECTING)
        try {
            closeCore(); val data=repo.state.value
            val selected=data.candidates().firstOrNull { it.id==data.settings.selectedId } ?: data.candidates().firstOrNull()
            requireNotNull(selected) { "Нет узла вне BL в выбранном фильтре" }
            start(data,vpn)
            mutable.value=ConnectionState(Phase.CONNECTED,selected.id)
            journal.add("VPN подключён")
            health?.cancel(); health=scope.launch { while(isActive) { delay(30_000); healthCheck() } }
        } catch(e:CancellationException) { closeCore();mutable.value=ConnectionState();throw e }
        catch(e:Exception) {
            closeCore(); mutable.value=ConnectionState(Phase.ERROR,notice="Не удалось запустить VPN. Проверьте узел, разрешение VPN и конфигурацию.")
            journal.add("Ошибка запуска VPN (${e.javaClass.simpleName})")
        }
    } }
    suspend fun disconnect(owner:BoxVpnService?=null)=withContext(Dispatchers.IO) {
        if(owner!=null && platform.vpn!==owner) return@withContext
        scanJob?.cancel();health?.cancel();api?.cancelPending()
        lock.withLock {
            if(owner!=null && platform.vpn!==owner) return@withLock
            health=null;closeCore();mutable.value=ConnectionState();journal.add("VPN отключён")
        }
    }
    suspend fun checkAll(id:String?=null):List<Node> = withContext(Dispatchers.IO) { lock.withLock {
        scanJob=currentCoroutineContext()[Job]
        checking.value=true
        val temporary=mutable.value.phase!=Phase.CONNECTED
        try {
            val data=repo.state.value
            val nodes=if(id==null) data.filtered() else data.nodes.filter { it.id==id }
            if(nodes.isEmpty()) return@withLock emptyList()
            if(temporary) { closeCore();start(data,null) }
            else if(signature!=fingerprint(data)) {
                // Горячая перезагрузка сохраняет службу; новый TUN учитывает изменённые исключения.
                server!!.startOrReloadService(ConfigFactory.create(data,true,port,secret,coreLog.path,mtu(data.settings),android.os.Build.VERSION.SDK_INT>=29),OverrideOptions())
                signature=fingerprint(data)
                val current=data.candidates().firstOrNull { it.id==data.settings.selectedId } ?: data.candidates().firstOrNull()
                mutable.value=mutable.value.copy(activeId=current?.id)
            }
            val result=probeAll(nodes)
            repo.results(result)
            journal.add("Проверено узлов: ${result.size}; работают: ${result.count { it.latencyMs!=null }}")
            result.filter { it.latencyMs!=null }.sortedBy { it.latencyMs }
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            if(!temporary) failConnection("Не удалось перезагрузить конфигурацию. VPN остановлен.")
            journal.add("Ошибка диагностики (${e.javaClass.simpleName})");throw IllegalStateException("Не удалось проверить узлы. Проверьте подключение к сети и JSON конфигурации.")
        }
        finally { scanJob=null;checking.value=false;if(temporary) closeCore() }
    } }
    private suspend fun probeAll(nodes:List<Node>):List<Node> = coroutineScope {
        val limiter=kotlinx.coroutines.sync.Semaphore(8)
        nodes.map { node -> async { limiter.acquire();try { probe(node) } finally { limiter.release() } } }.awaitAll()
    }
    private suspend fun probe(node:Node):Node {
        val delays=mutableListOf<Long>()
        repeat(3) {
            currentCoroutineContext().ensureActive()
            try { val value=codec.parseToJsonElement(api!!.delay(node.id)).jsonObject["delay"]!!.jsonPrimitive.long;delays+=value }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { /* Неудачная проба учитывается, но секреты не попадают в журнал. */ }
        }
        return node.copy(latencyMs=delays.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size/2] },lossPercent=(3-delays.size)*100/3,checkedAt=System.currentTimeMillis())
    }
    private suspend fun healthCheck() { lock.withLock {
        if(mutable.value.phase!=Phase.CONNECTED || !repo.state.value.settings.smartRouting) return@withLock
        try {
            val data=repo.state.value
            val active=data.nodes.firstOrNull { it.id==mutable.value.activeId } ?: return@withLock
            val result=probe(active);repo.results(listOf(result))
            if(result.latencyMs!=null) return@withLock
            val alternatives=probeAll(data.candidates().filter { it.id!=active.id })
            repo.results(alternatives)
            val best=alternatives.filter { it.latencyMs!=null }.minByOrNull { it.latencyMs!! }
            if(best!=null) {
                api!!.select(best.id);repo.updateSettings { it.copy(selectedId=best.id) };signature=fingerprint(repo.state.value)
                mutable.value=ConnectionState(Phase.CONNECTED,best.id,"Автопереключение: выбран работающий узел")
                journal.add("Автопереключение после трёх неудачных запросов")
            } else { mutable.value=mutable.value.copy(notice="Нет работающих узлов. VPN остаётся включённым без прямого обхода.");journal.add("Работающие запасные узлы не найдены") }
        } catch(e:CancellationException) { throw e } catch(_:Exception) { journal.add("Не удалось выполнить автоматическую проверку") }
    } }
    fun message(text:String) { mutable.value=mutable.value.copy(notice=text) }
    suspend fun applySettings()=withContext(Dispatchers.IO) { lock.withLock {
        if(mutable.value.phase!=Phase.CONNECTED) return@withLock
        try {
            val data=repo.state.value
            server!!.startOrReloadService(ConfigFactory.create(data,true,port,secret,coreLog.path,mtu(data.settings),android.os.Build.VERSION.SDK_INT>=29),OverrideOptions())
            signature=fingerprint(data)
            val selected=data.candidates().firstOrNull { it.id==data.settings.selectedId } ?: data.candidates().firstOrNull()
            mutable.value=mutable.value.copy(activeId=selected?.id,notice="Настройки применены")
        } catch(_:Exception) { failConnection("Настройки не применены: VPN остановлен. Проверьте фильтр и конфигурацию.") }
    } }
    private fun failConnection(message:String) {
        val owner=platform.vpn
        health?.cancel();health=null;closeCore();mutable.value=ConnectionState(Phase.ERROR,notice=message)
        owner?.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE);owner?.stopSelf()
    }
    override fun serviceStop() { scope.launch { platform.vpn?.stopSelf();disconnect() } }
    override fun serviceReload() { scope.launch { runCatching { checkAll() } } }
    override fun getSystemProxyStatus()=SystemProxyStatus()
    override fun setSystemProxyEnabled(enabled:Boolean)=Unit
    override fun triggerNativeCrash():Unit=error("Отладочный сбой отключён")
    override fun writeDebugMessage(message:String) { /* Сырые сообщения могут содержать адреса и токены. */ }
    override fun connectSSHAgent()=-1
}
