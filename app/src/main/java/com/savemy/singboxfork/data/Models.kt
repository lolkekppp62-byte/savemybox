package com.savemy.singboxfork.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
@Serializable enum class Mark { NONE, BL, WL }
@Serializable enum class CheckStatus { UNCHECKED, TCP_FAILED, URL_FAILED, ALIVE }
@Serializable enum class NodeFilter { ALL, BL, WL }
@Serializable data class Node(
    val id: String = UUID.randomUUID().toString(), val name: String, val outbound: String,
    val mark: Mark = Mark.NONE, val latencyMs: Long? = null, val lossPercent: Int? = null,
    val tcpMs: Long? = null, val status: CheckStatus = CheckStatus.UNCHECKED,
    val checkedAt: Long = 0,
    /** Тип протокола, заполняется парсером; для старых записей пуст и берётся из outbound. */
    val type: String = ""
) {
    val protocol: String get() = type.ifEmpty { runCatching { codec.parseToJsonElement(outbound).jsonObject["type"]!!.jsonPrimitive.content }.getOrDefault("?") }
}
/** Привязка подписки к режиму: универсальная работает в БС и ЧС. */
@Serializable enum class SubscriptionMode { UNIVERSAL, BLACKLIST, WHITELIST }

/** Расписание фонового обновления подписки. */
@Serializable enum class SubscriptionRefresh { OFF, ON_START, H6, H12, H24 }

@Serializable data class Subscription(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val lastUpdated: Long = 0,
    val mode: SubscriptionMode = SubscriptionMode.UNIVERSAL,
    val refresh: SubscriptionRefresh = SubscriptionRefresh.H24,
    /** Откуда добавлена подписка: url, qr, json — только для подписи в интерфейсе. */
    val source: String = "url"
)
@Serializable data class Settings(
    val theme: String = "system", val accent: Long = 0xFF63D6C5,
    val russianServices: Boolean = false,
    val russianPackages: Set<String> = setOf("com.vkontakte.android", "ru.oneme.app", "ru.rostel", "ru.yandex.searchplugin", "ru.yandex.taxi", "ru.yandex.yandexmaps", "ru.ozon.app.android", "com.wildberries.ru", "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.mail.mailapp"),
    val bypassPackages: Set<String> = emptySet(), val proxyApps: Set<String> = emptySet(), val smartRouting: Boolean = true,
    val routingMode: RoutingMode = RoutingMode.WHITELIST_BYPASS,
    val presetIds: Set<String> = setOf("rf-gov", "ru-banks", "ru-market", "ru-media", "lan-p2p"),
    /** Быстрая проверка включена по умолчанию. */
    val checkMode: CheckMode = CheckMode.FAST,
    /** Нерабочие узлы убираются в папку «Не отвечает». */
    val hideDeadNodes: Boolean = true,
    val pingFilter: PingFilterState = PingFilterState.ALL,
    val nodeSort: NodeSortState = NodeSortState.PING,
    val mtu: Int = 1400, val adaptiveMtu: Boolean = false, val packetRecovery: Boolean = false,
    val intervalMinutes: Long = 60, val filter: NodeFilter = NodeFilter.ALL, val selectedId: String? = null,
    val killSwitch: Boolean = false,
    val startOnBoot: Boolean = false,
    val subscriptions: List<Subscription> = emptyList(),
    val subIntervalMinutes: Long = 360,
    val routing: RoutingProfile = RoutingProfile(),
    val advancedMode: Boolean = false,
    val advancedJson: String = "",
    /** Канал обновлений: стабильные выпуски или бета 0.0.*. */
    val updateChannel: UpdateChannel = UpdateChannel.STABLE
)
@Serializable data class AppData(val nodes: List<Node> = emptyList(), val settings: Settings = Settings())
fun AppData.filtered(): List<Node> = nodes.filter { settings.filter == NodeFilter.ALL || it.mark.name == settings.filter.name }
fun AppData.candidates(): List<Node> = filtered().filter { it.mark != Mark.BL }
object Interval {
    val presets = listOf(30L,60L,120L,300L,480L,600L,720L,1440L)
    fun parse(value: String, unit: String): Long {
        val n = value.toLongOrNull() ?: error("Введите целое число")
        val multiplier = when(unit) { "minutes" -> 1; "hours" -> 60; "days" -> 1440; else -> error("Неизвестная единица времени") }
        require(n in 1..43200 && n <= 43200 / multiplier) { "Интервал должен быть от 15 минут до 30 дней" }
        return (n * multiplier).also { require(it >= 15) { "Android допускает фоновый интервал от 15 минут" } }
    }
}
