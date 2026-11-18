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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.savemy.singboxfork.BuildConfig
import com.savemy.singboxfork.core.UpdateState
import com.savemy.singboxfork.core.Updater
import com.savemy.singboxfork.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(data:AppData,change:((Settings)->Settings)->Unit,openApps:()->Unit,openRussian:()->Unit,openSubscriptions:()->Unit,openLogs:()->Unit,updater:Updater,context: Context,onRoamNow:()->Unit,onConnectVpn:()->Unit) {
    val s=data.settings
    val update by updater.state.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf("") };var unit by remember { mutableStateOf("minutes") };var error by remember { mutableStateOf<String?>(null) }
    var customUrl by remember { mutableStateOf("") };var timeoutText by remember { mutableStateOf("") };var probeError by remember { mutableStateOf<String?>(null) }
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
            Button(onClick=onConnectVpn,Modifier.fillMaxWidth()) { Text("Подключить VPN сейчас") }
            Text("Сразу запросит разрешение VPN и подключится к выбранному узлу.",style=MaterialTheme.typography.bodySmall)
            Toggle("Kill Switch (блокировать трафик без VPN)",s.killSwitch) { value -> change { it.copy(killSwitch=value) } }
            Text("Если туннель упадёт, весь трафик будет заблокирован до восстановления VPN.",style=MaterialTheme.typography.bodySmall)
            Toggle("Запускать VPN при загрузке устройства",s.startOnBoot) { value -> change { it.copy(startOnBoot=value) } }
            Text("Автозапуск VPN после перезагрузки Android.",style=MaterialTheme.typography.bodySmall)
            val pm = context.getSystemService(PowerManager::class.java)
            val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
            if (!ignoring) {
                OutlinedButton(onClick={
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }, modifier=Modifier.fillMaxWidth()) { Text("Отключить оптимизацию батареи") }
                Text("Рекомендуется для стабильной работы VPN в фоне.",style=MaterialTheme.typography.bodySmall)
            } else {
                Text("✓ Оптимизация батареи отключена",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
            }
        } }
        item { Section("Боксы") {
            Text("Текущие боксы: ${s.subscriptions.size}")
            s.subscriptions.forEach { sub ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(sub.name.ifBlank { "Бокс" }, Modifier.weight(1f), style=MaterialTheme.typography.bodySmall)
                    Text("${sub.sources().size} ссылок · ${data.nodes.count { it.id.startsWith("${sub.id}::") }} узлов", style=MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(openSubscriptions) { Text("Боксы, ссылки и проверка · ${s.subscriptions.size}") }
            Text("Создайте бокс и добавьте одну или несколько ссылок. Каждый бокс обновляется и проверяется отдельно.",style=MaterialTheme.typography.bodySmall)
        } }
        item { Section("Маршрутизация") {
            Text("Режим")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(RoutingMode.WHITELIST_BYPASS to "БС · всё через VPN", RoutingMode.BLACKLIST_PROXY to "ЧС · VPN только для списка").forEach { (mode,label) ->
                    val accent=if(mode==RoutingMode.WHITELIST_BYPASS) Palette.Emerald else Palette.Cyan
                    FilterChip(s.routingMode==mode,{change { it.copy(routingMode=mode) }},label={Text(label)},
                        colors=FilterChipDefaults.filterChipColors(selectedContainerColor=accent.copy(alpha=.18f),selectedLabelColor=accent))
                }
            }
            Text(if(s.routingMode==RoutingMode.WHITELIST_BYPASS) "БС: VPN для всего трафика, кроме приложений и доменов Белого списка." else "ЧС: напрямую всё, в VPN идут только домены и приложения Чёрного списка.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(openApps) { Text("Приложения БС и ЧС · ${s.bypassPackages.size} / ${s.proxyApps.size}") }
            OutlinedButton(openRussian) { Text("Доменные правила списков") }
            Text("Пресеты: включено ${s.presetIds.size} из 10 · БС изумрудный, ЧС циановый.",style=MaterialTheme.typography.bodySmall)
            Toggle("Российские сервисы",s.russianServices) { value -> change { it.copy(russianServices=value) } }
            Text("Приложения из редактируемого списка идут напрямую. Это список пакетов, не автоматическое определение страны.",style=MaterialTheme.typography.bodySmall)
            Toggle("Переключаться на рабочий узел",s.smartRouting) { value -> change { it.copy(smartRouting=value) } }
            Text("Проверка каждые 30 секунд. После трёх неудачных запросов — выбор самого быстрого доступного узла вне BL.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onRoamNow,Modifier.fillMaxWidth()) { Text("Проверить узел и переключиться сейчас") }
            Text("Проверяет активный узел: если он не отвечает, сразу переключает на самый быстрый рабочий. Работает при подключённом VPN.",style=MaterialTheme.typography.bodySmall)
        } }
        item { Section("Проверка узлов") {
            Toggle("Убирать нерабочие узлы в папку «Не отвечает»",s.hideDeadNodes) { value -> change { it.copy(hideDeadNodes=value) } }
            Text("Неотвечающие узлы сворачиваются внизу списка. Общая проверка проходит и по ним: ответивший узел сам возвращается наверх.",style=MaterialTheme.typography.bodySmall)
            Text("Метод проверки и профиль")
            fun setMode(method:ProbeMethod,mode:CheckMode)=change { st -> when(method) {
                ProbeMethod.TCP -> st.copy(checkMode=mode)
                ProbeMethod.HTTP_HEAD -> st.copy(headMode=mode)
                ProbeMethod.HTTP_GET -> st.copy(getMode=mode)
                ProbeMethod.ICMP -> st.copy(icmpMode=mode)
            } }
            ProbeMethod.entries.forEach { method ->
                Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    FilterChip(s.probeMethod==method,{change { it.copy(probeMethod=method) }},label={Text(ProbeLabels.method(method))})
                    Spacer(Modifier.weight(1f))
                    FilterChip(s.modeFor(method)==CheckMode.FAST,{setMode(method,CheckMode.FAST)},label={Text("Быстрая")})
                    Spacer(Modifier.width(6.dp))
                    FilterChip(s.modeFor(method)==CheckMode.PRECISE,{setMode(method,CheckMode.PRECISE)},label={Text("Точная")})
                }
            }
            Text("TCP — порт сервера узла. ICMP — эхо сервера. HTTP HEAD и HTTP GET — запрос к тестовому URL. Узел в любом случае подтверждается проверкой через ядро (URLTest).",style=MaterialTheme.typography.bodySmall)
            Text("Тестовый URL · ${ProbeUrls.label(s.probeUrl)}")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ProbeUrls.presets.forEach { (label,url) -> FilterChip(s.probeUrl==url,{change { it.copy(probeUrl=url) }},label={Text(label)}) }
            }
            OutlinedTextField(customUrl,{customUrl=it;probeError=null},label={Text("Свой URL (HTTPS)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button({ if(ProbeUrls.valid(customUrl)) { change { it.copy(probeUrl=customUrl.trim()) };probeError=null } else probeError="Нужен адрес https://…" }) { Text("Сохранить URL") }
                OutlinedButton({ customUrl="";change { it.copy(probeUrl=ProbeUrls.GOOGLE) };probeError=null }) { Text("Сбросить на Google") }
            }
            Text("По умолчанию Google (generate_204). Cloudflare, Apple и Firefox отвечают коротким кодом 204 или 200.",style=MaterialTheme.typography.bodySmall)
            Text("Таймаут проверки: ${if(s.probeTimeoutMs>0) "${s.probeTimeoutMs} мс" else "по профилю (${s.modeFor(s.probeMethod).tcpTimeoutMs} / ${s.modeFor(s.probeMethod).urlTimeoutMs} мс)"}")
            OutlinedTextField(timeoutText,{timeoutText=it;probeError=null},label={Text("Свой таймаут, мс (пусто — авто)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button({ runCatching {
                    val ms=if(timeoutText.isBlank()) 0 else timeoutText.trim().toInt()
                    require(ms==0 || ms in 500..20000) { "Таймаут от 500 до 20000 мс" }
                    change { it.copy(probeTimeoutMs=ms) };probeError=null
                }.onFailure { probeError=it.message } }) { Text("Сохранить таймаут") }
                OutlinedButton({ timeoutText="";change { it.copy(probeTimeoutMs=0) };probeError=null }) { Text("Авто") }
            }
            probeError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Text("Шкала быстрой проверки — 1.5 / 2 с, точной — 3.5 / 4 с. Свой таймаут применяется к обоим этапам.",style=MaterialTheme.typography.bodySmall)
            Text("Отображение пинга")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PingDisplay.entries.forEach { value -> FilterChip(s.pingDisplay==value,{change { it.copy(pingDisplay=value) }},label={Text(ProbeLabels.display(value))}) }
            }
            Text("Пинг показывается в списке узлов, на главном экране и в строке «Загрузка / Отдача».",style=MaterialTheme.typography.bodySmall)
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
        item { Section("Обновления") {
            Text("Установлено: ${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})")
            Text("Канал обновлений")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(UpdateChannel.STABLE to "Стабильные",UpdateChannel.BETA to "Бета · 0.0.*").forEach { (channel,label) ->
                    FilterChip(s.updateChannel==channel,{change { it.copy(updateChannel=channel) }},label={Text(label)})
                }
            }
            Text(
                if(s.updateChannel==UpdateChannel.BETA) "Бета получает только версии 0.0.* — они могут быть нестабильными"
                else "Стабильный канал: выпуски без пометки «бета»",
                style=MaterialTheme.typography.bodySmall)
            when (val st = update) {
                is UpdateState.Checking -> Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text("Проверяем…",style=MaterialTheme.typography.bodySmall)
                }
                is UpdateState.UpToDate -> Text("Установлена последняя версия: ${st.current}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                is UpdateState.Available -> {
                    Text("Доступна версия ${st.release.version}",style=MaterialTheme.typography.titleSmall)
                    if(st.release.notes.isNotBlank()) Text(st.release.notes.take(280),style=MaterialTheme.typography.bodySmall)
                    Button({ updater.install(st.release) },Modifier.fillMaxWidth()) { Text("Обновить до ${st.release.version}") }
                }
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress={ st.percent/100f },modifier=Modifier.fillMaxWidth())
                    Text("Скачиваем APK: ${st.percent}%",style=MaterialTheme.typography.bodySmall)
                }
                is UpdateState.Ready -> {
                    Text("APK ${st.release.version} скачан — откройте установщик, Android спросит подтверждение.",style=MaterialTheme.typography.bodySmall)
                    Button({ updater.install(st.release) },Modifier.fillMaxWidth()) { Text("Открыть установщик") }
                }
                is UpdateState.Failed -> Text(st.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                UpdateState.Idle -> Unit
            }
            OutlinedButton({ updater.check(s.updateChannel) },Modifier.fillMaxWidth(),enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading) { Text("Проверить обновление") }
            Text("Обновление качается прямо в приложение. Адрес выпусков — GitHub Releases проекта.",style=MaterialTheme.typography.bodySmall)
        } }
        item { OutlinedButton(openLogs,Modifier.fillMaxWidth()) { Text("Журнал соединений и проверок") };Text("Савеми Бокс · sing-box 1.14.4",style=MaterialTheme.typography.labelMedium) }
    }
}
@Composable private fun Section(title:String,content:@Composable ColumnScope.()->Unit) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title,style=MaterialTheme.typography.titleMedium);content() } } }
@Composable private fun Toggle(label:String,checked:Boolean,onChange:(Boolean)->Unit) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,Modifier.weight(1f).padding(top=12.dp));Switch(checked,onChange) } }


