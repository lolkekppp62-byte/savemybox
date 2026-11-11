package com.savemy.singboxfork.data

import kotlinx.serialization.Serializable
import java.util.UUID

// fix: Модель подписки
@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val lastUpdated: Long = 0L
)

// fix: Настройки приложения с поддержкой подписок
@Serializable
data class Settings(
    val subscriptions: List<Subscription> = emptyList(), // fix: добавлен недостающий список подписок
    val autoUpdateSubscriptions: Boolean = false,
    val updateIntervalHours: Int = 24,
    val bypassLan: Boolean = true,
    val allowInsecure: Boolean = false,
    val resolveDestination: Boolean = false,
    val ipv6Mode: String = "disable",
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "https://223.5.5.5/dns-query"
)