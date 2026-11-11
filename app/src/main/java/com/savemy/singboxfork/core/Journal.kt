package com.savemy.singboxfork.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalTime

class Journal(context: Context) {
    private val file=File(context.filesDir,"events.log")
    private val mutable=MutableStateFlow(runCatching { file.readLines().takeLast(300) }.getOrDefault(emptyList()))
    val lines=mutable.asStateFlow()
    @Synchronized fun add(message: String) {
        // Никогда не передавать сюда конфигурацию, ссылки, пароли или сырые исключения импорта.
        val safe=message.replace(Regex("[a-z0-9]+://[^\\s]+"),"[адрес скрыт]").take(500)
        mutable.value=(mutable.value + "${LocalTime.now().withNano(0)}  $safe").takeLast(300)
        runCatching { file.writeText(mutable.value.joinToString("\n")) }
    }
    @Synchronized fun clear() { mutable.value=emptyList(); runCatching { file.writeText("") } }
}
