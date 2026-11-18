package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.CheckStatus
import com.savemy.singboxfork.data.ProbeMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"

enum class TestStage { TCP, URLTEST, DONE }

/**
 * fallback — TCP-этап не пропустил ни одного узла (перехваченный DNS, блокировка, фильтр SYN):
 * URLTest тогда идёт по всем узлам, а не только по «живым» по TCP.
 */
data class CheckProgress(val stage: TestStage, val done: Int, val total: Int, val alive: Int, val fallback: Boolean = false)

/** udpOnly — у протокола нет TCP-порта, первый этап для него бессмысленен. */
data class ProbeTarget(val id: String, val host: String, val port: Int, val udpOnly: Boolean = false)

data class NodeResult(
    val id: String,
    val tcpMs: Long?,
    val latencyMs: Long?,
    val lossPercent: Int?,
    val status: CheckStatus,
    val checkedAt: Long
)

interface TcpProbe { suspend fun connect(host: String, port: Int, timeoutMs: Int): Long? }
/** ICMP-эхо до сервера узла. */
interface IcmpProbe { suspend fun echo(host: String, timeoutMs: Int): Long? }
/** HTTP-запрос к тестовому URL: head=true — HEAD, иначе GET. */
interface HttpProbe { suspend fun request(url: String, head: Boolean, timeoutMs: Int): Long? }
interface ProxyProbe { suspend fun urlTest(tag: String, url: String, timeoutMs: Int): Long }

/** Прямой TCP-коннект из процесса приложения: только отсев мёртвых серверов, не проверка протокола. */
class SocketTcpProbe : TcpProbe {
    override suspend fun connect(host: String, port: Int, timeoutMs: Int): Long? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            val socket = Socket()
            try { socket.connect(InetSocketAddress(host, port), timeoutMs) } finally { runCatching { socket.close() } }
            (System.nanoTime() - started) / 1_000_000
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/** ICMP через системный резолвер: доступность сервера узла без прокси. */
class SystemIcmpProbe : IcmpProbe {
    override suspend fun echo(host: String, timeoutMs: Int): Long? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            if (java.net.InetAddress.getByName(host).isReachable(timeoutMs)) (System.nanoTime() - started) / 1_000_000 else null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
}

/** HTTP HEAD/GET к тестовому URL: задержка запроса и код ответа 2xx–3xx. */
class SystemHttpProbe : HttpProbe {
    override suspend fun request(url: String, head: Boolean, timeoutMs: Int): Long? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val connection = runCatching { java.net.URL(url).openConnection() as java.net.HttpURLConnection }.getOrNull()
            ?: return@withContext null
        try {
            connection.requestMethod = if (head) "HEAD" else "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "SaveMyBox/1.0")
            if (connection.responseCode in 200..399) (System.nanoTime() - started) / 1_000_000 else null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { runCatching { connection.disconnect() } }
    }
}

/**
 * Двухэтапная проверка: массовый пречек отсеивает мёртвые серверы, URLTest через ядро идёт
 * только по живым. Если TCP-этап не пропустил никого (DNS перехвачен, домены блокируются, SYN
 * режется), проверка не помечает всё мёртвым: URLTest выполняется по всем узлам, а в прогрессе
 * выставляется fallback. Результаты отдаются пакетами, чтобы UI обновлялся порциями.
 */
class NodeChecker(
    private val tcp: TcpProbe,
    private val proxy: ProxyProbe,
    private val icmp: IcmpProbe = SystemIcmpProbe(),
    private val http: HttpProbe = SystemHttpProbe(),
    private val method: ProbeMethod = ProbeMethod.TCP,
    private val probeUrl: String = DEFAULT_PROBE_URL,
    private val urls: List<String> = listOf(DEFAULT_PROBE_URL),
    private val tcpConcurrency: Int = 48,
    private val proxyConcurrency: Int = 8,
    private val tcpTimeoutMs: Int = 2_500,
    private val urlTimeoutMs: Int = 5_000,
    private val batchEveryMs: Long = 300,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun run(
        targets: List<ProbeTarget>,
        attempts: Int = 1,
        onProgress: (CheckProgress) -> Unit = {},
        onBatch: suspend (List<NodeResult>) -> Unit = {},
        /** Без ядра (ядро не приняло конфигурацию) результат даёт только TCP-этап. */
        tcpOnly: Boolean = false
    ): List<NodeResult> = coroutineScope {
        require(attempts >= 1) { "Нужна хотя бы одна проба" }
        val total = targets.size
        if (total == 0) {
            onProgress(CheckProgress(TestStage.DONE, 0, 0, 0))
            return@coroutineScope emptyList()
        }
        val results = ConcurrentHashMap<String, NodeResult>()
        val tcpMs = ConcurrentHashMap<String, Long>()
        val finished = AtomicInteger()
        val alive = AtomicInteger()
        val buffer = ArrayList<NodeResult>()
        val bufferLock = Mutex()
        var fallback = false

        fun report(stage: TestStage, stageTotal: Int) =
            onProgress(CheckProgress(stage, finished.get(), stageTotal, alive.get(), fallback))

        suspend fun emit(result: NodeResult, stageTotal: Int) {
            results[result.id] = result
            if (result.status == CheckStatus.ALIVE) alive.incrementAndGet()
            bufferLock.withLock { buffer += result }
            finished.incrementAndGet()
            report(TestStage.URLTEST, stageTotal)
        }

        val flusher = launch {
            while (isActive) {
                delay(batchEveryMs)
                val batch = bufferLock.withLock { if (buffer.isEmpty()) emptyList() else buffer.toList().also { buffer.clear() } }
                if (batch.isNotEmpty()) onBatch(batch)
            }
        }
        try {
            // Этап 1: пречек выбранным методом. UDP-протоколы (hysteria2/tuic/hysteria)
            // не имеют TCP-порта, поэтому TCP-метод для них пропускается.
            val tcpTargets = targets.filterNot { it.udpOnly }
            val stageTargets = if (method == ProbeMethod.TCP) tcpTargets else targets
            report(TestStage.TCP, stageTargets.size)
            if (method == ProbeMethod.TCP || method == ProbeMethod.ICMP) {
                val firstLimiter = Semaphore(tcpConcurrency)
                stageTargets.map { target ->
                    async {
                        firstLimiter.withPermit {
                            val ms = if (method == ProbeMethod.TCP) tcp.connect(target.host, target.port, tcpTimeoutMs)
                                     else icmp.echo(target.host, tcpTimeoutMs)
                            if (ms != null) tcpMs[target.id] = ms
                            finished.incrementAndGet()
                            report(TestStage.TCP, stageTargets.size)
                        }
                    }
                }.awaitAll()
            } else {
                // HTTP HEAD/GET — один запрос к тестовому URL на весь проход: он проверяет
                // доступность сети и адреса, а сам узел подтверждает URLTest.
                val ms = http.request(probeUrl, method == ProbeMethod.HTTP_HEAD, tcpTimeoutMs)
                if (ms != null) stageTargets.forEach { tcpMs[it.id] = ms }
                finished.set(stageTargets.size)
                report(TestStage.TCP, stageTargets.size)
            }
            // Узлы без ответа на первом этапе сразу получают статус «не отвечает».
            stageTargets.forEach { target ->
                if (tcpMs.containsKey(target.id)) return@forEach
                val failed = NodeResult(target.id, null, null, null, CheckStatus.TCP_FAILED, clock())
                results[target.id] = failed
                bufferLock.withLock { buffer += failed }
            }

            if (tcpOnly) {
                // Задержка берётся из TCP-соединения: это доступность сервера, а не проверка протокола.
                targets.forEach { target ->
                    if (results.containsKey(target.id)) return@forEach
                    val ms = tcpMs[target.id]
                    val result = if (ms != null) NodeResult(target.id, ms, ms, null, CheckStatus.ALIVE, clock())
                        else NodeResult(target.id, null, null, null, CheckStatus.TCP_FAILED, clock())
                    results[target.id] = result
                    if (ms != null) alive.incrementAndGet()
                    bufferLock.withLock { buffer += result }
                }
                finished.set(targets.size)
                onProgress(CheckProgress(TestStage.DONE, targets.size, total, alive.get(), true))
                return@coroutineScope targets.mapNotNull { results[it.id] }
            }

            // Ни одна проба первого этапа не прошла: причина может быть в DNS/фильтрации, а не в узлах.
            fallback = stageTargets.isNotEmpty() && tcpMs.isEmpty()
            val second = if (fallback) targets else targets.filter { it.udpOnly || tcpMs.containsKey(it.id) }
            finished.set(0)
            report(TestStage.URLTEST, second.size)

            val proxyLimiter = Semaphore(proxyConcurrency)
            second.map { target ->
                async {
                    proxyLimiter.withPermit {
                        val delays = ArrayList<Long>(attempts)
                        repeat(attempts) { index ->
                            ensureActive()
                            try { delays += proxy.urlTest(target.id, urls[index % urls.size], urlTimeoutMs) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Неудачная проба учитывается, секреты не пишем. */ }
                        }
                        val sorted = delays.sorted()
                        val latency = if (sorted.isEmpty()) null else sorted[sorted.size / 2]
                        emit(NodeResult(
                            id = target.id,
                            tcpMs = tcpMs[target.id],
                            latencyMs = latency,
                            lossPercent = (attempts - delays.size) * 100 / attempts,
                            status = if (latency == null) CheckStatus.URL_FAILED else CheckStatus.ALIVE,
                            checkedAt = clock()
                        ), second.size)
                    }
                }
            }.awaitAll()
        } finally {
            withContext(NonCancellable) {
                flusher.cancelAndJoin()
                val rest = bufferLock.withLock { if (buffer.isEmpty()) emptyList() else buffer.toList().also { buffer.clear() } }
                if (rest.isNotEmpty()) onBatch(rest)
            }
        }
        onProgress(CheckProgress(TestStage.DONE, results.size, total, alive.get(), fallback))
        targets.mapNotNull { results[it.id] }
    }
}
