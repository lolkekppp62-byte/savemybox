package com.savemy.singboxfork.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.savemy.singboxfork.BoxApp
import com.savemy.singboxfork.core.Phase
import com.savemy.singboxfork.data.*
import com.savemy.singboxfork.work.SubscriptionWorker
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BoxScreen(app:BoxApp,onConnect:()->Unit,onDisconnect:()->Unit) {
    val context = LocalContext.current
    val data by app.repository.state.collectAsStateWithLifecycle()
    val connection by app.engine.state.collectAsStateWithLifecycle()
    val checking by app.engine.checking.collectAsStateWithLifecycle()
    val loadError by app.repository.loadError.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Node>?>(null) }
    val scope=rememberCoroutineScope()
    fun action(block:suspend ()->Unit) { scope.launch { try { app.ready.await();block() } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message ?: "Операция не выполнена" } } }
    fun scan(id:String?=null) { action { results=app.engine.checkAll(id) } }
    BoxTheme(data.settings) {
        Scaffold(containerColor=MaterialTheme.colorScheme.background.copy(alpha=0f),topBar={
            TopAppBar(title={ Column { Text(if(page.isEmpty()) "Савеми Бокс" else page,fontWeight=FontWeight.Bold); if(page.isEmpty()) Text("Ваш маршрут. Ваши правила.",style=MaterialTheme.typography.labelMedium) } },
                actions={ if(page.isNotEmpty()) TextButton(onClick={page=""}) { Text("Назад") } else IconButton(onClick={page="Журнал"}) { Icon(Icons.Rounded.ReceiptLong,"Журнал") } })
        },bottomBar={ if(page.isEmpty()) NavigationBar {
            listOf("Главная","Узлы","Настройки").forEachIndexed { i,title ->
                NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(listOf(Icons.Rounded.PowerSettingsNew,Icons.Rounded.Dns,Icons.Rounded.Tune)[i],title)},label={Text(title)})
            }
        } }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when(page) {
                    "Журнал" -> Logs(app)
                    "Обход VPN" -> AppBypassScreen(app,data.settings.bypassPackages,{ selected -> action { app.repository.updateSettings { it.copy(bypassPackages=selected) };app.engine.applySettings() } },{page=""})
                    "Российские приложения" -> RussianListScreen(data.settings.russianPackages,{ selected -> action { app.repository.updateSettings { it.copy(russianPackages=selected) };app.engine.applySettings() } },{page=""})
                    else -> when(tab) {
                        0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            if(data.settings.theme=="halloween") Text("Тыквенный маршрут  ·  ☾",color=MaterialTheme.colorScheme.primary)
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(when(connection.phase) { Phase.DISCONNECTED->"Не подключено";Phase.CONNECTING->"Подключение…";Phase.CONNECTED->"VPN включён";Phase.ERROR->"Ошибка подключения" },style=MaterialTheme.typography.headlineSmall)
                                val active=data.nodes.firstOrNull { it.id==connection.activeId }
                                Text(active?.name ?: "Добавьте узел или выберите его в списке")
                                if(active!=null) Text("Задержка: ${active.latencyMs?.let { "$it мс" } ?: "ещё не измерена"}")
                            } }
                            connection.notice?.let { Text(it,color=MaterialTheme.colorScheme.primary) }
                            loadError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                            Text("Проверка выполняет три HTTPS-запроса через каждый узел. Потери запросов — не измерение потерь IP-пакетов.",style=MaterialTheme.typography.bodySmall)
                            Filters(data.settings.filter) { f -> action { app.repository.updateSettings { it.copy(filter=f) };app.engine.applySettings() } }
                            Spacer(Modifier.height(24.dp))
                            OutlinedButton(onClick={scan()},enabled=!checking && data.nodes.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) { if(checking) Text("Проверяем узлы…") else Text("Найти рабочие узлы") }
                            Button(onClick={if(connection.phase==Phase.CONNECTED || connection.phase==Phase.CONNECTING) onDisconnect() else onConnect()},
                                enabled=connection.phase==Phase.CONNECTED || connection.phase==Phase.CONNECTING || (loadError==null && !checking && data.candidates().isNotEmpty()),modifier=Modifier.fillMaxWidth().height(88.dp),shape=MaterialTheme.shapes.extraLarge) {
                                Icon(Icons.Rounded.PowerSettingsNew,null,Modifier.size(30.dp));Spacer(Modifier.width(12.dp))
                                Text(if(connection.phase==Phase.CONNECTED || connection.phase==Phase.CONNECTING) "Отключить" else "Подключить",style=MaterialTheme.typography.titleLarge)
                            }
                        }
                        1 -> Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                Text("Узлы · ${data.nodes.size}",style=MaterialTheme.typography.headlineSmall)
                                FilledTonalButton(onClick={importing=true}) { Text("Добавить") }
                            }
                            Filters(data.settings.filter) { f -> action { app.repository.updateSettings { it.copy(filter=f) };app.engine.applySettings() } }
                            OutlinedButton(onClick={scan()},enabled=!checking && data.nodes.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Text(if(checking) "Проверка…" else "Найти рабочие узлы") }
                            Text("BL исключён из подключения. Фильтр BL проверяет запрещённые узлы только для диагностики.",style=MaterialTheme.typography.bodySmall)
                            LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                                if(data.filtered().isEmpty()) item { Text("Здесь пока нет узлов. Добавьте ссылку или outbound JSON.",Modifier.padding(20.dp)) }
                                items(data.filtered(),key={it.id}) { node -> NodeCard(node,node.id==connection.activeId,checking,
                                    onCheck={scan(node.id)},onMark={m->action { app.repository.mark(node.id,m);app.engine.applySettings() }},
                                    onSelect={action { app.repository.updateSettings { it.copy(selectedId=node.id) };app.engine.applySettings() }},
                                    onDelete={action { app.repository.remove(node.id);app.engine.applySettings() }}) }
                            }
                        }
                        else -> SettingsScreen(data,change={ transform -> action {
                            val old=app.repository.state.value.settings
                            app.repository.updateSettings(transform)
                            val next=app.repository.state.value.settings
                            if(old.intervalMinutes!=next.intervalMinutes) com.savemy.singboxfork.work.RefreshWorker.schedule(app,next.intervalMinutes)
                            if(next.subscriptions.isNotEmpty()) SubscriptionWorker.schedule(context,next.subIntervalMinutes) else SubscriptionWorker.cancel(context)
                            if(old.copy(theme=next.theme,accent=next.accent,intervalMinutes=next.intervalMinutes,subIntervalMinutes=next.subIntervalMinutes)!=next) app.engine.applySettings()
                        } },openApps={page="Обход VPN"},openRussian={page="Российские приложения"},openLogs={page="Журнал"},context=context)
                    }
                }
            }
        }
        if(importing) ImportDialog(onDismiss={importing=false}) { text,mark -> action {
            val source=text.trim()
            val content=if(source.startsWith("https://",ignoreCase=true)) withContext(Dispatchers.IO) {
                val url=java.net.URL(source)
                require(url.protocol.equals("https",true)) { "Поддерживаются только HTTPS-ссылки" }
                val connection=url.openConnection().apply { connectTimeout=15_000;readTimeout=30_000;setRequestProperty("User-Agent","SavemyBox/1.0") }
                connection.getInputStream().use { stream ->
                    val bytes=stream.readNBytes(1_000_001)
                    require(bytes.size<=1_000_000) { "Список слишком большой (максимум 1 МБ)" }
                    bytes.decodeToString()
                }
            } else source
            val nodes=withContext(Dispatchers.Default) { Importer.parse(content,mark) }
            app.repository.add(nodes);importing=false
            app.journal.add("Добавлено узлов: ${nodes.size}");app.engine.applySettings()
        } }
        error?.let { text -> AlertDialog(onDismissRequest={error=null},title={Text("Не получилось")},text={Text(text)},confirmButton={TextButton(onClick={error=null}) { Text("Понятно") }}) }
        results?.let { nodes -> AlertDialog(onDismissRequest={results=null},title={Text("Рабочие узлы: ${nodes.size}")},text={
            LazyColumn { if(nodes.isEmpty()) item { Text("Рабочие узлы не найдены. Проверьте сеть, фильтр и данные подключения.") }
                items(nodes) { n -> Text("${n.name}\n${n.latencyMs} мс · потери запросов ${n.lossPercent}%",Modifier.padding(vertical=8.dp)) }
            }
        },confirmButton={TextButton(onClick={results=null}) { Text("Закрыть") }}) }
    }
}
@Composable fun Filters(value:NodeFilter,onChange:(NodeFilter)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { NodeFilter.entries.forEach { FilterChip(selected=it==value,onClick={onChange(it)},label={Text(if(it==NodeFilter.ALL) "Все" else it.name)}) } }
}
@Composable private fun NodeCard(n:Node,active:Boolean,checking:Boolean,onCheck:()->Unit,onMark:(Mark)->Unit,onSelect:()->Unit,onDelete:()->Unit) {
    var delete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text((if(active) "● " else "")+n.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Text("${n.protocol.uppercase()} · ${if(n.checkedAt==0L) "Не проверен" else if(n.latencyMs!=null) "Работает · ${n.latencyMs} мс" else "Не работает"}")
        if(n.checkedAt>0) Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(n.checkedAt))} · потери запросов ${n.lossPercent}%",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Mark.entries.forEach { m -> FilterChip(selected=n.mark==m,onClick={onMark(m)},label={Text(if(m==Mark.NONE) "Обычный" else m.name)}) } }
        Row { TextButton(onClick=onSelect,enabled=n.mark!=Mark.BL) { Text("Выбрать") };TextButton(onClick=onCheck,enabled=!checking) { Text("Обновить") };IconButton(onClick={delete=true}) { Icon(Icons.Rounded.DeleteOutline,"Удалить узел") } }
    } }
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("Удалить узел?")},text={Text(n.name)},confirmButton={TextButton(onClick={delete=false;onDelete()}) { Text("Удалить") }},dismissButton={TextButton(onClick={delete=false}) { Text("Отмена") }})
}
@Composable private fun ImportDialog(onDismiss:()->Unit,onImport:(String,Mark)->Unit) {
    var text by remember { mutableStateOf("") };var mark by remember { mutableStateOf(Mark.NONE) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Добавить узлы")},text={Column {
        Text("Вставьте HTTPS-ссылку на список, ссылки узлов по одной на строку или outbound JSON. Ключи сохраняются зашифрованными.")
        OutlinedTextField(value=text,onValueChange={text=it},label={Text("HTTPS-ссылка, ссылки узлов или JSON")},modifier=Modifier.fillMaxWidth().heightIn(max=220.dp),minLines=3)
        Row { Mark.entries.forEach { m -> FilterChip(selected=m==mark,onClick={mark=m},label={Text(if(m==Mark.NONE) "Обычный" else m.name)}) } }
    }},confirmButton={TextButton(onClick={onImport(text,mark)},enabled=text.isNotBlank()) { Text("Добавить") }},dismissButton={TextButton(onClick=onDismiss) { Text("Отмена") }})
}
@Composable private fun Logs(app:BoxApp) {
    val lines by app.journal.lines.collectAsStateWithLifecycle()
    Column(Modifier.padding(16.dp)) { Text("Журнал соединения и проверок. Ссылки и ключи не записываются.");TextButton(onClick={app.journal.clear()}) { Text("Очистить") }
        LazyColumn { items(lines.reversed()) { Text(it,Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodySmall) } }
    }
}
