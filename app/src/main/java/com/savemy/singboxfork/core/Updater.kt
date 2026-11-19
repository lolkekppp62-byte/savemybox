package com.savemy.singboxfork.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.savemy.singboxfork.data.ReleaseInfo
import com.savemy.singboxfork.data.UpdateChannel
import com.savemy.singboxfork.data.UpdateLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Состояние проверки / скачивания обновления для экрана настроек. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    /** Скачивание: [progress] 0–100, null — размер неизвестен (неопределённый прогресс). */
    data class Downloading(val release: ReleaseInfo, val progress: Int?) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Обновление из GitHub Releases: проверка версии, скачивание APK во внутренний кэш
 * и запуск системного установщика через FileProvider (content:// URI).
 *
 * REQUEST_INSTALL_PACKAGES нужен только для запуска системного диалога «Установить»;
 * само скачивание и хранение — внутри кэша приложения, сторонние хранилища не нужны.
 */
class Updater(private val context: Context, private val currentVersion: String) {
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    fun check(channel: UpdateChannel) {
        if (mutable.value is UpdateState.Checking) return
        mutable.value = UpdateState.Checking
        scope.launch {
            mutable.value = try {
                val payload = request(RELEASES_URL).use { input ->
                    val bytes = input.readBytes()
                    require(bytes.size <= MAX_PAYLOAD) { "Ответ GitHub слишком большой" }
                    bytes.decodeToString()
                }
                val release = UpdateLogic.available(UpdateLogic.parseReleases(payload), channel, currentVersion)
                if (release == null) UpdateState.UpToDate(currentVersion) else UpdateState.Available(release)
            } catch (e: Exception) {
                UpdateState.Failed("Не удалось проверить обновления: ${Diagnostics.describe(e)}")
            }
        }
    }

    /**
     * Скачивает APK во внутренний кэш и запускает системный установщик.
     * Прогресс (0–100) передаётся через [UpdateState.Downloading].
     * Если [ReleaseInfo.apkUrl] пустой — открывает страницу релиза в браузере как запасной вариант.
     */
    fun download(release: ReleaseInfo) {
        if (release.apkUrl.isBlank()) {
            openInBrowser(RELEASES_PAGE)
            return
        }
        if (mutable.value is UpdateState.Downloading) return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            mutable.value = UpdateState.Downloading(release, null)
            try {
                val dir = File(context.cacheDir, "updates").also { it.mkdirs() }
                // Имя файла из URL: безопасно — берём только последний сегмент пути.
                val fileName = release.apkUrl.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "update-${release.version}.apk" }
                val apkFile = File(dir, fileName)

                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "SaveMyBox/$currentVersion")
                }
                val total = connection.contentLengthLong // -1 если неизвестно

                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L
                        var lastReported = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            downloaded += n
                            val pct = if (total > 0) (downloaded * 100 / total).toInt() else null
                            // Обновляем UI не чаще чем каждый процент.
                            if (pct != null && pct != lastReported) {
                                lastReported = pct
                                mutable.value = UpdateState.Downloading(release, pct)
                            }
                        }
                    }
                }

                // Запускаем системный установщик через FileProvider (content:// URI обязателен на API 24+).
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                // После запуска установщика возвращаемся в состояние Available:
                // пользователь может отменить установку и захотеть попробовать снова.
                mutable.value = UpdateState.Available(release)
            } catch (e: Exception) {
                mutable.value = UpdateState.Failed("Не удалось скачать обновление: ${Diagnostics.describe(e)}")
            }
        }
    }

    /** Открывает страницу релизов в браузере как запасной вариант. */
    fun openInBrowser(url: String = RELEASES_PAGE) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            mutable.value = UpdateState.Failed("Не удалось открыть браузер: ${Diagnostics.describe(it)}")
        }
    }

    private fun request(url: String): java.io.InputStream {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SaveMyBox/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val code = connection.responseCode
        require(code in 200..299) { "GitHub ответил $code" }
        return connection.inputStream
    }

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/lolkekppp62-byte/savemybox/releases?per_page=30"
        const val MAX_PAYLOAD = 2_000_000
        const val RELEASES_PAGE = "https://github.com/lolkekppp62-byte/savemybox/releases"
    }
}
