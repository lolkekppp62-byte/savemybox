package com.savemy.singboxfork.data

import com.savemy.singboxfork.core.NodeResult
import kotlinx.serialization.Serializable

/**
 * Профиль проверки узлов. Быстрый — 20 параллельных проб и короткие таймауты,
 * точный — длинные таймауты для медленных сетей.
 */
enum class CheckMode(val tcpConcurrency: Int, val proxyConcurrency: Int, val tcpTimeoutMs: Int, val urlTimeoutMs: Int) {
    FAST(20, 20, 1_500, 2_000),
    PRECISE(12, 8, 3_500, 4_000)
}

/**
 * Метод первого этапа проверки: чем измеряется доступность узла.
 * TCP — порт сервера, ICMP — эхо сервера, HTTP HEAD/GET — запрос к тестовому URL.
 */
@Serializable enum class ProbeMethod { TCP, HTTP_HEAD, HTTP_GET, ICMP }

/** Стиль показа пинга: цифры, шкала, шкала с цифрами или точки. */
@Serializable enum class PingDisplay { NUMBERS, BAR, BAR_NUMBERS, DOTS }

/** Профиль проверки для выбранного метода: у каждого свои «быстрая» и «точная» настройки. */
fun Settings.modeFor(method: ProbeMethod): CheckMode = when (method) {
    ProbeMethod.TCP -> checkMode
    ProbeMethod.HTTP_HEAD -> headMode
    ProbeMethod.HTTP_GET -> getMode
    ProbeMethod.ICMP -> icmpMode
}

/** Свой таймаут (мс) важнее профиля; 0 — брать значения быстрой или точной проверки. */
fun Settings.firstTimeout(profile: CheckMode): Int = probeTimeoutMs.takeIf { it >= 500 } ?: profile.tcpTimeoutMs
fun Settings.urlTimeout(profile: CheckMode): Int = probeTimeoutMs.takeIf { it >= 500 } ?: profile.urlTimeoutMs

/** Тестовые URL для HTTP-проверок: Google по умолчанию плюс быстрые альтернативы. */
object ProbeUrls {
    const val GOOGLE = "https://www.gstatic.com/generate_204"
    const val CLOUDFLARE = "https://cp.cloudflare.com/generate_204"
    const val APPLE = "https://captive.apple.com/hotspot-detect.html"
    const val FIREFOX = "https://detectportal.firefox.com/success.txt"
    val presets = listOf("Google" to GOOGLE, "Cloudflare" to CLOUDFLARE, "Apple" to APPLE, "Firefox" to FIREFOX)
    fun valid(url: String) = runCatching {
        val uri = java.net.URI(url.trim())
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535)
    }.getOrDefault(false)
    /** Пустое или некорректное значение не ломает проверку: берём Google. */
    fun choose(url: String) = url.trim().takeIf(::valid) ?: GOOGLE
    fun label(url: String) = presets.firstOrNull { it.second == url.trim() }?.first ?: "Свой"
}

/** Подписи метода проверки и профиля для интерфейса и журнала. */
object ProbeLabels {
    fun method(value: ProbeMethod): String = when (value) {
        ProbeMethod.TCP -> "TCP"
        ProbeMethod.HTTP_HEAD -> "HTTP HEAD"
        ProbeMethod.HTTP_GET -> "HTTP GET"
        ProbeMethod.ICMP -> "ICMP"
    }
    fun mode(value: CheckMode): String = if (value == CheckMode.FAST) "быстрая" else "точная"
    fun display(value: PingDisplay): String = when (value) {
        PingDisplay.NUMBERS -> "В цифрах (мс)"
        PingDisplay.BAR -> "Шкала"
        PingDisplay.BAR_NUMBERS -> "Шкала и цифры"
        PingDisplay.DOTS -> "Точки"
    }
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
