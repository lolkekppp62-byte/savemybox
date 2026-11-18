package com.savemy.singboxfork.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.savemy.singboxfork.data.ReleaseInfo
import com.savemy.singboxfork.data.UpdateChannel
import com.savemy.singboxfork.data.UpdateLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/** Состояние проверки обновления для экрана настроек. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Обновление из GitHub Releases: проверка версии и переход на страницу выпуска.
 * APK скачивает и ставит браузер, поэтому приложению не нужно REQUEST_INSTALL_PACKAGES:
 * право устанавливать пакеты только ради самообновления выглядит для Play Protect как
 * установщик и вызывает предупреждение при установке.
 */
class Updater(private val context: Context, private val currentVersion: String) {
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun check(channel: UpdateChannel) {
        if (mutable.value is UpdateState.Checking) return
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

    /** Открывает ссылку на APK в браузере: скачивание и установку делает браузер. */
    fun open(release: ReleaseInfo) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl.ifBlank { RELEASES_PAGE }))
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
