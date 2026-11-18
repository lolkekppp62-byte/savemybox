package com.savemy.singboxfork.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Секреты узлов хранятся только в закрытом каталоге и шифруются ключом Android Keystore. */
class Repository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "profiles.enc"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(AppData())
    val state = mutable.asStateFlow()
    val loadError = MutableStateFlow<String?>(null)
    private var loaded = false
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("profiles", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("profiles", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock {
        if (loaded) return@withLock
        try {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val bytes = file.readFully()
                require(bytes.size > 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
                mutable.value = codec.decodeFromString<AppData>(cipher.doFinal(bytes.copyOfRange(12,bytes.size)).decodeToString())
            }
            loaded = true
        } catch (_: Exception) { loadError.value = "Не удалось прочитать хранилище. Исходный файл сохранён; запись заблокирована." }
    } }
    private suspend fun change(transform: (AppData) -> AppData) = withContext(Dispatchers.IO) { mutex.withLock {
        check(loaded && loadError.value == null) { "Хранилище недоступно" }
        val next = transform(mutable.value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(codec.encodeToString(next).encodeToByteArray())
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = next
    } }
    suspend fun updateSettings(transform: (Settings) -> Settings) = change { it.copy(settings = transform(it.settings)) }
    suspend fun add(nodes: List<Node>) = change { d ->
        // Пул ограничен: список и проверка не должны разрастаться бесконечно.
        // id — тег outbound у ядра, поэтому повторы не допускаются и здесь.
        val used = d.nodes.mapTo(HashSet(d.nodes.size * 2)) { it.id }
        val usedNames = d.nodes.mapTo(HashSet()) { it.name }
        val fresh = nodes.map { node ->
            var id = node.id
            var index = 1
            while (!used.add(id)) { index++; id = "${node.id}#$index" }
            val name = if (node.name.startsWith("Свой узел #")) {
                var number = 1
                while ("Свой узел #$number" in usedNames) number++
                "Свой узел #$number"
            } else node.name
            usedNames.add(name)
            node.copy(id = id, name = name)
        }
        d.copy(nodes = SubscriptionManager.capPool(d.nodes + fresh))
    }
    suspend fun editNode(id: String, name: String, source: String, mark: Mark, folderId: String?) = change { d ->
        require(name.trim().isNotEmpty() && name.length <= 120) { "Название: от 1 до 120 символов" }
        require(folderId == null || d.folders.any { it.id == folderId }) { "Папка удалена" }
        val old = d.nodes.firstOrNull { it.id == id } ?: error("Узел удалён")
        val next = if (source.trim() == old.source.ifBlank { old.outbound }) old
            else Importer.parse(source, mark).also { require(it.size == 1) { "Вставьте ссылку одного узла" } }.single()
        d.copy(nodes = d.nodes.map { if (it.id == id) next.copy(id = id, name = name.trim(), source = source.trim(), mark = mark, folderId = folderId, locallyEdited = true) else it })
    }
    suspend fun removeSubscription(id: String) = change { d ->
        val remaining = d.nodes.filterNot { it.id.startsWith("$id::") }
        d.copy(nodes = remaining, settings = d.settings.copy(
            subscriptions = d.settings.subscriptions.filterNot { it.id == id },
            selectedId = d.settings.selectedId?.takeIf { selected -> remaining.any { it.id == selected } }))
    }
    suspend fun addFolder(name: String) = change { d ->
        require(name.trim().isNotEmpty() && name.length <= 120) { "Введите название папки (до 120 символов)" }
        d.copy(folders = d.folders + NodeFolder(name = name.trim()))
    }
    /**
     * «Совместить»: до 25 ссылок за раз в отдельную папку — узлы не нужно добавлять по одному.
     * Это группировка для выбора и проверки, а не цепочка прокси и не сложение скоростей.
     */
    suspend fun combine(links: String, name: String, mark: Mark = Mark.NONE) = change { d ->
        require(name.trim().isNotEmpty() && name.length <= 120) { "Введите название группы (до 120 символов)" }
        val rows = links.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(rows.size in 1..25) { "Вставьте от 1 до 25 ссылок, каждая с новой строки" }
        val parsed = Importer.parse(rows.joinToString("\n"), mark)
        require(parsed.isNotEmpty()) { "В ссылках не найдено ни одного узла" }
        val folder = NodeFolder(name = name.trim())
        val used = d.nodes.mapTo(HashSet()) { it.id }
        val fresh = parsed.map { node ->
            var id = node.id
            var index = 1
            while (!used.add(id)) { index++; id = "${node.id}#$index" }
            node.copy(id = id, folderId = folder.id)
        }
        d.copy(folders = d.folders + folder, nodes = SubscriptionManager.capPool(d.nodes + fresh))
    }
    suspend fun mark(id: String, mark: Mark) = change { d -> d.copy(nodes = d.nodes.map { if(it.id == id) it.copy(mark=mark) else it }) }
    suspend fun remove(id: String) = change { it.copy(nodes = it.nodes.filterNot { n -> n.id == id }) }
    /** Массовое удаление из мультивыбора: отсутствующие id просто игнорируются. */
    suspend fun removeAll(ids: Collection<String>) = change { d ->
        val doomed = ids.toSet()
        d.copy(nodes = d.nodes.filterNot { it.id in doomed })
    }
    suspend fun mark(mark: Mark, ids: Collection<String>) = change { d ->
        val chosen = ids.toSet()
        d.copy(nodes = d.nodes.map { if (it.id in chosen) it.copy(mark = mark) else it })
    }
    // Результаты проверки не восстанавливают удалённый узел и не перезаписывают свежую отметку BL/WL.
    suspend fun results(nodes: List<Node>) = change { d ->
        val updates = nodes.associateBy { it.id }
        d.copy(nodes = d.nodes.map { old -> updates[old.id]?.let { old.copy(tcpMs=it.tcpMs,latencyMs=it.latencyMs,lossPercent=it.lossPercent,status=it.status,checkedAt=it.checkedAt) } ?: old })
    }
    /** Обновляет список узлов подписки: удаляет старые узлы этой подписки, добавляет свежие. */
    suspend fun addFromSubscription(subId: String, fresh: List<Node>, sourceUrl: String? = null) = change { d ->
        if (d.settings.subscriptions.none { it.id == subId }) return@change d
        val previous = d.nodes.filter { it.id.startsWith("$subId::") }
        // Старые записи не хранили ссылку: для бокса с одной ссылкой заменяем их при первом обновлении.
        val legacy = sourceUrl != null && d.settings.subscriptions.first { it.id == subId }.sources() == listOf(sourceUrl)
        val retained = if (sourceUrl == null) emptyList() else previous.filter { it.sourceUrl != sourceUrl && !(legacy && it.sourceUrl == null) }
        val replaced = if (sourceUrl == null) previous else previous.filter { it.sourceUrl == sourceUrl || (legacy && it.sourceUrl == null) }
        // Сохраняем идентификаторы и правки узлов, не затрагивая остальные ссылки бокса.
        val incoming = SubscriptionManager.distinct(fresh)
        val tagged = SubscriptionManager.reconcile(replaced, subscriptionNodes(subId, incoming))
        val existing = d.nodes.filterNot { it.id.startsWith("$subId::") }
        val used = (existing + retained).mapTo(HashSet()) { it.id }
        val unique = tagged.map { node ->
            var id = node.id
            var index = 1
            while (!used.add(id)) { index++; id = "${node.id}#$index" }
            node.copy(id = id, sourceUrl = sourceUrl ?: node.sourceUrl)
        }
        d.copy(nodes = SubscriptionManager.capPool(existing + retained + unique))
    }
}
