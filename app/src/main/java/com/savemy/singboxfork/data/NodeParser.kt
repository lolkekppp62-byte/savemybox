package com.savemy.singboxfork.data

import kotlinx.serialization.json.JsonObject

/**
 * Единый контракт разбора узлов: ссылки, самостоятельный outbound JSON и списки строк.
 * Реализация не пишет исходные ссылки и пароли в сообщения об ошибках.
 */
interface NodeParser {
    /** Бросает IllegalArgumentException с безопасным текстом, если разобрать не удалось. */
    fun parse(text: String, mark: Mark = Mark.NONE): List<Node>

    /** Одна строка: outbound JSON или ссылка. null — строку нужно пропустить (чужая схема/фрагментация). */
    fun parseLine(line: String): Pair<String?, JsonObject>?
}
