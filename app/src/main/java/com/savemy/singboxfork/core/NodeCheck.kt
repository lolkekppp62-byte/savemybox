package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.CheckStatus
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

/**
 * Двухэтапная проверка: массовый TCP-пречек отсеивает мёртвые серверы, URLTest через ядро идёт
 * только по живым. Если TCP-этап не пропустил никого (DNS перехвачен, домены блокируются, SYN
 * режется), проверка не помечает всё мёртвым: URLTest выполняется по всем узлам, а в прогрессе
 * выставляется fallback. Результаты отдаются пакетами, чтобы UI обновлялся порциями.
 */
class NodeChecker(
    private val tcp: TcpProbe,
    private val proxy: ProxyProbe,
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
        onBatch: suspend (List<NodeResult>) -> Unit = {}
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
            // Этап 1: TCP-пречек. UDP-протоколы (hysteria2/tuic/hysteria) не имеют TCP-порта.
            val tcpTargets = targets.filterNot { it.udpOnly }
            report(TestStage.TCP, tcpTargets.size)
            val tcpLimiter = Semaphore(tcpConcurrency)
            tcpTargets.map { target ->
                async {
                    tcpLimiter.withPermit {
                        val ms = tcp.connect(target.host, target.port, tcpTimeoutMs)
                        if (ms != null) {
                            tcpMs[target.id] = ms
                        } else {
                            val failed = NodeResult(target.id, null, null, null, CheckStatus.TCP_FAILED, clock())
                            results[target.id] = failed
                            bufferLock.withLock { buffer += failed }
                        }
                        finished.incrementAndGet()
                        report(TestStage.TCP, tcpTargets.size)
                    }
                }
            }.awaitAll()

            // Ни один TCP-коннект не прошёл: причина может быть в DNS/фильтрации, а не в узлах.
            fallback = tcpTargets.isNotEmpty() && tcpMs.isEmpty()
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
