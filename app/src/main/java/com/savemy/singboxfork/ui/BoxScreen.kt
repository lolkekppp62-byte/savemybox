package com.savemy.singboxfork.ui

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.savemy.singboxfork.BoxApp
import com.savemy.singboxfork.core.CheckProgress
import com.savemy.singboxfork.core.ConnectionState
import com.savemy.singboxfork.core.NodeResult
import com.savemy.singboxfork.core.Phase
import com.savemy.singboxfork.core.TestStage
import com.savemy.singboxfork.data.*
import com.savemy.singboxfork.work.SubscriptionWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BoxScreen(app:BoxApp,onConnect:()->Unit,onDisconnect:()->Unit) {
    val context = LocalContext.current
    val data by app.repository.state.collectAsStateWithLifecycle()
    val connection by app.engine.state.collectAsStateWithLifecycle()
    val progress by app.engine.check.collectAsStateWithLifecycle()
    val live by app.engine.results.collectAsStateWithLifecycle()
    val checking by app.engine.checking.collectAsStateWithLifecycle()
    val loadError by app.repository.loadError.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun action(block:suspend ()->Unit) { scope.launch { try { app.ready.await();block() } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message ?: "Операция не выполнена" } } }
    fun scan(id:String?=null) { action { app.engine.checkAll(id) } }
    fun scanNodes(ids:Set<String>) { action { app.engine.checkNodes(ids) } }
    val connected = connection.phase==Phase.CONNECTED || connection.phase==Phase.CONNECTING
    BoxTheme(data.settings) {
        Scaffold(containerColor = Color.Transparent, topBar = {
            TopAppBar(title = {
                Column {
                    Text(if (page.isEmpty()) "SaveMyBox" else page, fontWeight = FontWeight.Bold)
                    if (page.isEmpty()) Text("Ваш маршрут. Ваши правила.", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                }
            }, actions = {
                if (page.isNotEmpty()) TextButton(onClick = { page = "" }) { Text("Назад") }
                else {
                    IconButton(onClick = { sheet = true }) { Icon(Icons.Rounded.Dns, "Серверы и подписки") }
                    IconButton(onClick = { page = "Журнал" }) { Icon(Icons.Rounded.ReceiptLong, "Журнал") }
                }
            })
        }, bottomBar = {
            if (page.isEmpty()) NavigationBar(containerColor = Palette.Surface.copy(alpha = .94f)) {
                listOf("Главная", "Настройки").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(listOf(Icons.Rounded.PowerSettingsNew, Icons.Rounded.Tune)[index], title) }, label = { Text(title) })
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (page) {
                    "Журнал" -> Logs(app)
                    "Приложения" -> AppBypassScreen(context, data.settings, { transform ->
                        action { app.repository.updateSettings(transform);app.engine.applySettings() }
                    }, { page = "" })
                    "Домены" -> RussianListScreen(data.settings, { transform ->
                        action { app.repository.updateSettings(transform);app.engine.applySettings() }
                    }, { page = "" })
                    "Подписки" -> SubscriptionScreen(context, data.settings, data.nodes,
                        onAdd = { sub -> action {
                            app.repository.updateSettings { it.copy(subscriptions = it.subscriptions + sub) }
                            SubscriptionWorker.schedule(context, sub)
                            if (sub.refresh == SubscriptionRefresh.ON_START) SubscriptionWorker.refreshNow(context, sub.id)
                            app.journal.add("Подписка добавлена")
                        } },
                        onUpdate = { sub -> action {
                            app.repository.updateSettings { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == sub.id) sub else it }) }
                            SubscriptionWorker.schedule(context, sub)
                        } },
                        onRemove = { id -> action {
                            app.repository.updateSettings { s -> s.copy(subscriptions = s.subscriptions.filterNot { it.id == id }) }
                            SubscriptionWorker.cancel(context, id)
                        } },
                        onRefresh = { id -> action { SubscriptionWorker.refreshNow(context, id) } },
                        onRefreshAll = { action { SubscriptionWorker.refreshAllNow(context, data.settings.subscriptions) } },
                        onImport = { raw -> action {
                            val nodes = withContext(Dispatchers.Default) { Importer.parse(raw) }
                            app.repository.add(nodes);app.engine.applySettings()
                            app.journal.add("Добавлено узлов: ${nodes.size}")
                        } },
                        onCheck = { scan() },
                        back = { page = "" })
                    else -> if (tab == 0) MainPane(data, connection, progress, live, checking, loadError,
                        onToggle = { if (connected) onDisconnect() else onConnect() },
                        onScan = { scan() },
                        onServers = { sheet = true },
                        onRefreshSubs = { refreshSubscriptions(context, app) },
                        onCheckMode = { mode -> action { app.repository.updateSettings { it.copy(checkMode = mode) } } })
                    else SettingsScreen(data, change = { transform ->
                        action {
                            val old = app.repository.state.value.settings
                            app.repository.updateSettings(transform)
                            val next = app.repository.state.value.settings
                            if (old.intervalMinutes != next.intervalMinutes) com.savemy.singboxfork.work.RefreshWorker.schedule(app, next.intervalMinutes)
                            if (next.subscriptions.isNotEmpty()) SubscriptionWorker.sync(context, next.subscriptions) else SubscriptionWorker.cancelAll(context)
                            if (old.copy(theme = next.theme, accent = next.accent, intervalMinutes = next.intervalMinutes, subIntervalMinutes = next.subIntervalMinutes) != next) app.engine.applySettings()
                        }
                    }, openApps = { page = "Приложения" }, openRussian = { page = "Домены" }, openSubscriptions = { page = "Подписки" }, openLogs = { page = "Журнал" }, context = context)
                }
            }
        }
        if (importing) ImportDialog(onDismiss = { importing = false }) { text, mark -> action {
            val source = text.trim()
            val content = if (source.startsWith("https://", ignoreCase = true)) withContext(Dispatchers.IO) {
                val url = java.net.URL(source)
                require(url.protocol.equals("https", true)) { "Поддерживаются только HTTPS-ссылки" }
                val connection = url.openConnection().apply { connectTimeout = 15_000;readTimeout = 30_000;setRequestProperty("User-Agent", "SavemyBox/1.0") }
                val raw = connection.getInputStream().use { stream ->
                    val bytes = stream.readNBytes(1_000_001)
                    require(bytes.size <= 1_000_000) { "Список слишком большой (максимум 1 МБ)" }
                    bytes.decodeToString()
                }
                runCatching { String(java.util.Base64.getDecoder().decode(raw.trim())) }
                    .getOrElse { runCatching { String(java.util.Base64.getMimeDecoder().decode(raw.trim())) }.getOrElse { raw } }
            } else source
            val nodes = withContext(Dispatchers.Default) { Importer.parse(content, mark) }
            app.repository.add(nodes);importing = false
            app.journal.add("Добавлено узлов: ${nodes.size}");app.engine.applySettings()
        } }
        error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Не получилось") }, text = { Text(text) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } }) }
        if (sheet) ServersSheet(data, connection, progress, live, checking,
            onDismiss = { sheet = false },
            onAdd = { sheet = false; importing = true },
            onScan = { scan() },
            onFilter = { filter -> action { app.repository.updateSettings { it.copy(filter = filter) };app.engine.applySettings() } },
            onSelect = { node -> action { app.repository.updateSettings { it.copy(selectedId = node.id) };app.engine.applySettings() } },
            onCheck = { id -> scan(id) },
            onMark = { node, mark -> action { app.repository.mark(node.id, mark);app.engine.applySettings() } },
            onDelete = { node -> action { app.repository.remove(node.id);app.engine.applySettings() } },
            onCheckMany = { ids -> scanNodes(ids) },
            onDeleteMany = { ids -> action { app.repository.removeAll(ids);app.engine.applySettings() } },
            onPingFilter = { value -> action { app.repository.updateSettings { it.copy(pingFilter = value) } } },
            onSort = { value -> action { app.repository.updateSettings { it.copy(nodeSort = value) } } },
            onRefreshSub = { refreshSubscriptions(context, app) },
            onOpenSettings = { sheet = false;tab = 1 })
    }
}

@Composable private fun MainPane(data: AppData, connection: ConnectionState, progress: CheckProgress?, live: Map<String, NodeResult>,
                                checking: Boolean, loadError: String?, onToggle: () -> Unit, onScan: () -> Unit,
                                onServers: () -> Unit, onRefreshSubs: () -> Unit, onCheckMode: (CheckMode) -> Unit) {
    val active = data.nodes.firstOrNull { it.id == connection.activeId }
    val sub = data.settings.subscriptions.firstOrNull { it.enabled } ?: data.settings.subscriptions.firstOrNull()
    val subNodes = sub?.let { s -> data.nodes.count { it.id.startsWith("${s.id}::") } } ?: data.nodes.size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ConnectHero(connection, checking, onToggle, onScan)
        CheckModeRow(data.settings.checkMode, onCheckMode)
        NodePlate(active, live[active?.id], onServers)
        SubscriptionCard(sub, subNodes, onRefreshSubs, onServers)
        if (checking) ProgressStrip(progress)
        connection.notice?.let { Text(it, color = Palette.Cyan, style = MaterialTheme.typography.bodyMedium) }
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(6.dp))
        Text("TCP-пречек отсеивает мёртвые серверы, URLTest идёт только по живым. Потери запросов — не измерение потерь IP-пакетов.",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
    }
}

@Composable private fun ConnectHero(connection: ConnectionState, checking: Boolean, onToggle: () -> Unit, onScan: () -> Unit) {
    val connected = connection.phase == Phase.CONNECTED || connection.phase == Phase.CONNECTING
    val accent = if (connected) Palette.Cyan else Palette.TextMuted
    val scale by animateFloatAsState(if (connected) 1f else .92f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "Кнопка подключения")
    Surface(shape = Palette.CardShape, color = Palette.CardFill, border = BorderStroke(1.dp, Palette.CardStroke), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when (connection.phase) {
                Phase.DISCONNECTED -> "VPN выключен"
                Phase.CONNECTING -> "Подключение…"
                Phase.CONNECTED -> "VPN включён"
                Phase.ERROR -> "Ошибка подключения"
            }, style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
            Box(Modifier.padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(200.dp).graphicsLayer { scaleX = scale; scaleY = scale }.background(
                    Brush.radialGradient(listOf(if (connected) Palette.Cyan.copy(alpha = .28f) else Color.Transparent, Color.Transparent)), CircleShape))
                Box(Modifier.size(150.dp).clip(CircleShape)
                    .background(if (connected) Palette.Cyan.copy(alpha = .18f) else Palette.SurfaceHigh)
                    .border(1.dp, if (connected) Palette.Cyan.copy(alpha = .55f) else Palette.CardStroke, CircleShape)
                    .clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(38.dp), tint = accent)
                        Text(if (connected) "Отключить" else "Подключить", style = MaterialTheme.typography.titleMedium, color = accent)
                    }
                }
            }
            Text(if (connected) "Трафик идёт через выбранный узел" else "Один тап — и весь трафик в туннеле",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onScan, enabled = !checking) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (checking) "Проверяем…" else "Проверить узлы")
            }
        }
    }
}

/** Переключатель перед проверкой: быстрая (по умолчанию) или точная для медленных сетей. */
@Composable private fun CheckModeRow(mode: CheckMode, onMode: (CheckMode) -> Unit) {
    Surface(shape = Palette.CardShape, color = Palette.CardFill, border = BorderStroke(1.dp, Palette.CardStroke), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Проверка узлов", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                Text(
                    if (mode == CheckMode.FAST) "Быстрая · 20 потоков, 1.5 / 2 с" else "Точная · 3.5 / 4 с",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = Palette.TextMuted
                )
            }
            FilterChip(mode == CheckMode.FAST, { onMode(CheckMode.FAST) }, label = { Text("Быстрая") })
            Spacer(Modifier.width(6.dp))
            FilterChip(mode == CheckMode.PRECISE, { onMode(CheckMode.PRECISE) }, label = { Text("Точная") })
        }
    }
}

@Composable private fun NodePlate(node: Node?, live: NodeResult?, onOpen: () -> Unit) {
    val status = live?.status ?: node?.status ?: CheckStatus.UNCHECKED
    val latency = live?.latencyMs ?: node?.latencyMs
    val tcp = live?.tcpMs ?: node?.tcpMs
    Surface(shape = Palette.CardShape, color = Palette.CardFill, border = BorderStroke(1.dp, Palette.CardStroke),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Текущий узел", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                Text(node?.name ?: "Узел не выбран", style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(nodeMeta(node, status, tcp), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PingBadge(latency, status)
            Spacer(Modifier.width(10.dp))
            Text("Сменить", style = MaterialTheme.typography.labelLarge, color = Palette.Cyan)
        }
    }
}

@Composable private fun SubscriptionCard(sub: Subscription?, nodeCount: Int, onRefresh: () -> Unit, onOpen: () -> Unit) {
    Surface(shape = Palette.CardShape, color = Palette.CardFill, border = BorderStroke(1.dp, Palette.CardStroke), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Подписка", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                Text(sub?.name?.ifBlank { "Без названия" } ?: "Локальные узлы", style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${sub?.let { "Обновлено: ${formatTime(it.lastUpdated)}" } ?: "Добавлены вручную"} · $nodeCount узлов",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = Palette.TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (sub != null) IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Обновить подписку", tint = Palette.Cyan) }
            TextButton(onClick = onOpen) { Text("Все", color = Palette.Cyan) }
        }
    }
}

@Composable private fun ProgressStrip(progress: CheckProgress?) {
    Surface(shape = Palette.CardShape, color = Palette.Cyan.copy(alpha = .10f),
        border = BorderStroke(1.dp, Palette.Cyan.copy(alpha = .24f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Cyan, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(progressLabel(progress), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = Palette.Cyan)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable private fun ServersSheet(data: AppData, connection: ConnectionState, progress: CheckProgress?, live: Map<String, NodeResult>,
                                    checking: Boolean, onDismiss: () -> Unit, onAdd: () -> Unit, onScan: () -> Unit,
                                    onFilter: (NodeFilter) -> Unit, onSelect: (Node) -> Unit, onCheck: (String) -> Unit,
                                    onMark: (Node, Mark) -> Unit, onDelete: (Node) -> Unit, onRefreshSub: () -> Unit,
                                    onCheckMany: (Set<String>) -> Unit, onDeleteMany: (Set<String>) -> Unit,
                                    onPingFilter: (PingFilterState) -> Unit, onSort: (NodeSortState) -> Unit,
                                    onOpenSettings: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingDelete by remember { mutableStateOf<Node?>(null) }
    // Долгое нажатие включает режим выделения: несколько узлов проверяются или удаляются сразу.
    var choosing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deadOpen by remember { mutableStateOf(false) }
    val split = NodeView.layout(data.filtered(), live, data.settings.pingFilter, data.settings.nodeSort, data.settings.hideDeadNodes)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Palette.Surface) {
        Column(Modifier.fillMaxHeight(.92f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Серверы", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить")
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Filters(data.settings.filter, onFilter)
                Spacer(Modifier.weight(1f))
                if (choosing) {
                    TextButton({ picked = data.filtered().map { it.id }.toSet() }) { Text("Выбрать все") }
                    TextButton({ choosing = false;picked = emptySet() }) { Text("Снять") }
                } else {
                    TextButton(onClick = onScan, enabled = !checking && data.nodes.isNotEmpty()) {
                        Text(if (checking) "Проверка…" else "Проверить все")
                    }
                }
            }
            if (choosing) Text(
                "Выбрано: ${picked.size}. Зажмите карточку, чтобы отметить ещё.",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = Palette.Cyan
            )
            if (checking) ProgressStrip(progress)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                item(key = "subs-title") { SheetTitle("Подписки · ${data.settings.subscriptions.size}") }
                if (data.settings.subscriptions.isEmpty()) item(key = "subs-empty") {
                    Text("Подписок нет. Добавьте ссылку кнопкой «Добавить» или в настройках.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
                items(data.settings.subscriptions, key = { it.id }) { sub ->
                    SubscriptionRow(sub, data.nodes.count { it.id.startsWith("${sub.id}::") }, onRefreshSub, onOpenSettings)
                }
                item(key = "panel") {
                    NodePanel(data.settings.pingFilter, data.settings.nodeSort, onPingFilter, onSort)
                }
                item(key = "nodes-title") {
                    SheetTitle("Узлы · ${split.main.size}" + if (split.deadCount > 0) " · не отвечает ${split.deadCount}" else "")
                }
                if (split.main.isEmpty()) item(key = "nodes-empty") {
                    Text("Здесь пока нет узлов. Добавьте ссылку или outbound JSON.",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
                items(split.main, key = { it.id }) { node ->
                    ServerRow(node, node.id == connection.activeId, checking, live[node.id],
                        choosing = choosing, picked = node.id in picked,
                        onSelect = onSelect, onCheck = onCheck, onMark = onMark, onDelete = { pendingDelete = node },
                        onLongPress = { choosing = true;picked = picked + node.id },
                        onTogglePick = { picked = if (node.id in picked) picked - node.id else picked + node.id })
                }
                if (split.deadCount > 0) item(key = "dead-folder") {
                    DeadFolder(split.deadCount, deadOpen) { deadOpen = !deadOpen }
                }
                if (deadOpen) items(split.dead, key = { it.id }) { node ->
                    ServerRow(node, node.id == connection.activeId, checking, live[node.id],
                        choosing = choosing, picked = node.id in picked,
                        onSelect = onSelect, onCheck = onCheck, onMark = onMark, onDelete = { pendingDelete = node },
                        onLongPress = { choosing = true;picked = picked + node.id },
                        onTogglePick = { picked = if (node.id in picked) picked - node.id else picked + node.id })
                }
                item(key = "footer") { Spacer(Modifier.height(12.dp)) }
            }
            if (choosing) Surface(
                shape = Palette.CardShape, color = Palette.Cyan.copy(alpha = .10f),
                border = BorderStroke(1.dp, Palette.Cyan.copy(alpha = .30f)), modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onCheckMany(picked);choosing = false;picked = emptySet() },
                        enabled = picked.isNotEmpty() && !checking
                    ) { Text("Проверить выбранные · ${picked.size}") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onDeleteMany(picked);choosing = false;picked = emptySet() },
                        enabled = picked.isNotEmpty()
                    ) { Text("Удалить", color = Palette.Rose) }
                }
            }
        }
    }
    pendingDelete?.let { node ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Удалить узел?") }, text = { Text(node.name) },
            confirmButton = { TextButton(onClick = { pendingDelete = null;onDelete(node) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } })
    }
}

/** Компактная панель фильтра по пингу и сортировки над списком узлов. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NodePanel(filter: PingFilterState, sort: NodeSortState, onFilter: (PingFilterState) -> Unit, onSort: (NodeSortState) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Фильтр по пингу", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PingFilterState.entries.forEach { value ->
                FilterChip(filter == value, { onFilter(value) }, label = { Text(NodeView.filterLabel(value)) })
            }
        }
        Text("Сортировка", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NodeSortState.entries.forEach { value ->
                FilterChip(sort == value, { onSort(value) }, label = { Text(NodeView.sortLabel(value)) })
            }
        }
    }
}

/** Свёрнутая папка «Не отвечает»: узлы проверяются вместе с остальными и сами возвращаются наверх. */
@Composable private fun DeadFolder(count: Int, open: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = Palette.CardShape, color = Palette.Rose.copy(alpha = .08f),
        border = BorderStroke(1.dp, Palette.Rose.copy(alpha = .26f)), modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Не отвечает ($count)", Modifier.weight(1f), color = Palette.Rose,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
            )
            Text(if (open) "Свернуть" else "Показать", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ServerRow(node: Node, active: Boolean, checking: Boolean, live: NodeResult?,
                                  choosing: Boolean, picked: Boolean,
                                  onSelect: (Node) -> Unit, onCheck: (String) -> Unit, onMark: (Node, Mark) -> Unit, onDelete: () -> Unit,
                                  onLongPress: () -> Unit, onTogglePick: () -> Unit) {
    val status = live?.status ?: node.status
    val latency = live?.latencyMs ?: node.latencyMs
    val scale by animateFloatAsState(
        if (picked) 1f else .98f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "Выделение узла"
    )
    Surface(shape = Palette.CardShape,
        color = if (picked || active) Palette.Cyan.copy(alpha = .10f) else Palette.CardFill,
        border = BorderStroke(1.dp, if (picked || active) Palette.Cyan.copy(alpha = .45f) else Palette.CardStroke),
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale;scaleY = scale }
            .combinedClickable(onClick = { if (choosing) onTogglePick() }, onLongClick = onLongPress)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (choosing) {
                    Checkbox(picked, { onTogglePick() }, colors = CheckboxDefaults.colors(checkedColor = Palette.Cyan))
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text((if (node.mark == Mark.BL) "BL · " else "") + node.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${node.protocol.uppercase()} · ${statusText(status)}", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PingBadge(latency, status)
            }
            if (!choosing) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Mark.entries.forEach { mark ->
                        FilterChip(selected = node.mark == mark, onClick = { onMark(node, mark) },
                            label = { Text(if (mark == Mark.NONE) "Обычный" else mark.name) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onSelect(node) }, enabled = node.mark != Mark.BL) { Text("Выбрать") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onCheck(node.id) }, enabled = !checking) { Icon(Icons.Rounded.Refresh, "Проверить узел") }
                    IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Удалить узел") }
                }
            }
        }
    }
}

@Composable private fun SubscriptionRow(sub: Subscription, nodeCount: Int, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(shape = Palette.CardShape, color = Palette.CardFill, border = BorderStroke(1.dp, Palette.CardStroke), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sub.name.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatTime(sub.lastUpdated)} · $nodeCount узлов", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Обновить подписку", tint = Palette.Cyan) }
            TextButton(onClick = onOpenSettings) { Text("Настроить") }
        }
    }
}

@Composable private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.TextMuted, modifier = Modifier.padding(top = 8.dp))
}

@Composable private fun PingBadge(latency: Long?, status: CheckStatus) {
    val label = when {
        status == CheckStatus.ALIVE && latency != null -> "$latency мс"
        status == CheckStatus.TCP_FAILED -> "TCP"
        status == CheckStatus.URL_FAILED -> "URL"
        status == CheckStatus.ALIVE -> "OK"
        else -> "—"
    }
    val color = when {
        status == CheckStatus.ALIVE && latency != null -> pingColor(latency)
        status == CheckStatus.TCP_FAILED -> Palette.Rose
        status == CheckStatus.URL_FAILED -> Palette.Amber
        else -> Palette.TextMuted
    }
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = .14f)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = color,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
    }
}

@Composable fun Filters(value: NodeFilter, onChange: (NodeFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NodeFilter.entries.forEach { filter ->
            FilterChip(selected = filter == value, onClick = { onChange(filter) },
                label = { Text(if (filter == NodeFilter.ALL) "Все" else filter.name) })
        }
    }
}

private fun pingColor(ms: Long) = when { ms <= 120L -> Palette.Emerald; ms <= 300L -> Palette.Amber; else -> Palette.Rose }

private fun statusText(status: CheckStatus) = when (status) {
    CheckStatus.UNCHECKED -> "не проверен"
    CheckStatus.TCP_FAILED -> "TCP не отвечает"
    CheckStatus.URL_FAILED -> "URLTest не прошёл"
    CheckStatus.ALIVE -> "работает"
}

private fun nodeMeta(node: Node?, status: CheckStatus, tcp: Long?): String {
    val parts = mutableListOf(node?.protocol?.uppercase() ?: "—")
    tcp?.let { parts += "TCP $it мс" }
    node?.checkedAt?.takeIf { it > 0 && status == CheckStatus.ALIVE }?.let { parts += "потери ${node.lossPercent ?: 0}%" }
    return parts.joinToString(" · ")
}

private fun progressLabel(p: CheckProgress?): String = when {
    p == null -> "Проверяем узлы…"
    p.stage == TestStage.TCP -> "TCP-проверка: ${p.done} из ${p.total}"
    p.fallback -> "TCP не прошёл — проверяем все через URLTest: ${p.done} из ${p.total} · работают ${p.alive}"
    else -> "Проверка узлов: ${p.done} из ${p.total} · работают ${p.alive}"
}

private fun formatTime(ts: Long): String =
    if (ts <= 0) "ещё не обновлялась" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))

private fun refreshSubscriptions(context: Context, app: BoxApp) {
    SubscriptionWorker.refreshNow(context)
    app.journal.add("Запрошено обновление подписок")
}

@Composable private fun ImportDialog(onDismiss: () -> Unit, onImport: (String, Mark) -> Unit) {
    var text by remember { mutableStateOf("") }
    var mark by remember { mutableStateOf(Mark.NONE) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить узлы") }, text = {
        Column {
            Text("Вставьте HTTPS-ссылку на список, ссылки узлов по одной на строку или outbound JSON. Ключи сохраняются зашифрованными.")
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("HTTPS-ссылка, ссылки узлов или JSON") },
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp), minLines = 3)
            Row { Mark.entries.forEach { m -> FilterChip(selected = m == mark, onClick = { mark = m }, label = { Text(if (m == Mark.NONE) "Обычный" else m.name) }) } }
        }
    }, confirmButton = { TextButton(onClick = { onImport(text, mark) }, enabled = text.isNotBlank()) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable private fun Logs(app: BoxApp) {
    val lines by app.journal.lines.collectAsStateWithLifecycle()
    Column(Modifier.padding(16.dp)) {
        Text("Журнал соединения и проверок. Ссылки и ключи не записываются.")
        TextButton(onClick = { app.journal.clear() }) { Text("Очистить") }
        LazyColumn {
            items(lines.reversed()) { Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
