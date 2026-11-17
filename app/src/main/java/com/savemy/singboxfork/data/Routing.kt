package com.savemy.singboxfork.data

import kotlinx.serialization.Serializable

@Serializable enum class RouteTarget { DIRECT, PROXY, BLOCK }

/** Файл .srs из каталога rule-set приложения; отсутствующий файл в конфигурацию не попадает. */
@Serializable data class RuleSetFile(
    val file: String,
    val tag: String = "",
    val target: RouteTarget = RouteTarget.DIRECT
)

/** Базовый профиль маршрутизации: домены и пакеты без внешних rule-set. */
@Serializable data class RoutingProfile(
    val directDomains: List<String> = emptyList(),
    val blockDomains: List<String> = emptyList(),
    val directPackages: List<String> = emptyList(),
    val blockPackages: List<String> = emptyList(),
    val ruleSets: List<RuleSetFile> = emptyList()
)
