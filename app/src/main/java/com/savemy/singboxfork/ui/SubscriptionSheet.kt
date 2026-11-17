package com.savemy.singboxfork.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
 * Экран подписок: ссылка, .txt/Base64-список, JSON, URI-узлы, QR камерой или из галереи.
 * Вкладки БС/ЧС, встроенный каталог бесплатных подписок и параллельное обновление всех ссылок.
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
    onRefreshAll: () -> Unit,
    onImport: (String) -> Unit,
    onCheck: () -> Unit,
    back: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var modeFilter by remember { mutableStateOf<SubscriptionMode?>(null) }
    var catalog by remember { mutableStateOf(false) }
    var suggestCheck by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) { error = "Вставьте ссылку, JSON или список узлов"; return }
        error = null
        if (SubscriptionManager.looksLikeUrl(value)) {
            onAdd(Subscription(url = value, name = SubscriptionManager.title(value, value), source = "url"))
        } else {
            // Список узлов или JSON добавляется сразу, без расписания.
            onImport(value)
        }
        text = ""
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
            Text("Подписки", style = MaterialTheme.typography.headlineMedium)
            Text(
                "HTTPS-ссылка, raw GitHub или .txt, Base64, JSON, URI-ссылки vless/trojan/ss/hy2/tuic и QR-код.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ onRefreshAll(); suggestCheck = true }) { Text("Обновить все подписки") }
                OutlinedButton({ catalog = true }) { Text("Добавить из каталога") }
            }
        }
        if (suggestCheck) item {
            Surface(
                shape = Palette.CardShape, color = Palette.Cyan.copy(alpha = .10f),
                border = BorderStroke(1.dp, Palette.Cyan.copy(alpha = .30f)), modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Узлы обновляются в фоне. Прогнать быструю проверку?", Modifier.weight(1f),
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
                    Text("Добавить", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        text, { text = it; error = null }, label = { Text("Ссылка, JSON или список узлов") },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp), minLines = 2, isError = error != null
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button({ submit(text) }, enabled = text.isNotBlank()) { Text("Добавить") }
                        OutlinedButton({
                            camera.launch(ScanOptions().apply {
                                // Форматы не ограничиваются: QR подписки читается наравне с остальными.
                                setPrompt("Наведите камеру на QR-код подписки")
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
                "Подписок пока нет. Узлы, добавленные вручную, остаются в списке серверов.",
                color = Palette.TextMuted, style = MaterialTheme.typography.bodySmall
            )
        }
        items(shown, key = { it.id }) { sub ->
            SubscriptionCard(sub, nodesOf(sub), onUpdate, onRemove, onRefresh)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (catalog) CatalogDialog(
        settings.subscriptions,
        onDismiss = { catalog = false },
        onPick = { entry -> onAdd(DefaultSubscriptionsRegistry.toSubscription(entry)) }
    )
}

@Composable private fun ModeTabs(value: SubscriptionMode?, onPick: (SubscriptionMode?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            null to "Все",
            SubscriptionMode.BLACKLIST to "ЧС (Чёрный список)",
            SubscriptionMode.WHITELIST to "БС (Белый список)"
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

/** Каталог бесплатных подписок: слоты ЧС и БС с готовой привязкой к режиму. */
@Composable private fun CatalogDialog(existing: List<Subscription>, onDismiss: () -> Unit, onPick: (CatalogEntry) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Каталог подписок") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "Публичные пулы сообщества. Доступность и скорость зависят от сети — проверяйте узлы перед подключением.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted
                )
            }
            items(DefaultSubscriptionsRegistry.entries, key = { it.id }) { entry ->
                val added = existing.any { it.url == entry.url }
                Surface(
                    shape = Palette.CardShape, color = Palette.CardFill,
                    border = BorderStroke(1.dp, Palette.CardStroke), modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall)
                        Text(entry.note, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (entry.mode == SubscriptionMode.WHITELIST) "БС · 24 ч" else "ЧС · 6 ч",
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = if (entry.mode == SubscriptionMode.WHITELIST) Palette.Emerald else Palette.Cyan
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onPick(entry) }, enabled = !added) {
                                Text(if (added) "Добавлено" else "Добавить")
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Закрыть") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SubscriptionCard(
    sub: Subscription,
    subNodes: List<Node>,
    onUpdate: (Subscription) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: (String) -> Unit
) {
    val dead = SubscriptionManager.isDead(subNodes)
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
                        sub.name.ifBlank { "Подписка" }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${if (sub.url.isBlank()) "вставленный список" else sub.url.take(56)} · ${formatStamp(sub.lastUpdated)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = sub.enabled, onCheckedChange = { onUpdate(sub.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Cyan, checkedThumbColor = Palette.Ink)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${subNodes.size} узлов · живых ${SubscriptionManager.aliveCount(subNodes)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = Palette.TextMuted
                )
                if (dead) Text(
                    "Не отвечает", style = MaterialTheme.typography.labelMedium, color = Palette.Rose
                )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onRefresh(sub.id) }, enabled = sub.url.isNotBlank()) { Text("Обновить сейчас") }
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
