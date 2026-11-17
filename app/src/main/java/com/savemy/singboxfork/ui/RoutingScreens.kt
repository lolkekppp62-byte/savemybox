package com.savemy.singboxfork.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.savemy.singboxfork.core.FvPreset
import com.savemy.singboxfork.core.PresetManager
import com.savemy.singboxfork.core.PresetSide
import com.savemy.singboxfork.data.RoutingMode
import com.savemy.singboxfork.data.Settings
import com.savemy.singboxfork.data.normalizeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Строка установленного приложения: пакет, подпись и признак системного. */
data class AppEntry(val id: String, val label: String, val system: Boolean)

internal fun loadApps(context: Context): List<AppEntry> =
    context.packageManager.getInstalledApplications(0)
        .filter { it.packageName != context.packageName }
        .map {
            AppEntry(
                id = it.packageName,
                label = it.loadLabel(context.packageManager).toString(),
                system = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        .sortedBy { it.label.lowercase() }

/** БС — изумруд, ЧС — циан: единый акцент списков во всём приложении. */
internal fun accentOf(side: PresetSide): Color =
    if (side == PresetSide.WHITELIST) Palette.Emerald else Palette.Cyan

@Composable internal fun SideSelector(side: PresetSide, onSelect: (PresetSide) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PresetSide.WHITELIST to "БС · напрямую", PresetSide.BLACKLIST to "ЧС · через VPN").forEach { (value, label) ->
            val accent = accentOf(value)
            FilterChip(
                selected = side == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent.copy(alpha = .18f),
                    selectedLabelColor = accent
                )
            )
        }
    }
}

@Composable internal fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = Palette.TextMuted,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable private fun Counter(text: String, accent: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = .14f)) {
        Text(
            text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = accent,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
        )
    }
}

/** Карточка пресета Фарватера: название, счётчики доменов и найденных приложений, тумблер. */
@Composable internal fun PresetCard(preset: FvPreset, enabled: Boolean, installedCount: Int, onToggle: (Boolean) -> Unit) {
    val accent = accentOf(preset.side)
    val glow by animateFloatAsState(
        targetValue = if (enabled) 1f else .35f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "Пресет"
    )
    Surface(
        shape = Palette.CardShape,
        color = if (enabled) accent.copy(alpha = .10f) else Palette.CardFill,
        border = BorderStroke(1.dp, if (enabled) accent.copy(alpha = .45f * glow) else Palette.CardStroke),
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = .9f + .1f * glow }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.title, style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
                    Text(
                        preset.note, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = enabled, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Palette.Ink)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Counter("${preset.domainCount} доменов", accent)
                Counter("$installedCount прил. на устройстве", accent)
            }
        }
    }
}

@Composable private fun AppRow(app: AppEntry, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else .985f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "Приложение"
    )
    Surface(
        shape = Palette.CardShape,
        color = if (checked) accent.copy(alpha = .10f) else Palette.CardFill,
        border = BorderStroke(1.dp, if (checked) accent.copy(alpha = .40f) else Palette.CardStroke),
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }.clickable { onChange(!checked) }
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.id, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = accent))
        }
    }
}

/** Пресеты сверху, полный список установленных приложений снизу — с поиском и системными пакетами. */
@Composable fun AppBypassScreen(context: Context, settings: Settings, change: ((Settings) -> Settings) -> Unit, back: () -> Unit) {
    val modeSide = if (settings.routingMode == RoutingMode.BLACKLIST_PROXY) PresetSide.BLACKLIST else PresetSide.WHITELIST
    var side by remember { mutableStateOf(modeSide) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
        loading = false
    }
    val installed = remember(apps) { apps.map { it.id }.toSet() }
    val accent = accentOf(side)
    val chosen = if (side == PresetSide.WHITELIST) settings.bypassPackages else settings.proxyApps
    val visible = apps.filter { app ->
        (showSystem || !app.system) && (query.isBlank() || app.label.contains(query, true) || app.id.contains(query, true))
    }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(back) { Text("Назад") }
            Text("Приложения БС и ЧС", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Пресеты Фарватера сами подставляют установленные приложения. Ниже можно вручную добавить или убрать любое приложение.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted
            )
        }
        item { SideSelector(side) { side = it } }
        item { SectionLabel("Пресеты Фарватера · ${PresetManager.bySide(side).size}") }
        items(PresetManager.bySide(side), key = { it.id }) { preset ->
            PresetCard(preset, preset.id in settings.presetIds, PresetManager.installedApps(preset, installed).size) { enabled ->
                change { s -> s.copy(presetIds = if (enabled) s.presetIds + preset.id else s.presetIds - preset.id) }
            }
        }
        item {
            OutlinedTextField(
                query, { query = it }, label = { Text("Поиск: название или пакет") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Показывать системные пакеты", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(showSystem, { showSystem = it })
            }
            Text(
                "В списке ${if (side == PresetSide.WHITELIST) "БС" else "ЧС"}: ${chosen.size} приложений",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = accent
            )
        }
        if (loading) item { CircularProgressIndicator(color = accent) }
        items(visible, key = { it.id }) { app ->
            AppRow(app, app.id in chosen, accent) { enabled ->
                change { s ->
                    val next = if (enabled) chosen + app.id else chosen - app.id
                    if (side == PresetSide.WHITELIST) s.copy(bypassPackages = next) else s.copy(proxyApps = next)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Ручные доменные правила выбранного списка плюс его пресеты. */
@Composable fun RussianListScreen(settings: Settings, change: ((Settings) -> Settings) -> Unit, back: () -> Unit) {
    var side by remember { mutableStateOf(PresetSide.WHITELIST) }
    var value by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val accent = accentOf(side)
    val rules = if (side == PresetSide.WHITELIST) settings.routing.directDomains else settings.routing.proxyDomains
    fun setRules(next: List<String>) = change { s ->
        s.copy(
            routing = if (side == PresetSide.WHITELIST) s.routing.copy(directDomains = next)
            else s.routing.copy(proxyDomains = next)
        )
    }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(back) { Text("Назад") }
            Text("Правила списков", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Домены БС идут напрямую, домены ЧС — через VPN. Пресеты добавляют свои домены автоматически.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted
            )
        }
        item { SideSelector(side) { side = it } }
        item { SectionLabel("Пресеты · ${PresetManager.bySide(side).size}") }
        items(PresetManager.bySide(side), key = { it.id }) { preset ->
            PresetCard(preset, preset.id in settings.presetIds, preset.packages.size) { enabled ->
                change { s -> s.copy(presetIds = if (enabled) s.presetIds + preset.id else s.presetIds - preset.id) }
            }
        }
        item {
            OutlinedTextField(
                value, { value = it; error = null }, label = { Text("Например: example.com или .рф") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, isError = error != null
            )
            Button(onClick = {
                val domain = normalizeRule(value)
                if (domain.isNotBlank() && domain.matches(Regex("[a-zа-я0-9._-]+"))) {
                    setRules((rules + domain).distinct()); value = ""; error = null
                } else error = "Проверьте домен: буквы, цифры, точка и дефис"
            }) { Text("Добавить домен") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        item {
            OutlinedTextField(
                query, { query = it }, label = { Text("Поиск по правилам") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Text(
                "Ручных правил: ${rules.size}", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = accent
            )
        }
        items(rules.filter { query.isBlank() || it.contains(query, true) }, key = { it }) { rule ->
            Surface(
                shape = Palette.CardShape, color = Palette.CardFill,
                border = BorderStroke(1.dp, Palette.CardStroke), modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    TextButton({ setRules(rules - rule) }) { Text("Убрать", color = accent) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
