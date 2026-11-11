package com.savemy.singboxfork.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
@Serializable enum class Mark { NONE, BL, WL }
@Serializable enum class NodeFilter { ALL, BL, WL }
@Serializable data class Node(
    val id: String = UUID.randomUUID().toString(), val name: String, val outbound: String,
    val mark: Mark = Mark.NONE, val latencyMs: Long? = null, val lossPercent: Int? = null,
    val checkedAt: Long = 0
) {
    val protocol: String get() = runCatching { codec.parseToJsonElement(outbound).jsonObject["type"]!!.jsonPrimitive.content }.getOrDefault("?")
}
@Serializable data class Subscription(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String,
    val name: String = url.take(60)
)
@Serializable data class Settings(
    val theme: String = "system", val accent: Long = 0xFF63D6C5,
    val russianServices: Boolean = false,
    val russianPackages: Set<String> = setOf("com.vkontakte.android", "ru.oneme.app", "ru.rostel", "ru.yandex.searchplugin", "ru.yandex.taxi", "ru.yandex.yandexmaps", "ru.ozon.app.android", "com.wildberries.ru", "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.mail.mailapp"),
    val bypassPackages: Set<String> = emptySet(), val smartRouting: Boolean = true,
    val mtu: Int = 1400, val adaptiveMtu: Boolean = false, val packetRecovery: Boolean = false,
    val intervalMinutes: Long = 60, val filter: NodeFilter = NodeFilter.ALL, val selectedId: String? = null,
    val killSwitch: Boolean = false,
    val startOnBoot: Boolean = false,
    val subscriptions: List<Subscription> = emptyList(),
    val subIntervalMinutes: Long = 360
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
