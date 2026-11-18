package com.savemy.singboxfork.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.savemy.singboxfork.data.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun HomeNodes(data: AppData, checking: Boolean, onAdd: () -> Unit,
                         onFolder: (String) -> Unit, onCombine: (Set<String>, String) -> Unit,
                         onEdit: (Node, String, String, Mark, String?) -> Unit,
                         onDelete: (Node) -> Unit, onSelect: (Node) -> Unit, onCheck: (String) -> Unit,
                         onCheckGroup: (Set<String>) -> Unit) {
    var editing by remember { mutableStateOf<Node?>(null) }
    var deleting by remember { mutableStateOf<Node?>(null) }
    var folderDialog by remember { mutableStateOf(false) }
    var combine by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var opened by remember { mutableStateOf<Set<String>>(emptySet()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onAdd) { Text("Добавить узел") }
            OutlinedButton({ folderName = ""; folderDialog = true }) { Text("Добавить папку") }
        }
        TextButton({ combine = true; picked = emptySet(); folderName = "Совмещённые узлы" }) { Text("+ Совместить узлы (до 25)") }
        if (data.nodes.isEmpty()) Text("Добавьте ссылку узла или подписку. Папки группируют узлы, не меняя их подключение.", style = MaterialTheme.typography.bodySmall)
        val draw: @Composable (Node) -> Unit = { node ->
            var expanded by remember(node.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(node.name, Modifier.weight(1f).clickable { expanded = !expanded }.padding(16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ editing = node }) { Icon(Icons.Rounded.Edit, "Редактировать ${node.name}") }
                    IconButton({ deleting = node }) { Icon(Icons.Rounded.Close, "Удалить ${node.name}") }
                }
                if (expanded) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("${node.protocol.uppercase()} · ${if (node.mark == Mark.NONE) "Обычный" else node.mark.name} · ${node.latencyMs?.let { "$it мс" } ?: "нет подтверждённого пинга"}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton({ onSelect(node) }, enabled = node.mark != Mark.BL) { Text(if (data.settings.selectedId == node.id) "Выбран" else "Выбрать") }
                        TextButton({ onCheck(node.id) }, enabled = !checking) { Text("Проверить") }
                    }
                }
            }
        }
        data.folders.forEach { folder ->
            val nodes = data.nodes.filter { it.folderId == folder.id }
            TextButton({ opened = if (folder.id in opened) opened - folder.id else opened + folder.id }, Modifier.fillMaxWidth()) {
                Text("${folder.name} · ${nodes.size} ${if (folder.id in opened) "▴" else "▾"}")
            }
            if (folder.id in opened) {
                TextButton({ onCheckGroup(nodes.map { it.id }.toSet()) }, enabled = !checking && nodes.isNotEmpty()) { Text("Проверить папку") }
                nodes.forEach { key(it.id) { draw(it) } }
            }
        }
        data.nodes.filter { node -> data.folders.none { it.id == node.folderId } }.forEach { key(it.id) { draw(it) } }
    }
    deleting?.let { node ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Ты точно хочешь удалить этот узел?") }, text = { Text(node.name) },
            confirmButton = { TextButton({ deleting = null; onDelete(node) }) { Text("Удалить") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Отмена") } })
    }
    if (folderDialog || combine) AlertDialog(onDismissRequest = { folderDialog = false; combine = false },
        title = { Text(if (combine) "Совместить узлы (до 25)" else "Новая папка") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(folderName, { folderName = it }, label = { Text("Название") }, singleLine = true)
            if (combine) {
                Text("Группа для выбора и проверки. Не цепочка прокси и не сложение скоростей.", style = MaterialTheme.typography.bodySmall)
                Text("Выбрано ${picked.size} из 25")
                data.nodes.forEach { node ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(node.id in picked, { selected -> picked = if (selected) picked + node.id else picked - node.id }, enabled = node.id in picked || picked.size < 25)
                        Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } },
        confirmButton = { TextButton({
            if (combine) onCombine(picked, folderName) else onFolder(folderName)
            folderDialog = false; combine = false
        }, enabled = folderName.isNotBlank() && folderName.length <= 120 && (!combine || picked.size in 2..25)) { Text("Создать") } },
        dismissButton = { TextButton({ folderDialog = false; combine = false }) { Text("Отмена") } })
    editing?.let { node ->
        key(node.id) {
            var name by remember { mutableStateOf(node.name) }
            var source by remember { mutableStateOf(node.source.ifBlank { node.outbound }) }
            var mark by remember { mutableStateOf(node.mark) }
            var folder by remember { mutableStateOf(node.folderId) }
            var error by remember { mutableStateOf<String?>(null) }
            AlertDialog(onDismissRequest = { editing = null }, title = { Text("Редактировать узел") },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it; error = null }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(source, { source = it; error = null }, label = { Text("Ссылка или outbound JSON") }, maxLines = 5)
                    Text("Ссылка содержит секреты. Не передавайте её другим.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mark.entries.forEach { value -> FilterChip(mark == value, { mark = value }, label = { Text(if (value == Mark.NONE) "Обычный" else value.name.lowercase()) }) }
                    }
                    Text("Папка")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(folder == null, { folder = null }, label = { Text("Без папки") })
                        data.folders.forEach { f -> FilterChip(folder == f.id, { folder = f.id }, label = { Text(f.name) }) }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } },
                confirmButton = { TextButton({
                    runCatching {
                        require(name.isNotBlank() && name.length <= 120) { "Название: от 1 до 120 символов" }
                        require(Importer.parse(source, mark).size == 1) { "Нужен один узел" }
                    }.onSuccess { onEdit(node, name, source, mark, folder); editing = null }
                        .onFailure { error = it.message ?: "Проверьте данные" }
                }) { Text("Сохранить") } },
                dismissButton = { TextButton({ editing = null }) { Text("Отмена") } })
        }
    }
}
