package com.savemy.singboxfork.data

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/** Лимиты одного импорта: интерфейс и проверка остаются быстрыми. */
const val MAX_NODES_PER_SUBSCRIPTION = 150
const val MAX_ACTIVE_POOL = 500

/**
 * Импорт и привязка подписок. Чистый Kotlin: ссылки, .txt-списки, Base64 и JSON
 * разбираются одним и тем же импортёром узлов.
 */
object SubscriptionManager {
    fun looksLikeUrl(text: String): Boolean {
        val value = text.trim()
        return value.startsWith("https://", true) || value.startsWith("http://", true)
    }

    /** Base64 стандартный, MIME и URL-safe; иначе текст возвращается как есть. */
    fun decode(raw: String): String {
        val text = raw.trim().removePrefix("\uFEFF")
        if (!isBase64(text)) return text
        val decoded = runCatching { String(Base64.getDecoder().decode(text)) }
            .recoverCatching { String(Base64.getMimeDecoder().decode(text)) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(text)) }
            .getOrNull()
        return if (decoded.isNullOrBlank() || decoded == text) text else decoded
    }

    private fun isBase64(text: String): Boolean =
        text.length > 24 && !text.contains("://") && !text.contains(" ") &&
            text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' || it == '\n' || it == '\r' }

    fun parse(raw: String, mark: Mark = Mark.NONE): List<Node> = Importer.parse(decode(raw), mark)

    /** Подписка работает в режиме: универсальная — всегда, иначе только в своём списке. */
    fun modeAllows(sub: Subscription, mode: RoutingMode): Boolean = when (sub.mode) {
        SubscriptionMode.UNIVERSAL -> true
        SubscriptionMode.WHITELIST -> mode == RoutingMode.WHITELIST_BYPASS
        SubscriptionMode.BLACKLIST -> mode == RoutingMode.BLACKLIST_PROXY
    }

    /** Период фонового обновления в минутах; null — по расписанию не обновляется. */
    fun minutes(refresh: SubscriptionRefresh): Long? = when (refresh) {
        SubscriptionRefresh.OFF, SubscriptionRefresh.ON_START -> null
        SubscriptionRefresh.H6 -> 360
        SubscriptionRefresh.H12 -> 720
        SubscriptionRefresh.H24 -> 1440
    }

    fun title(raw: String, url: String): String {
        val head = raw.lineSequence().firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()
        if (head.startsWith("#")) return head.trimStart('#').trim().take(60)
        return url.substringAfterLast('/').substringBefore('?').ifBlank { "Подписка" }.take(60)
    }

    /** Ключ дедупликации: адрес + порт + протокол. */
    fun identity(node: Node): String {
        val obj = runCatching { codec.parseToJsonElement(node.outbound).jsonObject }.getOrNull() ?: return node.id
        val server = obj["server"]?.jsonPrimitive?.contentOrNull
            ?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return node.id
        val port = obj["server_port"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return "$server|$port|${node.protocol}"
    }

    /** Оставляет первый узел для каждой связки адрес+порт+протокол и режет список по лимиту. */
    fun distinct(nodes: List<Node>, limit: Int = MAX_NODES_PER_SUBSCRIPTION): List<Node> {
        val seen = HashSet<String>(nodes.size * 2)
        val result = ArrayList<Node>(minOf(nodes.size, limit))
        for (node in nodes) {
            if (!seen.add(identity(node))) continue
            result += node
            if (result.size >= limit) break
        }
        return result
    }

    /** Общий пул не растёт бесконечно: проверки и список остаются отзывчивыми. */
    fun capPool(nodes: List<Node>, max: Int = MAX_ACTIVE_POOL): List<Node> =
        if (nodes.size <= max) nodes else nodes.take(max)

    fun aliveCount(nodes: List<Node>): Int = nodes.count { it.status == CheckStatus.ALIVE && it.latencyMs != null }

    /** Проверенная подписка без единого живого узла помечается «Не отвечает». */
    fun isDead(nodes: List<Node>): Boolean =
        nodes.isNotEmpty() && nodes.any { it.status != CheckStatus.UNCHECKED } && aliveCount(nodes) == 0

    /** Мёртвые подписки уходят в низ списка, остальные — по названию. */
    fun sortedForDisplay(subs: List<Subscription>, nodesOf: (Subscription) -> List<Node>): List<Subscription> =
        subs.sortedWith(compareBy({ isDead(nodesOf(it)) }, { it.name.lowercase() }))
}

/** Подписка, которой принадлежит узел (id вида "$subId::имя"), либо null для ручных узлов. */
fun AppData.subscriptionOf(node: Node): Subscription? =
    if (!node.id.contains("::")) null
    else settings.subscriptions.firstOrNull { node.id.startsWith("${it.id}::") }

/** Кандидаты подключения с учётом привязки подписок к БС/ЧС. */
fun AppData.candidates(mode: RoutingMode): List<Node> =
    filtered().filter { it.mark != Mark.BL && subscriptionOf(it)?.let { sub -> SubscriptionManager.modeAllows(sub, mode) } != false }

/** Активный узел выбранного режима: при переключении БС/ЧС подставляется узел своей подписки. */
fun AppData.preferredForMode(mode: RoutingMode): Node? {
    val usable = candidates(mode)
    return usable.firstOrNull { it.id == settings.selectedId } ?: usable.firstOrNull()
}

/** Узлы конкретной подписки: id вида "$subId::имя". */
fun AppData.nodesOf(sub: Subscription): List<Node> = nodes.filter { it.id.startsWith("${sub.id}::") }

/**
 * Встроенный каталог бесплатных подписок. Слоты ЧС рассчитаны на заблокированные сервисы,
 * слоты БС — на локальные/РФ-узлы при полном туннеле. Каталог инициализируется один раз.
 */
data class CatalogEntry(
    val id: String,
    val title: String,
    val note: String,
    val url: String,
    val mode: SubscriptionMode,
    val refresh: SubscriptionRefresh
)

object DefaultSubscriptionsRegistry {
    const val BLACKLIST_ID = "catalog-blacklist"
    const val WHITELIST_ID = "catalog-whitelist"

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "$BLACKLIST_ID-v2ray-configs",
            title = "V2ray-Configs · общий пул",
            note = "Публичный GitHub-список VLESS/Reality/Trojan для заблокированных сервисов",
            url = "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/All_Configs_Sub.txt",
            mode = SubscriptionMode.BLACKLIST, refresh = SubscriptionRefresh.H6
        ),
        CatalogEntry(
            id = "$BLACKLIST_ID-aggregator",
            title = "V2RayAggregator · merge",
            note = "Объединённый Base64-список с обновлением каждые 6 часов",
            url = "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/sub/sub_merge.txt",
            mode = SubscriptionMode.BLACKLIST, refresh = SubscriptionRefresh.H6
        ),
        CatalogEntry(
            id = "$BLACKLIST_ID-collector",
            title = "Telegram-collector · mixed",
            note = "Смешанный пул VLESS/Trojan/Hysteria2 для YouTube, Discord и AI",
            url = "https://raw.githubusercontent.com/soroushmirzaei/telegram-configs-collector/main/splitted/mixed",
            mode = SubscriptionMode.BLACKLIST, refresh = SubscriptionRefresh.H6
        ),
        CatalogEntry(
            id = "$WHITELIST_ID-reality",
            title = "Reality-пул · локальные узлы",
            note = "VLESS-Reality для БС: проверяйте пинг, локация узлов не гарантируется",
            url = "https://raw.githubusercontent.com/soroushmirzaei/telegram-configs-collector/main/protocols/reality",
            mode = SubscriptionMode.WHITELIST, refresh = SubscriptionRefresh.H24
        ),
        CatalogEntry(
            id = "$WHITELIST_ID-vless",
            title = "VLESS-пул · БС",
            note = "Резервный VLESS-список для белого списка, обновление раз в сутки",
            url = "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Splitted-By-Protocol/vless.txt",
            mode = SubscriptionMode.WHITELIST, refresh = SubscriptionRefresh.H24
        )
    )

    fun byMode(mode: SubscriptionMode): List<CatalogEntry> = entries.filter { it.mode == mode }

    fun toSubscription(entry: CatalogEntry): Subscription = Subscription(
        name = entry.title, url = entry.url, mode = entry.mode,
        refresh = entry.refresh, source = "catalog"
    )

    /** Первый запуск: каталог подставляется только если пользователь ещё ничего не добавлял. */
    fun seed(existing: List<Subscription>): List<Subscription> =
        if (existing.isEmpty()) entries.map(::toSubscription) else existing
}
