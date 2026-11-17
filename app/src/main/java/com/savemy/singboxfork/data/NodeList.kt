package com.savemy.singboxfork.data

import com.savemy.singboxfork.core.NodeResult

/**
 * Профиль проверки узлов. Быстрый — 20 параллельных проб и короткие таймауты,
 * точный — длинные таймауты для медленных сетей.
 */
enum class CheckMode(val tcpConcurrency: Int, val proxyConcurrency: Int, val tcpTimeoutMs: Int, val urlTimeoutMs: Int) {
    FAST(20, 20, 1_500, 2_000),
    PRECISE(12, 8, 3_500, 4_000)
}

/** Фильтр списка узлов по пингу. */
enum class PingFilterState { ALL, ALIVE, FAST_150, FAST_300 }

/** Сортировка списка узлов. */
enum class NodeSortState { PING, NAME, PROTOCOL }

/** Основной список и свёрнутая папка «Не отвечает». */
data class NodeSplit(val main: List<Node>, val dead: List<Node>) {
    val deadCount: Int get() = dead.size
}

/**
 * Чистая логика списка узлов: живые узлы с пингом поднимаются наверх по мере
 * получения результатов, проверенные без ответа уходят в папку «Не отвечает»
 * и возвращаются в основной список, как только снова ответили.
 */
object NodeView {
    fun status(node: Node, live: NodeResult? = null): CheckStatus = live?.status ?: node.status

    fun ping(node: Node, live: NodeResult? = null): Long? = live?.latencyMs ?: node.latencyMs

    /** Проверенный и без задержки — значит, не отвечает. Непроверенные сюда не попадают. */
    fun isDead(node: Node, live: NodeResult? = null): Boolean =
        status(node, live) != CheckStatus.UNCHECKED && ping(node, live) == null

    fun matches(node: Node, live: NodeResult?, filter: PingFilterState): Boolean {
        val ms = ping(node, live) ?: return filter == PingFilterState.ALL
        return when (filter) {
            PingFilterState.ALL, PingFilterState.ALIVE -> true
            PingFilterState.FAST_150 -> ms <= 150
            PingFilterState.FAST_300 -> ms <= 300
        }
    }

    fun sorted(nodes: List<Node>, live: Map<String, NodeResult>, sort: NodeSortState): List<Node> = when (sort) {
        // Нерабочие без пинга уходят в конец, живые сортируются по задержке.
        NodeSortState.PING -> nodes.sortedWith(
            compareBy({ ping(it, live[it.id]) ?: Long.MAX_VALUE }, { it.name.lowercase() })
        )
        NodeSortState.NAME -> nodes.sortedWith(
            compareBy({ isDead(it, live[it.id]) }, { it.name.lowercase() })
        )
        NodeSortState.PROTOCOL -> nodes.sortedWith(
            compareBy({ isDead(it, live[it.id]) }, { it.protocol }, { it.name.lowercase() })
        )
    }

    /**
     * hideDead = true — нерабочие вынимаются в отдельную папку.
     * hideDead = false — остаются в общем списке, но всегда в самом низу.
     */
    fun layout(
        nodes: List<Node>,
        live: Map<String, NodeResult>,
        filter: PingFilterState,
        sort: NodeSortState,
        hideDead: Boolean
    ): NodeSplit {
        val visible = nodes.filter { matches(it, live[it.id], filter) }
        val ordered = sorted(visible, live, sort)
        if (!hideDead) return NodeSplit(ordered, emptyList())
        val (dead, alive) = ordered.partition { isDead(it, live[it.id]) }
        return NodeSplit(alive, dead)
    }

    /** Подпись фильтра для панели над списком. */
    fun filterLabel(filter: PingFilterState): String = when (filter) {
        PingFilterState.ALL -> "Все"
        PingFilterState.ALIVE -> "Живые"
        PingFilterState.FAST_150 -> "< 150 мс"
        PingFilterState.FAST_300 -> "< 300 мс"
    }

    fun sortLabel(sort: NodeSortState): String = when (sort) {
        NodeSortState.PING -> "По пингу"
        NodeSortState.NAME -> "По названию"
        NodeSortState.PROTOCOL -> "По протоколу"
    }
}
