package com.savemy.singboxfork.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Канал обновлений: стабильные выпуски и бета-ветка 0.0.*. */
@Serializable enum class UpdateChannel { STABLE, BETA }

/** Выпуск из GitHub Releases, в котором есть APK. */
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val prerelease: Boolean
) {
    /** Бета — только версии вида 0.0.* (см. канал BETA). */
    val beta: Boolean get() = version.startsWith("0.0.")
}

/**
 * Сравнение версий и выбор выпуска. Чистый Kotlin: сеть и установка APK — в Updater,
 * поэтому выбор канала и разбор ответа GitHub проверяются тестами без Android.
 */
object UpdateLogic {
    fun normalizeTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** Числовые части версии: "0.1.2-beta" -> [0, 1, 2, 0]. */
    fun segments(version: String): List<Int> = normalizeTag(version).split('.', '-', '+').map { it.toIntOrNull() ?: 0 }

    fun compare(left: String, right: String): Int {
        val a = segments(left)
        val b = segments(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val diff = (a.getOrNull(index) ?: 0) - (b.getOrNull(index) ?: 0)
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** Предварительный канал получает legacy-бету 0.0.* и prerelease; стабильный исключает оба вида. */
    fun matches(channel: UpdateChannel, release: ReleaseInfo): Boolean = when (channel) {
        UpdateChannel.BETA -> release.beta || release.prerelease
        UpdateChannel.STABLE -> !release.beta && !release.prerelease
    }

    /**
     * Новейший выпуск канала. Бета живёт отдельной линией 0.0.*: стабильная 0.1.2 старше неё
     * по номеру, но бета-канал всё равно предлагает свежую 0.0.* сборку — сранение идёт внутри линии.
     */
    fun available(releases: List<ReleaseInfo>, channel: UpdateChannel, current: String): ReleaseInfo? {
        val candidates = releases.filter { it.apkUrl.isNotBlank() && it.version.isNotBlank() && matches(channel, it) }
        val latest = candidates.maxWithOrNull { a, b -> compare(a.version, b.version) } ?: return null
        val sameLine = channel == UpdateChannel.BETA && current.startsWith("0.0.")
        val newer = if (sameLine) isNewer(latest.version, current) else isNewer(latest.version, current) || channel == UpdateChannel.BETA
        return latest.takeIf { newer && it.version != current && (it.beta || isNewer(it.version, current)) }
    }

    /** Ответ GitHub Releases. Битые элементы и выпуски без APK пропускаются. */
    fun parseReleases(payload: String): List<ReleaseInfo> {
        val root = runCatching { codec.parseToJsonElement(payload) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj["draft"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val apk = (obj["assets"] as? JsonArray).orEmpty()
                .filterIsInstance<JsonObject>()
                .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true }
                ?: return@mapNotNull null
            val url = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ReleaseInfo(
                version = normalizeTag(tag),
                tag = tag,
                notes = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                apkUrl = url,
                sizeBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                prerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }.filter { it.version.isNotBlank() }
    }
}
