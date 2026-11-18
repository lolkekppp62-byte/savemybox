package com.savemy.singboxfork.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.savemy.singboxfork.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Боксы: контейнеры узлов. В бокс можно добавить одну или несколько ссылок,
 * обновлять и проверять его отдельно от остальных.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun SubscriptionScreen(
    context: Context,
    settings: Settings,
    nodes: List<Node>,
    onAdd: (Subscription) -> Unit,
    onUpdate: (Subscription) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshSource: (String, String) -> Unit,
    onRefreshAll: () -> Unit,
    onImport: (String) -> Unit,
    onCheck: () -> Unit,
    onCheckBox: (String) -> Unit,
    back: () -> Unit
) {
    var boxName by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var modeFilter by remember { mutableStateOf<SubscriptionMode?>(null) }
    var suggestCheck by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit(raw: String, name: String = "") {
        val value = raw.trim()
        if (value.isEmpty()) { error = "Вставьте ссылку, JSON или список узлов"; return }
        error = null
        val lines = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val urls = lines.filter { SubscriptionManager.looksLikeUrl(it) }
        if (urls.isNotEmpty()) {
            val title = name.trim().ifBlank { SubscriptionManager.title(value, urls.first()) }
            onAdd(Subscription(url = urls.first(), urls = urls, name = title, source = "url"))
        } else {
            // Список узлов или JSON — без бокса/расписания, как ручной импорт.
            onImport(value)
        }
        text = ""
        boxName = ""
    }

    val camera = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { submit(it) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeQr(context, uri) }
            if (decoded == null) error = "QR-код на изображении не распознан" else submit(decoded)
        }
    }
    fun nodesOf(sub: Subscription) = nodes.filter { it.id.startsWith("${sub.id}::") }
    val shown = SubscriptionManager.sortedForDisplay(
        settings.subscriptions.filter { modeFilter == null || it.mode == modeFilter || it.mode == SubscriptionMode.UNIVERSAL },
        { sub -> nodesOf(sub) }
    )

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(back) { Text("Назад") }
            Text("Боксы", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Бокс объединяет узлы: создайте его и добавьте одну или несколько ссылок (HTTPS, raw GitHub, .txt, Base64, JSON, QR). Каждый бокс обновляется и проверяется отдельно.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ onRefreshAll(); suggestCheck = true }) { Text("Обновить все боксы") }
            }
        }
        if (suggestCheck) item {
            Surface(
                shape = Palette.CardShape, color = Palette.Cyan.copy(alpha = .10f),
                border = BorderStroke(1.dp, Palette.Cyan.copy(alpha = .30f)), modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Боксы обновляются в фоне. Прогнать быструю проверку?", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = Palette.Cyan
                    )
                    TextButton({ suggestCheck = false; onCheck() }) { Text("Проверить", color = Palette.Cyan) }
                }
            }
        }
        item { ModeTabs(modeFilter) { modeFilter = it } }
        item {
            Surface(
                shape = Palette.CardShape, color = Palette.CardFill,
                border = BorderStroke(1.dp, Palette.CardStroke), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Создать бокс", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        boxName, { boxName = it }, label = { Text("Название бокса (необязательно)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        text, { text = it; error = null },
                        label = { Text("Ссылки (по одной на строку) или узлы/JSON") },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp), minLines = 2, isError = error != null
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button({ submit(text, boxName) }, enabled = text.isNotBlank()) { Text("Создать") }
                        OutlinedButton({
                            camera.launch(ScanOptions().apply {
                                setPrompt("Наведите камеру на QR-код")
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            })
                        }) { Text("QR камерой") }
                        OutlinedButton({ gallery.launch("image/*") }) { Text("QR из галереи") }
                    }
                }
            }
        }
        if (shown.isEmpty()) item {
            Text(
                "Боксов пока нет. Узлы, добавленные вручную, остаются в общем списке.",
                color = Palette.TextMuted, style = MaterialTheme.typography.bodySmall
            )
        }
        items(shown, key = { it.id }) { sub ->
            SubscriptionCard(sub, nodesOf(sub), onUpdate, onRemove, onRefresh, onRefreshSource, onCheckBox)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun ModeTabs(value: SubscriptionMode?, onPick: (SubscriptionMode?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            null to "Все",
            SubscriptionMode.BLACKLIST to "ЧС",
            SubscriptionMode.WHITELIST to "БС"
        ).forEach { (mode, label) ->
            FilterChip(
                selected = value == mode, onClick = { onPick(mode) }, label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = (if (mode == SubscriptionMode.WHITELIST) Palette.Emerald else Palette.Cyan).copy(alpha = .18f),
                    selectedLabelColor = if (mode == SubscriptionMode.WHITELIST) Palette.Emerald else Palette.Cyan
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SubscriptionCard(
    sub: Subscription,
    subNodes: List<Node>,
    onUpdate: (Subscription) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshSource: (String, String) -> Unit,
    onCheckBox: (String) -> Unit
) {
    var extraLink by remember(sub.id) { mutableStateOf("") }
    val dead = SubscriptionManager.isDead(subNodes)
    val sources = sub.sources()
    Surface(
        shape = Palette.CardShape,
        color = if (dead) Palette.Rose.copy(alpha = .08f) else Palette.CardFill,
        border = BorderStroke(1.dp, if (dead) Palette.Rose.copy(alpha = .28f) else Palette.CardStroke),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        sub.name.ifBlank { "Бокс" }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${sources.size} ссылок · ${formatStamp(sub.lastUpdated)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = sub.enabled, onCheckedChange = { onUpdate(sub.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Cyan, checkedThumbColor = Palette.Ink)
                )
            }
            sources.forEach { url ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(url, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton({ onRefreshSource(sub.id, url) }) { Text("Обновить ссылку") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${subNodes.size} узлов · живых ${SubscriptionManager.aliveCount(subNodes)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = Palette.TextMuted
                )
                if (dead) Text("Не отвечает", style = MaterialTheme.typography.labelMedium, color = Palette.Rose)
            }
            Text("Режим маршрутизации", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SubscriptionMode.BLACKLIST to "Использовать в ЧС",
                    SubscriptionMode.WHITELIST to "Использовать в БС",
                    SubscriptionMode.UNIVERSAL to "Универсальная"
                ).forEach { (mode, label) ->
                    val accent = when (mode) {
                        SubscriptionMode.WHITELIST -> Palette.Emerald
                        SubscriptionMode.BLACKLIST -> Palette.Cyan
                        SubscriptionMode.UNIVERSAL -> Palette.Amber
                    }
                    FilterChip(
                        selected = sub.mode == mode, onClick = { onUpdate(sub.copy(mode = mode)) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = .18f), selectedLabelColor = accent
                        )
                    )
                }
            }
            Text("Обновление", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SubscriptionRefresh.OFF to "Выкл",
                    SubscriptionRefresh.ON_START to "При запуске",
                    SubscriptionRefresh.H6 to "6 ч",
                    SubscriptionRefresh.H12 to "12 ч",
                    SubscriptionRefresh.H24 to "24 ч"
                ).forEach { (refresh, label) ->
                    FilterChip(selected = sub.refresh == refresh, onClick = { onUpdate(sub.copy(refresh = refresh)) }, label = { Text(label) })
                }
            }
            OutlinedTextField(
                extraLink, { extraLink = it }, label = { Text("Добавить ссылку в бокс") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val link = extraLink.trim()
                    if (SubscriptionManager.looksLikeUrl(link)) {
                        onUpdate(sub.copy(url = if (sub.url.isBlank()) link else sub.url, urls = (sources + link).distinct()))
                        extraLink = ""
                    }
                }, enabled = SubscriptionManager.looksLikeUrl(extraLink.trim())) { Text("Добавить ссылку") }
                Spacer(Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onRefresh(sub.id) }, enabled = sources.isNotEmpty()) { Text("Обновить") }
                TextButton({ onCheckBox(sub.id) }, enabled = subNodes.isNotEmpty()) { Text("Проверить бокс") }
                Spacer(Modifier.weight(1f))
                TextButton({ onRemove(sub.id) }) { Text("Удалить", color = Palette.Rose) }
            }
        }
    }
}

private fun formatStamp(ts: Long): String =
    if (ts <= 0) "ещё не обновлялась" else "обновлено " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))

/** Декодирование QR из изображения галереи: камера не нужна, файл читается через content resolver. */
private fun decodeQr(context: Context, uri: Uri): String? = runCatching {
    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
}.getOrNull()
