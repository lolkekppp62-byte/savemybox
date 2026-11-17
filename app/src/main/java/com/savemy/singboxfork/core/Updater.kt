package com.savemy.singboxfork.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.savemy.singboxfork.data.ReleaseInfo
import com.savemy.singboxfork.data.UpdateChannel
import com.savemy.singboxfork.data.UpdateLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Состояние проверки обновления для экрана настроек. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateState
    data class Ready(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Обновление из GitHub Releases без браузера: проверка, скачивание APK и системный установщик.
 * Подпись сборок постоянная (app/debug.keystore), поэтому APK ставится поверх текущей версии.
 */
class Updater(private val context: Context, private val currentVersion: String) {
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun check(channel: UpdateChannel) {
        if (mutable.value is UpdateState.Checking || mutable.value is UpdateState.Downloading) return
        mutable.value = UpdateState.Checking
        scope.launch {
            mutable.value = try {
                // readBytes, а не readNBytes: последний появился в Android только с API 33.
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

    fun install(release: ReleaseInfo) {
        if (mutable.value is UpdateState.Downloading) return
        mutable.value = UpdateState.Downloading(release, 0)
        scope.launch {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "SaveMyBox-${release.version}.apk")
                if (!apk.isFile || (release.sizeBytes > 0 && apk.length() != release.sizeBytes)) download(release, apk)
                mutable.value = UpdateState.Ready(release)
                openInstaller(apk)
            } catch (e: Exception) {
                mutable.value = UpdateState.Failed("Не удалось установить обновление: ${Diagnostics.describe(e)}")
            }
        }
    }

    /** Скачивание с прогрессом: неполный файл переименовывается только после сверки размера. */
    private fun download(release: ReleaseInfo, target: File) {
        val part = File(target.path + ".part")
        part.delete()
        request(release.apkUrl).use { input ->
            val total = release.sizeBytes
            var read = 0L
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    read += count
                    require(read <= MAX_APK) { "Файл обновления слишком большой" }
                    output.write(buffer, 0, count)
                    if (total > 0) mutable.value = UpdateState.Downloading(release, ((read * 100) / total).toInt().coerceIn(0, 100))
                }
            }
            require(total <= 0 || read == total) { "Файл скачался не полностью" }
        }
        require(part.length() > 0) { "Пустой файл обновления" }
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
    }

    private fun openInstaller(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // Разрешение «Установка неизвестных приложений» выдаётся только системными настройками.
            mutable.value = UpdateState.Failed("Разрешите установку приложений для SaveMyBox и нажмите «Обновить» ещё раз.")
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
        const val MAX_APK = 400L * 1024 * 1024
    }
}
