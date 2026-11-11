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
    suspend fun add(nodes: List<Node>) = change { it.copy(nodes = it.nodes + nodes) }
    suspend fun mark(id: String, mark: Mark) = change { d -> d.copy(nodes = d.nodes.map { if(it.id == id) it.copy(mark=mark) else it }) }
    suspend fun remove(id: String) = change { it.copy(nodes = it.nodes.filterNot { n -> n.id == id }) }
    // Результаты проверки не восстанавливают удалённый узел и не перезаписывают свежую отметку BL/WL.
    suspend fun results(nodes: List<Node>) = change { d ->
        val updates = nodes.associateBy { it.id }
        d.copy(nodes = d.nodes.map { old -> updates[old.id]?.let { old.copy(latencyMs=it.latencyMs,lossPercent=it.lossPercent,checkedAt=it.checkedAt) } ?: old })
    }
    /** Обновляет список узлов подписки: удаляет старые узлы этой подписки, добавляет свежие. */
    suspend fun addFromSubscription(subId: String, fresh: List<Node>) = change { d ->
        val tagged = fresh.map { it.copy(id = "$subId::${it.name.take(80)}") }
        val existing = d.nodes.filterNot { it.id.startsWith("$subId::") }
        d.copy(nodes = existing + tagged)
    }
}
