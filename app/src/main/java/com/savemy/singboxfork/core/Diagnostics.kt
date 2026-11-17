package com.savemy.singboxfork.core

/**
 * Причина отказа ядра в виде, пригодном для интерфейса и журнала.
 * Из текста выбрасываются строки, где могут оказаться адреса узлов, пароли и ключи:
 * sing-box иногда цитирует поле конфигурации целиком.
 */
object Diagnostics {
    private val secretish = listOf("://", "password", "uuid", "private_key", "secret", "token", "pbk")

    fun describe(error: Throwable, limit: Int = 220): String {
        val raw = generateSequence(error) { it.cause }.mapNotNull { it.message }.firstOrNull { it.isNotBlank() }
        val cleaned = raw?.lineSequence()
            ?.filterNot { line -> secretish.any { line.contains(it, ignoreCase = true) } }
            ?.joinToString(" ") { it.trim() }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        val text = cleaned.ifBlank { error.javaClass.simpleName }
        return if (text.length <= limit) text else text.take(limit) + "…"
    }
}
