package com.savemy.singboxfork.data

import kotlinx.serialization.Serializable

@Serializable enum class RouteTarget { DIRECT, PROXY, BLOCK }

/**
 * БС — VPN для всего трафика, кроме исключений Белого списка.
 * ЧС — напрямую всё, в VPN идёт только Чёрный список.
 */
@Serializable enum class RoutingMode { WHITELIST_BYPASS, BLACKLIST_PROXY }

/** Файл .srs из каталога rule-set приложения; отсутствующий файл в конфигурацию не попадает. */
@Serializable data class RuleSetFile(
    val file: String,
    val tag: String = "",
    val target: RouteTarget = RouteTarget.DIRECT
)

/** Ручные правила маршрутизации: домены, подсети и пакеты без внешних rule-set. */
@Serializable data class RoutingProfile(
    val directDomains: List<String> = emptyList(),
    val proxyDomains: List<String> = emptyList(),
    val blockDomains: List<String> = emptyList(),
    val directCidrs: List<String> = emptyList(),
    val directPackages: List<String> = emptyList(),
    val proxyPackages: List<String> = emptyList(),
    val blockPackages: List<String> = emptyList(),
    val ruleSets: List<RuleSetFile> = emptyList()
)

/** Одна запись правила в нижнем регистре без схемы, порта и маски; пустые отбрасываются. */
fun normalizeRule(raw: String): String {
    var value = raw.trim().lowercase().substringBefore('#').trim()
    if (value.contains("://")) value = value.substringAfter("://")
    val domain = value.substringBefore('/')
        .substringBefore(':')
        .removePrefix("*.")
        .removePrefix(".")
        .trimEnd('.')
    // Кириллические домены (.рф) ядро понимает только в punycode.
    return runCatching { java.net.IDN.toASCII(domain) }.getOrDefault(domain)
}

fun normalizeRules(values: Collection<String>): List<String> =
    values.map(::normalizeRule).filter { it.isNotEmpty() }.distinct()
