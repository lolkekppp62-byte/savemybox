package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.*
import kotlinx.serialization.json.*
import java.io.File

/** Полный вход генератора: данные, параметры запуска, профиль маршрутизации и продвинутый JSON. */
data class CoreSpec(
    val data: AppData,
    val vpn: Boolean,
    val port: Int,
    val secret: String,
    val logPath: String,
    val effectiveMtu: Int = data.settings.mtu,
    val packageRules: Boolean = true,
    val ruleSetsDir: String? = null,
    val advancedJson: String? = null,
    /** Установленные на телефоне приложения: из паков подтягиваются только они. */
    val installedPackages: Set<String> = emptySet()
)

object ConfigFactory {
    /** Базовый режим без rule-set и без продвинутого JSON. */
    fun create(data: AppData, vpn: Boolean, port: Int, secret: String, logPath: String, effectiveMtu: Int = data.settings.mtu, packageRules: Boolean = true): String =
        build(CoreSpec(data, vpn, port, secret, logPath, effectiveMtu, packageRules))

    fun build(spec: CoreSpec): String {
        val data = spec.data
        val s = data.settings
        // Встроенные пресеты и ручные правила сводятся в один набор: БС -> direct, ЧС -> proxy.
        val routing = SmartRouter.effective(
            profile = s.routing,
            mode = s.routingMode,
            presetIds = s.presetIds,
            installed = spec.installedPackages,
            extraDirectApps = s.bypassPackages + if (s.russianServices) s.russianPackages else emptySet(),
            extraProxyApps = s.proxyApps
        )
        require(spec.effectiveMtu in 1280..8500)
        // id узла становится тегом outbound. Повтор тега или битый JSON одного узла
        // раньше ронял запуск ядра целиком (и проверка узлов падала без объяснения),
        // поэтому такие узлы отбрасываются поштучно.
        val tags = HashSet<String>(data.nodes.size * 2)
        val outbounds = data.nodes.mapNotNull { node ->
            if (node.id in tags || node.id in setOf("direct", "proxy")) return@mapNotNull null
            val obj = runCatching { codec.parseToJsonElement(node.outbound).jsonObject.toMutableMap() }.getOrNull()
                ?: return@mapNotNull null
            tags.add(node.id)
            obj["tag"] = JsonPrimitive(node.id)
            // Для TUIC режим stream передаёт UDP надёжным потоком QUIC, ценой задержек.
            if (node.protocol == "tuic" && s.packetRecovery) { obj["udp_relay_mode"] = JsonPrimitive("quic"); obj.remove("udp_over_stream") }
            JsonObject(obj)
        }
        val candidates = data.candidates(s.routingMode).filter { it.id in tags }
        if (spec.vpn) require(candidates.isNotEmpty()) { "Нет доступных для подключения узлов. Смените фильтр или уберите BL." }
        val selected = candidates.firstOrNull { it.id == s.selectedId } ?: candidates.firstOrNull()
        val outboundsFinal = outbounds + listOf(buildJsonObject { put("type", "direct"); put("tag", "direct") }, buildJsonObject {
            put("type", "selector"); put("tag", "proxy")
            put("outbounds", JsonArray((candidates.map { it.id }.ifEmpty { listOf("direct") }).map(::JsonPrimitive)))
            put("default", selected?.id ?: "direct"); put("interrupt_exist_connections", true)
        })
        // В БС исключения TUN идут мимо VPN; в ЧС список наоборот — только через VPN (addAllowedApplication).
        val excluded = routing.excludedApps
        val ruleSets = prepareRuleSets(s.routing.ruleSets, spec.ruleSetsDir)
        val base = buildJsonObject {
            put("log", buildJsonObject { put("level", "warn"); put("timestamp", true); put("output", spec.logPath) })
            put("dns", buildJsonObject {
                put("servers", buildJsonArray {
                    // IP-адрес bootstrap не требует разрешения имени. detour здесь указывать нельзя:
                    // в новой форме DNS-серверов sing-box 1.12+ собственный dialer и так равен пустому
                    // direct, а явный detour на direct ядро отвергает фатально — «detour to an empty
                    // direct outbound makes no sense» — и VPN не запускается вовсе.
                    add(buildJsonObject { put("type", "udp"); put("tag", "bootstrap"); put("server", "1.1.1.1") })
                    add(buildJsonObject { put("type", "https"); put("tag", "remote"); put("server", "1.1.1.1"); put("path", "/dns-query"); put("detour", "proxy") })
                }); put("final", "remote")
            })
            put("inbounds", buildJsonArray {
                if (spec.vpn) add(buildJsonObject {
                    put("type", "tun"); put("tag", "tun-in"); put("address", buildJsonArray { add("172.19.0.1/30"); add("fdfe:dcba:9876::1/126") })
                    put("mtu", spec.effectiveMtu); put("auto_route", true); put("strict_route", true); put("stack", "mixed")
                    put("exclude_package", JsonArray(excluded.map(::JsonPrimitive)))
                })
            })
            put("outbounds", JsonArray(outboundsFinal))
            put("route", buildJsonObject {
                put("auto_detect_interface", true); put("default_domain_resolver", "bootstrap"); put("final", routing.finalOutbound)
                if (ruleSets.isNotEmpty()) put("rule_set", buildJsonArray {
                    ruleSets.forEach { (set, path) -> add(buildJsonObject {
                        put("type", "local"); put("tag", set.tag); put("format", "binary"); put("path", path)
                    }) }
                })
                put("rules", buildJsonArray {
                    add(buildJsonObject { put("action", "sniff") })
                    add(buildJsonObject { put("protocol", "dns"); put("action", "hijack-dns") })
                    // Пакеты исключаются на уровне TUN; правило — дополнительная защита на Android 10+.
                    if (spec.packageRules && excluded.isNotEmpty()) add(buildJsonObject { put("package_name", JsonArray(excluded.map(::JsonPrimitive))); routeTo(RouteTarget.DIRECT) })
                    if (spec.packageRules && routing.blockPackages.isNotEmpty()) add(buildJsonObject { put("package_name", JsonArray(routing.blockPackages.map(::JsonPrimitive))); routeTo(RouteTarget.BLOCK) })
                    // ЧС: приложения чёрного списка идут через VPN, остальные в туннель не попадают.
                    if (spec.packageRules && routing.proxyPackages.isNotEmpty()) add(buildJsonObject { put("package_name", JsonArray(routing.proxyPackages.map(::JsonPrimitive))); routeTo(RouteTarget.PROXY) })
                    if (spec.packageRules && routing.directPackages.isNotEmpty()) add(buildJsonObject { put("package_name", JsonArray(routing.directPackages.map(::JsonPrimitive))); routeTo(RouteTarget.DIRECT) })
                    // Правила из .srs идут раньше пользовательских доменов: конкретный набор перекрывает общий суффикс.
                    ruleSets.forEach { (set, _) -> add(buildJsonObject { put("rule_set", set.tag); routeTo(set.target) }) }
                    if (routing.blockDomains.isNotEmpty()) add(buildJsonObject { put("domain_suffix", JsonArray(routing.blockDomains.map(::JsonPrimitive))); routeTo(RouteTarget.BLOCK) })
                    // Явный ЧС перекрывает общий доменный суффикс Белого списка (например, .ru).
                    if (routing.proxyDomains.isNotEmpty()) add(buildJsonObject { put("domain_suffix", JsonArray(routing.proxyDomains.map(::JsonPrimitive))); routeTo(RouteTarget.PROXY) })
                    if (routing.directDomains.isNotEmpty()) add(buildJsonObject { put("domain_suffix", JsonArray(routing.directDomains.map(::JsonPrimitive))); routeTo(RouteTarget.DIRECT) })
                    if (routing.torrentDirect) add(buildJsonObject { put("protocol", "bittorrent"); routeTo(RouteTarget.DIRECT) })
                    if (routing.directCidrs.isNotEmpty()) add(buildJsonObject { put("ip_cidr", JsonArray(routing.directCidrs.map(::JsonPrimitive))); routeTo(RouteTarget.DIRECT) })
                })
            })
            put("experimental", buildJsonObject { put("clash_api", buildJsonObject { put("external_controller", "127.0.0.1:${spec.port}"); put("secret", spec.secret) }) })
        }
        val advanced = spec.advancedJson?.takeIf { it.isNotBlank() }?.let(::advancedConfig)
        // База и продвинутый JSON — объекты, поэтому слияние всегда даёт объект.
        val config = if (advanced == null) base else merge(base, advanced) as JsonObject
        return withClashApi(config, spec.port, spec.secret).toString()
    }

    /** Отсутствующие файлы, пустые и повторные теги пропускаются: один битый .srs не ломает VPN. */
    private fun prepareRuleSets(sets: List<RuleSetFile>, dir: String?): List<Pair<RuleSetFile, String>> {
        if (dir == null || sets.isEmpty()) return emptyList()
        val root = File(dir).canonicalFile
        val seen = mutableSetOf<String>()
        return sets.mapNotNull { set ->
            val tag = set.tag.ifBlank { set.file.substringBeforeLast('.').take(64) }
            if (tag.isBlank() || !seen.add(tag)) return@mapNotNull null
            val file = File(root, set.file)
            if (!file.isFile) return@mapNotNull null
            // Только файлы внутри каталога rule-set: путь с ../ не должен подключать чужой файл.
            if (!file.canonicalPath.startsWith(root.path + File.separator)) return@mapNotNull null
            set.copy(tag = tag) to file.canonicalPath
        }
    }

    private fun advancedConfig(text: String): JsonObject {
        val element = runCatching { codec.parseToJsonElement(text) }
            .getOrElse { throw IllegalArgumentException("Продвинутый JSON не разобран: проверьте синтаксис") }
        return element as? JsonObject ?: throw IllegalArgumentException("Продвинутый JSON должен быть объектом")
    }

    /** Продвинутый режим накладывается поверх базы рекурсивно; массивы заменяются целиком. */
    private fun merge(base: JsonElement, override: JsonElement): JsonElement {
        if (base !is JsonObject || override !is JsonObject) return override
        val out = base.toMutableMap()
        override.forEach { (key, value) -> out[key] = if (out.containsKey(key)) merge(out.getValue(key), value) else value }
        return JsonObject(out)
    }

    /** Локальный API ядра всегда остаётся на loopback с секретом запуска, даже если JSON его переопределяет. */
    private fun withClashApi(config: JsonObject, port: Int, secret: String): JsonObject {
        val experimental = (config["experimental"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        experimental["clash_api"] = buildJsonObject {
            put("external_controller", "127.0.0.1:$port"); put("secret", secret)
        }
        val out = config.toMutableMap()
        out["experimental"] = JsonObject(experimental)
        return JsonObject(out)
    }

    private fun JsonObjectBuilder.routeTo(target: RouteTarget) {
        when (target) {
            RouteTarget.BLOCK -> put("action", "reject")
            else -> { put("action", "route"); put("outbound", if (target == RouteTarget.DIRECT) "direct" else "proxy") }
        }
    }
}
