package com.savemy.singboxfork.core

import android.content.Context
import com.savemy.singboxfork.data.*
import com.savemy.singboxfork.vpn.BoxVpnService
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Phase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
data class ConnectionState(val phase:Phase=Phase.DISCONNECTED,val activeId:String?=null,val notice:String?=null)

/**
 * Фасад ядра: состояние соединения, проверки и Smart Routing.
 * Жизненный цикл libbox — CoreRuntime, двухэтапная проверка — NodeCheckRunner, решение о переключении — SmartRouter.
 * Все изменения ядра сериализованы: проверка не может закрыть активный VPN.
 */
class Engine(private val context:Context,private val repo:Repository,private val journal:Journal) : CommandServerHandler {
    private val lock=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(ConnectionState())
    val state=mutable.asStateFlow()
    val checking=MutableStateFlow(false)
    val check=MutableStateFlow<CheckProgress?>(null)
    val results=MutableStateFlow<Map<String,NodeResult>>(emptyMap())
    private val platform=AndroidPlatform(context)
    private val runtime=CoreRuntime(context,platform,this)
    private val runner=NodeCheckRunner()
    private var health:Job?=null
    @Volatile private var scanJob:Job?=null
    /** Кандидаты зависят от режима: подписки, привязанные к другому списку, не участвуют. */
    private fun candidates(data:AppData)=data.candidates(data.settings.routingMode)

    suspend fun connect(vpn:BoxVpnService)=withContext(Dispatchers.IO) { lock.withLock {
        if(mutable.value.phase==Phase.CONNECTED) return@withLock
        mutable.value=ConnectionState(Phase.CONNECTING)
        try {
            runtime.stop(); val data=repo.state.value
            val selected=candidates(data).firstOrNull { it.id==data.settings.selectedId } ?: candidates(data).firstOrNull()
            requireNotNull(selected) { "Нет узла вне BL в выбранном фильтре" }
            runtime.start(data,vpn)
            mutable.value=ConnectionState(Phase.CONNECTED,selected.id)
            journal.add("VPN подключён")
            health?.cancel(); health=scope.launch { while(isActive) { delay(30_000); healthCheck() } }
        } catch(e:CancellationException) { runtime.stop();mutable.value=ConnectionState();throw e }
        catch(e:Exception) {
            runtime.stop(); mutable.value=ConnectionState(Phase.ERROR,notice="Не удалось запустить VPN. Проверьте узел, разрешение VPN и конфигурацию.")
            journal.add("Ошибка запуска VPN (${e.javaClass.simpleName})")
        }
    } }
    suspend fun disconnect(owner:BoxVpnService?=null)=withContext(Dispatchers.IO) {
        if(owner!=null && platform.vpn!==owner) return@withContext
        scanJob?.cancel();health?.cancel();runtime.cancelPending()
        lock.withLock {
            if(owner!=null && platform.vpn!==owner) return@withLock
            health=null;runtime.stop();mutable.value=ConnectionState();journal.add("VPN отключён")
        }
    }
    suspend fun checkAll(id:String?=null):List<Node> = scan { data -> if(id==null) data.filtered() else data.nodes.filter { it.id==id } }
    /** Проверка только отмеченных узлов: остальные не трогаем. */
    suspend fun checkNodes(ids:Collection<String>):List<Node> = scan { data -> data.nodes.filter { it.id in ids } }
    private suspend fun scan(pick:(AppData)->List<Node>):List<Node> = withContext(Dispatchers.IO) { lock.withLock {
        scanJob=currentCoroutineContext()[Job]
        checking.value=true
        results.value=emptyMap()
        val temporary=mutable.value.phase!=Phase.CONNECTED
        try {
            val data=repo.state.value
            val nodes=pick(data)
            if(nodes.isEmpty()) return@withLock emptyList()
            if(temporary) { runtime.stop();runtime.start(data,null) }
            else if(runtime.stale(data)) {
                // Горячая перезагрузка сохраняет службу; новый TUN учитывает изменённые исключения.
                runtime.reload(data)
                val current=candidates(data).firstOrNull { it.id==data.settings.selectedId } ?: candidates(data).firstOrNull()
                mutable.value=mutable.value.copy(activeId=current?.id)
            }
            val result=measure(nodes,attempts=1,report=true)
            repo.results(result)
            if(check.value?.fallback==true) {
                journal.add("TCP-этап не пропустил ни одного узла: все проверены через URLTest")
                mutable.value=mutable.value.copy(notice="TCP-проверка не прошла ни для одного узла — возможен перехват DNS. Все узлы проверены через URLTest.")
            }
            journal.add("Проверено узлов: ${result.size}; работают: ${result.count { it.latencyMs!=null }}")
            result.filter { it.latencyMs!=null }.sortedBy { it.latencyMs }
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            if(!temporary) failConnection("Не удалось перезагрузить конфигурацию. VPN остановлен.")
            journal.add("Ошибка диагностики (${e.javaClass.simpleName})");throw IllegalStateException("Не удалось проверить узлы. Проверьте подключение к сети и JSON конфигурации.")
        }
        finally { scanJob=null;checking.value=false;check.value=null;if(temporary) runtime.stop() }
    } }
    /** Двухэтапная проверка; прогресс и пакеты результатов уходят в UI только при report=true. */
    private suspend fun measure(nodes:List<Node>,attempts:Int,report:Boolean=false):List<Node> =
        runner.run(nodes,runtime.proxy,attempts,
            onProgress={ if(report) check.value=it },
            onBatch={ batch -> if(report) results.value=results.value+batch.associateBy { it.id } })
    private suspend fun healthCheck() { lock.withLock {
        if(mutable.value.phase!=Phase.CONNECTED || !repo.state.value.settings.smartRouting) return@withLock
        try {
            val data=repo.state.value
            val active=data.nodes.firstOrNull { it.id==mutable.value.activeId } ?: return@withLock
            val activeResult=measure(listOf(active),attempts=3).firstOrNull() ?: return@withLock
            repo.results(listOf(activeResult))
            if(activeResult.latencyMs!=null) return@withLock
            val alternatives=measure(candidates(data).filter { it.id!=active.id },attempts=1)
            repo.results(alternatives)
            val best=SmartRouter.bestFallback(activeResult,alternatives)
            if(best!=null) {
                runtime.select(best.id);repo.updateSettings { it.copy(selectedId=best.id) };runtime.acknowledge(repo.state.value)
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
            runtime.reload(data)
            val selected=candidates(data).firstOrNull { it.id==data.settings.selectedId } ?: candidates(data).firstOrNull()
            mutable.value=mutable.value.copy(activeId=selected?.id,notice="Настройки применены")
        } catch(_:Exception) { failConnection("Настройки не применены: VPN остановлен. Проверьте фильтр и конфигурацию.") }
    } }
    private fun failConnection(message:String) {
        val owner=platform.vpn
        health?.cancel();health=null;runtime.stop();mutable.value=ConnectionState(Phase.ERROR,notice=message)
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
