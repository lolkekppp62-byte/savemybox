package com.savemy.singboxfork.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.savemy.singboxfork.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(data:AppData,change:((Settings)->Settings)->Unit,openApps:()->Unit,openRussian:()->Unit,openLogs:()->Unit,context: Context) {
    val s=data.settings
    var custom by remember { mutableStateOf("") };var unit by remember { mutableStateOf("minutes") };var error by remember { mutableStateOf<String?>(null) }
    var subUrl by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text("Настройки",style=MaterialTheme.typography.headlineLarge);Text("Внешний вид меняется сразу. Сетевые настройки переподключают туннель.") }
        item { Section("Оформление") {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Как в системе","dark" to "Тёмная","light" to "Светлая","halloween" to "Хэллоуин","newyear" to "Новый год").forEach { (id,label) ->
                    FilterChip(s.theme==id,{change { it.copy(theme=id,accent=if(id=="halloween") 0xFFFFAE61 else it.accent) }},label={Text(label)})
                }
            }
            Text("Акцент")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(0xFF63D6C5 to "Мята",0xFF91B7FF to "Синий",0xFFCCAAFF to "Лиловый",0xFFFFAE61 to "Янтарь",0xFFFF9DB4 to "Розовый").forEach { (value,label) -> FilterChip(s.accent==value,{change { it.copy(accent=value) }},label={Text(label)}) } }
            if(s.theme=="halloween") Text("☾  Ночь Хэллоуина · тёплый свет и тёмный фон")
            if(s.theme=="newyear") Text("Тихий снег. Анимация учитывает системное отключение анимаций.")
        } }
        item { Section("Безопасность") {
            Toggle("Kill Switch (блокировать трафик без VPN)",s.killSwitch) { value -> change { it.copy(killSwitch=value) } }
            Text("Если туннель упадёт, весь трафик будет заблокирован до восстановления VPN.",style=MaterialTheme.typography.bodySmall)
            Toggle("Запускать VPN при загрузке устройства",s.startOnBoot) { value -> change { it.copy(startOnBoot=value) } }
            Text("Автозапуск VPN после перезагрузки Android.",style=MaterialTheme.typography.bodySmall)
            val pm = context.getSystemService(PowerManager::class.java)
            val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
            if (!ignoring) {
                OutlinedButton(onClick={
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }, modifier=Modifier.fillMaxWidth()) { Text("Отключить оптимизацию батареи") }
                Text("Рекомендуется для стабильной работы VPN в фоне.",style=MaterialTheme.typography.bodySmall)
            } else {
                Text("✓ Оптимизация батареи отключена",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
            }
        } }
        item { Section("Подписки") {
            Text("Текущие подписки: ${s.subscriptions.size}")
            s.subscriptions.forEach { sub ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(sub.name, Modifier.weight(1f), style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={ change { it.copy(subscriptions=it.subscriptions.filterNot { sb -> sb.id==sub.id }) } }) { Text("Удалить") }
                }
            }
            OutlinedTextField(subUrl,{subUrl=it},label={Text("URL подписки (Base64 или ссылки)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Button(onClick={
                val url=subUrl.trim()
                if(url.startsWith("http://") || url.startsWith("https://")) {
                    change { it.copy(subscriptions=it.subscriptions+Subscription(url=url)) }
                    subUrl=""
                }
            },enabled=subUrl.trim().let { it.startsWith("http://") || it.startsWith("https://") }) { Text("Добавить подписку") }
            Text("Клиент скачает все узлы из URL и обновит их по расписанию.",style=MaterialTheme.typography.bodySmall)
        } }
        item { Section("Маршрутизация") {
            Toggle("Российские сервисы",s.russianServices) { value -> change { it.copy(russianServices=value) } }
            Text("Приложения из редактируемого списка идут напрямую. Это список пакетов, не автоматическое определение страны.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(openRussian) { Text("Изменить российский список · ${s.russianPackages.size}") }
            OutlinedButton(openApps) { Text("Обход VPN приложениями · ${s.bypassPackages.size}") }
            Toggle("Переключаться на рабочий узел",s.smartRouting) { value -> change { it.copy(smartRouting=value) } }
            Text("Проверка каждые 30 секунд. После трёх неудачных запросов — выбор самого быстрого доступного узла вне BL.",style=MaterialTheme.typography.bodySmall)
        } }
        item { Section("Сеть") {
            Text("MTU")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(8500,1500,1480,1400,1280).forEach { mtu -> FilterChip(s.mtu==mtu,{change { it.copy(mtu=mtu) }},label={Text("$mtu")}) } }
            Toggle("Адаптивный MTU",s.adaptiveMtu) { value -> change { it.copy(adaptiveMtu=value) } }
            Text("Оценка: MTU сети минус 100 байт, 1280–1500, не выше выбранного. Это не полноценный PMTU Discovery; применяется при запуске/переподключении.",style=MaterialTheme.typography.bodySmall)
            val tuic=data.nodes.firstOrNull { it.id==s.selectedId }?.protocol=="tuic"
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("Восстановление пакетов",Modifier.weight(1f));Switch(s.packetRecovery,{value -> change { it.copy(packetRecovery=value) }},enabled=tuic || s.packetRecovery)
            }
            Text(if(tuic) "TUIC: UDP через надёжный поток QUIC; возможен рост задержки. Для других протоколов опция не применяется." else "Для выбранного протокола отдельное восстановление пакетов недоступно. TCP/QUIC используют собственные механизмы.",style=MaterialTheme.typography.bodySmall)
        } }
        item { Section("Проверка узлов по расписанию") {
            Text("Сейчас: каждые ${s.intervalMinutes} мин.")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Interval.presets.forEach { minutes -> FilterChip(s.intervalMinutes==minutes,{change { it.copy(intervalMinutes=minutes) }},label={Text(if(minutes<60) "$minutes мин" else "${minutes/60} ч")}) } }
            OutlinedTextField(custom,{custom=it;error=null},label={Text("Своё время")},singleLine=true,modifier=Modifier.fillMaxWidth())
            FlowRow { listOf("minutes" to "Минуты","hours" to "Часы","days" to "Дни").forEach { (id,label) -> FilterChip(unit==id,{unit=id},label={Text(label)}) } }
            Button({runCatching { Interval.parse(custom,unit) }.onSuccess { n -> change { it.copy(intervalMinutes=n) };error=null }.onFailure { error=it.message }}) { Text("Сохранить интервал") }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Text("От 15 минут до 30 дней. Android может отложить проверку в режиме сна. Расписание проверяет доступность, а не обновляет подписки.",style=MaterialTheme.typography.bodySmall)
        } }
        item { OutlinedButton(openLogs,Modifier.fillMaxWidth()) { Text("Журнал соединений и проверок") };Text("Савеми Бокс · sing-box 1.14.4",style=MaterialTheme.typography.labelMedium) }
    }
}
@Composable private fun Section(title:String,content:@Composable ColumnScope.()->Unit) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title,style=MaterialTheme.typography.titleMedium);content() } } }
@Composable private fun Toggle(label:String,checked:Boolean,onChange:(Boolean)->Unit) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,Modifier.weight(1f).padding(top=12.dp));Switch(checked,onChange) } }

@Composable fun AppBypassScreen(context:Context,selected:Set<String>,change:(Set<String>)->Unit,back:()->Unit) {
    var apps by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) };var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps=withContext(Dispatchers.IO) { context.packageManager.getInstalledApplications(0).filter { it.packageName!=context.packageName }.map { it.packageName to it.loadLabel(context.packageManager).toString() }.sortedBy { it.second.lowercase() } };loading=false }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(back) { Text("Назад") };Text("Обход VPN",style=MaterialTheme.typography.headlineMedium);Text("Выбрано: ${selected.size}")
        OutlinedTextField(query,{query=it},label={Text("Название или пакет")},modifier=Modifier.fillMaxWidth())
        if(loading) CircularProgressIndicator()
        LazyColumn { items(apps.filter { it.first.contains(query,true)||it.second.contains(query,true) },key={it.first}) { (id,name) ->
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp)) { Checkbox(id in selected,{ yes -> change(if(yes) selected+id else selected-id) });Column { Text(name);Text(id,style=MaterialTheme.typography.bodySmall) } }
        } }
    }
}
@Composable fun RussianListScreen(packages:Set<String>,change:(Set<String>)->Unit,back:()->Unit) {
    var value by remember { mutableStateOf("") };var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(back) { Text("Назад") };Text("Российские приложения",style=MaterialTheme.typography.headlineMedium)
        Text("Укажите точное имя пакета. Список можно исправлять для вашей версии приложения.")
        OutlinedTextField(value,{value=it;error=false},label={Text("Например: com.vkontakte.android")},modifier=Modifier.fillMaxWidth(),isError=error)
        Button({if(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value.trim())) { change(packages+value.trim());value="" } else error=true}) { Text("Добавить пакет") }
        if(error) Text("Некорректное имя пакета",color=MaterialTheme.colorScheme.error)
        LazyColumn { items(packages.sorted(),key={it}) { id -> Row(Modifier.fillMaxWidth()) { Text(id,Modifier.weight(1f).padding(vertical=12.dp));TextButton({change(packages-id)}) { Text("Убрать") } } } }
    }
}
