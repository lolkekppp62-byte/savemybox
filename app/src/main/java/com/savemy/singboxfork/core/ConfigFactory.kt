package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.*
import kotlinx.serialization.json.*

object ConfigFactory {
    fun create(data: AppData, vpn: Boolean, port: Int, secret: String, logPath: String, effectiveMtu: Int = data.settings.mtu, packageRules: Boolean = true): String {
        val s=data.settings
        require(effectiveMtu in 1280..8500)
        val candidates=data.candidates()
        if(vpn) require(candidates.isNotEmpty()) { "Нет доступных для подключения узлов. Смените фильтр или уберите BL." }
        val selected=candidates.firstOrNull { it.id==s.selectedId } ?: candidates.firstOrNull()
        val outbounds=data.nodes.map { node ->
            val obj=codec.parseToJsonElement(node.outbound).jsonObject.toMutableMap()
            obj["tag"]=JsonPrimitive(node.id)
            // Для TUIC режим stream передаёт UDP надёжным потоком QUIC, ценой задержек.
            if(node.protocol=="tuic" && s.packetRecovery) { obj["udp_relay_mode"]=JsonPrimitive("quic"); obj.remove("udp_over_stream") }
            JsonObject(obj)
        } + listOf(buildJsonObject { put("type","direct"); put("tag","direct") }, buildJsonObject {
            put("type","selector"); put("tag","proxy")
            put("outbounds",JsonArray((candidates.map { it.id }.ifEmpty { listOf("direct") }).map(::JsonPrimitive)))
            put("default",selected?.id ?: "direct"); put("interrupt_exist_connections",true)
        })
        val excluded=s.bypassPackages + if(s.russianServices) s.russianPackages else emptySet()
        return buildJsonObject {
            put("log",buildJsonObject { put("level","warn"); put("timestamp",true); put("output",logPath) })
            put("dns",buildJsonObject {
                put("servers",buildJsonArray {
                    add(buildJsonObject { put("type","udp"); put("tag","bootstrap"); put("server","1.1.1.1"); put("detour","direct") })
                    add(buildJsonObject { put("type","https"); put("tag","remote"); put("server","1.1.1.1"); put("path","/dns-query"); put("detour","proxy") })
                }); put("final","remote")
            })
            put("inbounds",buildJsonArray {
                if(vpn) add(buildJsonObject {
                    put("type","tun"); put("tag","tun-in"); put("address",buildJsonArray { add("172.19.0.1/30"); add("fdfe:dcba:9876::1/126") })
                    put("mtu",effectiveMtu); put("auto_route",true); put("strict_route",true); put("stack","mixed")
                    put("exclude_package",JsonArray(excluded.map(::JsonPrimitive)))
                })
            })
            put("outbounds",JsonArray(outbounds))
            put("route",buildJsonObject {
                put("auto_detect_interface",true); put("default_domain_resolver","bootstrap"); put("final","proxy")
                put("rules",buildJsonArray {
                    add(buildJsonObject { put("action","sniff") })
                    add(buildJsonObject { put("protocol","dns"); put("action","hijack-dns") })
                    // Пакеты исключаются на уровне TUN; правило — дополнительная защита на Android 10+.
                    if(packageRules && excluded.isNotEmpty()) add(buildJsonObject { put("package_name",JsonArray(excluded.map(::JsonPrimitive))); put("action","route"); put("outbound","direct") })
                })
            })
            put("experimental",buildJsonObject { put("clash_api",buildJsonObject { put("external_controller","127.0.0.1:$port"); put("secret",secret) }) })
        }.toString()
    }
}
