package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.CheckMode
import com.savemy.singboxfork.data.Node
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import com.savemy.singboxfork.data.codec

/**
 * Готовит цели проверки из outbound-узлов и переносит результаты чекера обратно в узлы.
 * Чистый Kotlin: не зависит от Android, libbox и состояния VPN.
 * Профиль проверки задаёт параллельность и таймауты двух этапов.
 */
class NodeCheckRunner(
    private val tcp: TcpProbe = SocketTcpProbe(),
    private val mode: CheckMode = CheckMode.FAST
) {
    private val udpOnly = setOf("hysteria2", "tuic", "hysteria")

    fun target(node: Node): ProbeTarget? {
        val obj = runCatching { codec.parseToJsonElement(node.outbound).jsonObject }.getOrNull() ?: return null
        val host = obj["server"]?.jsonPrimitive?.contentOrNull ?: return null
        val port = obj["server_port"]?.jsonPrimitive?.intOrNull ?: return null
        return ProbeTarget(node.id, host.removePrefix("[").removeSuffix("]"), port, node.protocol in udpOnly)
    }

    /** Узлы без разбираемого server/port в результат не попадают: их конфигурация и так не запустится. */
    suspend fun run(
        nodes: List<Node>,
        proxy: ProxyProbe,
        attempts: Int = 1,
        checkMode: CheckMode = mode,
        onProgress: (CheckProgress) -> Unit = {},
        onBatch: suspend (List<NodeResult>) -> Unit = {},
        tcpOnly: Boolean = false
    ): List<Node> {
        val targets = nodes.mapNotNull(::target)
        if (targets.isEmpty()) return emptyList()
        val checker = NodeChecker(
            tcp, proxy,
            tcpConcurrency = checkMode.tcpConcurrency,
            proxyConcurrency = checkMode.proxyConcurrency,
            tcpTimeoutMs = checkMode.tcpTimeoutMs,
            urlTimeoutMs = checkMode.urlTimeoutMs
        )
        val results = checker.run(targets, attempts, onProgress, onBatch, tcpOnly)
        val byId = results.associateBy { it.id }
        return nodes.mapNotNull { node ->
            byId[node.id]?.let { r ->
                node.copy(tcpMs = r.tcpMs, latencyMs = r.latencyMs, lossPercent = r.lossPercent, status = r.status, checkedAt = r.checkedAt)
            }
        }
    }
}
