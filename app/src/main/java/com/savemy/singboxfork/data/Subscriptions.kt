package com.savemy.singboxfork.data

import java.util.Base64

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
}

/** Подписка, которой принадлежит узел (id вида "$subId::имя"), либо null для ручных узлов. */
fun AppData.subscriptionOf(node: Node): Subscription? =
    if (!node.id.contains("::")) null
    else settings.subscriptions.firstOrNull { node.id.startsWith("${it.id}::") }

/** Кандидаты подключения с учётом привязки подписок к БС/ЧС. */
fun AppData.candidates(mode: RoutingMode): List<Node> =
    filtered().filter { it.mark != Mark.BL && subscriptionOf(it)?.let { sub -> SubscriptionManager.modeAllows(sub, mode) } != false }
