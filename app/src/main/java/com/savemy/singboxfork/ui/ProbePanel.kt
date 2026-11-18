package com.savemy.singboxfork.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.savemy.singboxfork.data.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ProbePanel(s: Settings, change: ((Settings) -> Settings) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var own by remember(s.probeUrl) { mutableStateOf(ProbeUrls.label(s.probeUrl) == "Свой") }
    var url by remember(s.probeUrl) { mutableStateOf(s.probeUrl) }
    var timeout by remember(s.probeTimeoutMs) { mutableStateOf(s.probeTimeoutMs.takeIf { it > 0 }?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun mode(value: CheckMode) = change { when (it.probeMethod) {
        ProbeMethod.TCP -> it.copy(checkMode = value)
        ProbeMethod.HTTP_HEAD -> it.copy(headMode = value)
        ProbeMethod.HTTP_GET -> it.copy(getMode = value)
        ProbeMethod.ICMP -> it.copy(icmpMode = value)
    } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ open = !open }, Modifier.fillMaxWidth()) {
                Text("Проверка узлов · ${ProbeLabels.method(s.probeMethod)} ${if (open) "▴" else "▾"}")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.modeFor(s.probeMethod) == CheckMode.FAST, { mode(CheckMode.FAST) }, label = { Text("Быстрая") })
                FilterChip(s.modeFor(s.probeMethod) == CheckMode.PRECISE, { mode(CheckMode.PRECISE) }, label = { Text("Точная") })
            }
            if (open) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProbeMethod.entries.forEach { method ->
                        FilterChip(s.probeMethod == method, { change { it.copy(probeMethod = method) } }, label = { Text(ProbeLabels.method(method)) })
                    }
                }
                Text("Первый этап — отсев. Работу прокси подтверждает URLTest через ядро.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProbeUrls.presets.forEach { (label, address) ->
                        FilterChip(!own && s.probeUrl == address, { own = false; error = null; change { it.copy(probeUrl = address) } }, label = { Text(label) })
                    }
                    FilterChip(own, { own = true; error = null }, label = { Text("Свой") })
                }
                if (own) {
                    OutlinedTextField(url, { url = it; error = null }, label = { Text("URL проверки (HTTPS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button({
                        if (ProbeUrls.valid(url)) change { it.copy(probeUrl = url.trim()) }
                        else error = "Введите полный HTTPS-адрес без логина и пароля"
                    }) { Text("Сохранить URL") }
                } else Text(s.probeUrl, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(timeout, { timeout = it; error = null }, label = { Text("Таймаут, мс (пусто — по профилю)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton({
                    val ms = if (timeout.isBlank()) 0 else timeout.toIntOrNull()
                    if (ms != null && (ms == 0 || ms in 500..20000)) change { it.copy(probeTimeoutMs = ms) }
                    else error = "Таймаут: от 500 до 20000 мс"
                }) { Text("Сохранить таймаут") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
