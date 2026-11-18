package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.CheckStatus
import com.savemy.singboxfork.data.Node
import com.savemy.singboxfork.data.RoutingMode
import com.savemy.singboxfork.data.RoutingProfile
import com.savemy.singboxfork.data.normalizeRules

/**
 * Итоговые правила для конфига: ручной профиль пользователя плюс включённые пресеты.
 * side БС -> direct, side ЧС -> proxy; режим задаёт только final и способ фильтрации приложений.
 */
data class EffectiveRouting(
    val mode: RoutingMode = RoutingMode.WHITELIST_BYPASS,
    val directDomains: List<String> = emptyList(),
    val proxyDomains: List<String> = emptyList(),
    val blockDomains: List<String> = emptyList(),
    val directCidrs: List<String> = emptyList(),
    val directPackages: List<String> = emptyList(),
    val proxyPackages: List<String> = emptyList(),
    val blockPackages: List<String> = emptyList(),
    val torrentDirect: Boolean = false
) {
    val finalOutbound: String get() = if (mode == RoutingMode.WHITELIST_BYPASS) "proxy" else "direct"

    /** TUN-исключения: в БС всё, кроме Белого списка, идёт через VPN. */
    val excludedApps: List<String> get() = if (mode == RoutingMode.WHITELIST_BYPASS) directPackages else emptyList()

    /** Подмножество приложений TUN: в ЧС через VPN идут только правила Чёрного списка. */
    val includedApps: List<String> get() = if (mode == RoutingMode.WHITELIST_BYPASS) emptyList() else proxyPackages
}

/** Решение Smart Routing без побочных эффектов: что делать после неудачной проверки активного узла. */
object SmartRouter {
    /** Первый этап не считается подтверждением, если ядро не запустилось. */
    fun bestChecked(results: List<Node>, allowed: Set<String>, proxyConfirmed: Boolean): Node? =
        if (!proxyConfirmed) null else results.filter { it.id in allowed && it.status == CheckStatus.ALIVE && it.latencyMs != null }.minByOrNull { it.latencyMs!! }

    /**
     * null — активный узел работает или запасных нет.
     * Иначе — самый быстрый проверенный узел со статусом ALIVE.
     */
    fun bestFallback(active: Node?, alternatives: List<Node>): Node? {
        if (active?.latencyMs != null) return null
        return alternatives
            .filter { it.status == CheckStatus.ALIVE && it.latencyMs != null }
            .minByOrNull { it.latencyMs!! }
    }

    /**
     * Сводит ручной профиль, пресеты и ручные приложения в один набор правил.
     * installed пуст — фильтрация по установленным приложениям не применяется
     * (конфиг всё равно безопасен: правила для неустановленных пакетов не срабатывают).
     */
    fun effective(
        profile: RoutingProfile,
        mode: RoutingMode,
        presetIds: Set<String>,
        installed: Set<String> = emptySet(),
        extraDirectApps: Set<String> = emptySet(),
        extraProxyApps: Set<String> = emptySet()
    ): EffectiveRouting {
        val whitePackages = PresetManager.packages(presetIds, PresetSide.WHITELIST)
        val blackPackages = PresetManager.packages(presetIds, PresetSide.BLACKLIST)
        fun keepPreset(packages: List<String>) =
            if (installed.isEmpty()) packages else packages.filter { it in installed }
        val directApps = normalizeRules(profile.directPackages + keepPreset(whitePackages) + extraDirectApps)
        val proxyApps = normalizeRules(profile.proxyPackages + keepPreset(blackPackages) + extraProxyApps)
        return EffectiveRouting(
            mode = mode,
            directDomains = normalizeRules(profile.directDomains + PresetManager.domains(presetIds, PresetSide.WHITELIST)),
            proxyDomains = normalizeRules(profile.proxyDomains + PresetManager.domains(presetIds, PresetSide.BLACKLIST)),
            blockDomains = normalizeRules(profile.blockDomains),
            directCidrs = (PresetManager.normalizedCidrs(profile.directCidrs) + PresetManager.cidrs(presetIds)).distinct(),
            directPackages = directApps,
            proxyPackages = proxyApps.filterNot { it in directApps },
            blockPackages = normalizeRules(profile.blockPackages),
            torrentDirect = PresetManager.torrentDirect(presetIds)
        )
    }
}
