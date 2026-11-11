package com.savemy.singboxfork.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.savemy.singboxfork.data.Settings
import com.savemy.singboxfork.data.Subscription
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newSubName by remember { mutableStateOf("") }
    var newSubUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить подписку")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Подписки",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Lines 68-98: обработка и мутация подписок с корректными it.copy(...)
            // fix: начало блока управления подписками
            items(
                items = settings.subscriptions,
                key = { it.id }
            ) { subscription ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = subscription.name.ifEmpty { "Без названия" },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = subscription.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = subscription.enabled,
                            onCheckedChange = { isChecked ->
                                onSettingsChange { current ->
                                    current.copy( // fix: корректный вызов copy
                                        subscriptions = current.subscriptions.map { sub ->
                                            if (sub.id == subscription.id) sub.copy(enabled = isChecked) else sub
                                        }
                                    )
                                }
                            }
                        )

                        IconButton(
                            onClick = {
                                onSettingsChange { current ->
                                    current.copy( // fix: корректный вызов copy при удалении
                                        subscriptions = current.subscriptions.filter { it.id != subscription.id }
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить")
                        }
                    }
                }
            } // fix: конец блока

            if (settings.subscriptions.isEmpty()) {
                item {
                    Text(
                        text = "Список подписок пуст. Нажмите +, чтобы добавить.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Сетевые параметры",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Обход LAN (Bypass LAN)")
                    Switch(
                        checked = settings.bypassLan,
                        onCheckedChange = { isChecked ->
                            onSettingsChange { it.copy(bypassLan = isChecked) }
                        }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Разрешать небезопасные соединения")
                    Switch(
                        checked = settings.allowInsecure,
                        onCheckedChange = { isChecked ->
                            onSettingsChange { it.copy(allowInsecure = isChecked) }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Новая подписка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newSubName,
                        onValueChange = { newSubName = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newSubUrl,
                        onValueChange = { newSubUrl = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newSubUrl.isNotBlank()) {
                            onSettingsChange { current ->
                                current.copy( // fix: корректное добавление новой подписки
                                    subscriptions = current.subscriptions + Subscription(
                                        id = UUID.randomUUID().toString(),
                                        name = newSubName.trim(),
                                        url = newSubUrl.trim(),
                                        enabled = true,
                                        lastUpdated = System.currentTimeMillis()
                                    )
                                )
                            }
                            newSubName = ""
                            newSubUrl = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}