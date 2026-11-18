package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.normalizeRules

/** Сторона списка: БС идёт напрямую, ЧС — через VPN. */
enum class PresetSide { WHITELIST, BLACKLIST }

/**
 * Тематический пак правил: домены, подсети и известные Android-пакеты.
 * torrent — P2P/BitTorrent в обход VPN (только для локального пака).
 */
data class FvPreset(
    val id: String,
    val title: String,
    val note: String,
    val side: PresetSide,
    val domains: List<String> = emptyList(),
    val cidrs: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val torrent: Boolean = false
) {
    val domainCount: Int get() = domains.size + cidrs.size
}

/**
 * Встроенные пресеты. Пакеты — известные имена приложений;
 * реально установленные из них подтягиваются автоматически через [installedApps].
 */
object PresetManager {
    const val GOV = "rf-gov"
    const val BANKS = "ru-banks"
    const val MARKET = "ru-market"
    const val MEDIA = "ru-media"
    const val LAN = "lan-p2p"
    const val AI = "ai"
    const val YOUTUBE = "youtube"
    const val DISCORD = "discord"
    const val SOCIAL = "social"
    const val GAMES = "games"

    /** Базовые РФ-паки Белого списка включены по умолчанию. */
    val defaultIds: Set<String> = setOf(GOV, BANKS, MARKET, MEDIA, LAN)

    val presets: List<FvPreset> = listOf(
        FvPreset(
            id = GOV, title = "РФ и Госсервисы", note = "Домены .ru/.рф, Госуслуги, ФНС, СБП", side = PresetSide.WHITELIST,
            domains = listOf("ru", "рф", "gosuslugi.ru", "nalog.gov.ru", "nalog.ru", "sfr.gov.ru", "mos.ru", "sbp.nspk.ru", "nspk.ru", "mvd.ru"),
            packages = listOf("ru.rostel", "ru.fns.lk", "ru.nspk.mirpay", "ru.mos.app")
        ),
        FvPreset(
            id = BANKS, title = "Банки РФ", note = "Сбер, Т-Банк, Альфа, ВТБ, Газпромбанк, Яндекс Пэй", side = PresetSide.WHITELIST,
            domains = listOf("sberbank.ru", "online.sberbank.ru", "tinkoff.ru", "tbank.ru", "alfabank.ru", "vtb.ru", "gazprombank.ru", "raiffeisen.ru", "sovcombank.ru", "ozonbank.ru", "pay.yandex.ru", "yoomoney.ru"),
            packages = listOf("ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.alfabank.mobile.android", "ru.vtb24.mobilebanking.android", "ru.gazprombank.android.mobilebank", "ru.raiffeisennews", "com.sovcombank.mobile", "ru.ozon.fintech.mobile", "ru.yandex.money")
        ),
        FvPreset(
            id = MARKET, title = "Маркетплейсы и доставка", note = "Ozon, WB, Яндекс Маркет, Авито, Яндекс Go, Самокат, Купер", side = PresetSide.WHITELIST,
            domains = listOf("ozon.ru", "wildberries.ru", "market.yandex.ru", "avito.ru", "megamarket.ru", "go.yandex.ru", "taxi.yandex.ru", "samokat.ru", "kuper.ru"),
            packages = listOf("ru.ozon.app.android", "com.wildberries.ru", "ru.beru.android", "com.avito.android", "ru.yandex.taxi", "ru.samokat.app", "ru.kuper.app", "ru.megamarket")
        ),
        FvPreset(
            id = MEDIA, title = "Стриминг и соцсети РФ", note = "ВК, Кинопоиск, Яндекс Музыка, Иви, Okko, Wink, Rutube", side = PresetSide.WHITELIST,
            domains = listOf("vk.com", "vk.ru", "kinopoisk.ru", "music.yandex.ru", "ivi.ru", "okko.tv", "wink.ru", "rutube.ru"),
            packages = listOf("com.vkontakte.android", "ru.yandex.music", "ru.kinopoisk", "ru.ivi.client", "ru.okko.android", "ru.wink", "ru.rutube.app")
        ),
        FvPreset(
            id = LAN, title = "Локальная сеть и P2P", note = "RFC1918, link-local и торренты идут напрямую", side = PresetSide.WHITELIST,
            domains = listOf("local", "lan", "home.arpa"),
            cidrs = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "224.0.0.0/4", "::1/128", "fc00::/7", "fe80::/10"),
            torrent = true
        ),
        FvPreset(
            id = AI, title = "Нейросети (AI)", note = "Gemini, AI Studio, ChatGPT, Claude, Perplexity, Grok", side = PresetSide.BLACKLIST,
            domains = listOf("gemini.google.com", "generativelanguage.googleapis.com", "aistudio.google.com", "openai.com", "chatgpt.com", "chat.openai.com", "api.openai.com", "claude.ai", "anthropic.com", "perplexity.ai", "midjourney.com", "grok.com", "x.ai", "copilot.microsoft.com"),
            packages = listOf("com.google.android.apps.bard", "com.openai.chatgpt", "com.anthropic.claude", "ai.perplexity.app.android")
        ),
        FvPreset(
            id = YOUTUBE, title = "YouTube и видео", note = "YouTube, googlevideo, ReVanced и клиенты", side = PresetSide.BLACKLIST,
            domains = listOf("youtube.com", "youtu.be", "yt.be", "googlevideo.com", "ytimg.com", "ggpht.com", "youtube-nocookie.com"),
            packages = listOf("com.google.android.youtube", "app.revanced.android.youtube", "app.revanced.android.apps.youtube.music", "com.google.android.apps.youtube.music", "org.schabi.newpipe")
        ),
        FvPreset(
            id = DISCORD, title = "Discord (текст и голос)", note = "discord.com, .gg, media, голосовой шлюз", side = PresetSide.BLACKLIST,
            domains = listOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media", "discordstatus.com"),
            packages = listOf("com.discord")
        ),
        FvPreset(
            id = SOCIAL, title = "Зарубежные соцсети", note = "Instagram, X/Twitter, Facebook, Threads, TikTok", side = PresetSide.BLACKLIST,
            domains = listOf("instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "threads.net", "threads.com", "x.com", "twitter.com", "t.co", "twimg.com", "tiktok.com", "tiktokcdn.com"),
            packages = listOf("com.instagram.android", "com.twitter.android", "com.facebook.katana", "com.facebook.orca", "com.zhiliaoapp.musically", "com.instagram.barcelona")
        ),
        FvPreset(
            id = GAMES, title = "Игры и лаунчеры", note = "Brawl Stars, Roblox, Steam, Epic Games, Riot Games", side = PresetSide.BLACKLIST,
            domains = listOf("supercell.com", "roblox.com", "roblox.org", "steampowered.com", "steamcommunity.com", "epicgames.com", "unrealengine.com", "riotgames.com", "leagueoflegends.com", "playvalorant.com"),
            packages = listOf("com.supercell.brawlstars", "com.roblox.client", "com.valvesoftware.android.steam.community", "com.epicgames.portal", "com.riotgames.league.wildrift")
        )
    )

    fun byId(id: String): FvPreset? = presets.firstOrNull { it.id == id }

    fun bySide(side: PresetSide): List<FvPreset> = presets.filter { it.side == side }

    fun selected(ids: Set<String>, side: PresetSide): List<FvPreset> = bySide(side).filter { it.id in ids }

    /** Включённые имена пакетов стороны; пресеты без пакетов ничего не добавляют. */
    fun packages(ids: Set<String>, side: PresetSide): List<String> =
        selected(ids, side).flatMap { it.packages }.distinct()

    fun domains(ids: Set<String>, side: PresetSide): List<String> =
        normalizeRules(selected(ids, side).flatMap { it.domains })

    fun cidrs(ids: Set<String>): List<String> =
        normalizedCidrs(selected(ids, PresetSide.WHITELIST).flatMap { it.cidrs })

    fun torrentDirect(ids: Set<String>): Boolean = selected(ids, PresetSide.WHITELIST).any { it.torrent }

    /** Пересечение пакетов пака с установленными на телефоне приложениями. */
    fun installedApps(preset: FvPreset, installed: Collection<String>): List<String> =
        preset.packages.filter { it in installed }.sorted()

    /** Установленные приложения всех включённых паков стороны. */
    fun installedApps(ids: Set<String>, side: PresetSide, installed: Collection<String>): List<String> =
        if (installed.isEmpty()) emptyList()
        else packages(ids, side).filter { it in installed }.sorted()

    /** Подсети приводятся к виду ip/маска; мусорные строки не попадают в конфиг. */
    fun normalizedCidrs(values: Collection<String>): List<String> =
        values.map { it.trim() }.filter { it.matches(Regex("[0-9a-fA-F:.]+/[0-9]{1,3}")) }.distinct()
}
