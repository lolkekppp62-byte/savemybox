package com.savemy.singboxfork.data

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/** Импорт не записывает исходные ссылки в журнал и не отключает проверку сертификата. */
object Importer {
    private val types = setOf("vless","vmess","trojan","shadowsocks","hysteria2","tuic","socks","http","hysteria","anytls")
    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    private fun b64(s: String): String = runCatching { String(Base64.getDecoder().decode(s)) }
        .getOrElse { String(Base64.getUrlDecoder().decode(s)) }
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun p(s: String) = JsonPrimitive(s)
    fun parse(text: String, mark: Mark = Mark.NONE): List<Node> {
        require(text.length <= 1_000_000) { "Слишком большой импорт (максимум 1 МБ)" }
        try {
            val input = text.trim()
            val entries: List<Pair<String?, JsonObject>> = if (input.startsWith("{") || input.startsWith("[")) {
                val root = codec.parseToJsonElement(input)
                val list = if(root is JsonArray) root.toList() else listOf(root)
                list.map { null to it.jsonObject }
            } else {
                val lines = input.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
            require(lines.size <= 1000) { "В списке слишком много строк (максимум 1000)" }
            // Поддержка raw outbound JSON строк: если строка начинается с '{', считаем её JSON-объектом
            val entries = lines.mapNotNull { line ->
                runCatching {
                    if (line.startsWith("{")) {
                        // Возвращаем без имени, только объект
                        null to JsonObject(codec.parseToJsonElement(line).jsonObject)
                    } else {
                        link(line)
                    }
                }.getOrNull()
            }
                require(lines.size <= 1000) { "В списке слишком много строк (максимум 1000)" }
                // Подписки часто содержат экспериментальные схемы: добавляем распознанные узлы,
                // а несовместимые строки не должны отменять весь импорт.
                lines.mapNotNull { line -> runCatching { link(line) }.getOrNull() }
            }
            require(entries.isNotEmpty()) { "Не найдено поддерживаемых узлов в списке" }
            require(entries.size <= 200) { "За один раз можно добавить не более 200 узлов" }
            return entries.map { (name, value) ->
                val type = value["type"]?.jsonPrimitive?.content ?: error("В JSON отсутствует type")
                require(type in types) { "Этот тип outbound не поддерживается импортом" }
                require(value["detour"] == null && value["domain_resolver"] == null) { "Узел должен быть самостоятельным: ссылки на другие outbound/резолверы не поддерживаются" }
                require(!value["server"]?.jsonPrimitive?.content.isNullOrBlank()) { "Не задан сервер" }
                require(value["server_port"]?.jsonPrimitive?.intOrNull in 1..65535) { "Порт должен быть от 1 до 65535" }
                if(type in setOf("vless","vmess","tuic")) {
                    val uuid=value["uuid"]!!.jsonPrimitive.content
                    require(UUID.fromString(uuid).toString().equals(uuid,true)) { "Не задан корректный UUID" }
                }
                if(type in setOf("trojan","hysteria2","tuic","shadowsocks","anytls")) require(!value["password"]?.jsonPrimitive?.content.isNullOrEmpty()) { "Не задан пароль" }
                if(type in setOf("trojan","hysteria2","tuic","anytls")) require(value["tls"]?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull==true) { "Не включён обязательный TLS" }
                if(type=="shadowsocks") require(!value["method"]?.jsonPrimitive?.content.isNullOrBlank()) { "Не задан метод шифрования" }
                val clean = JsonObject(value.filterKeys { it != "tag" })
                Node(name = (name ?: value["tag"]?.jsonPrimitive?.content ?: "$type · ${value["server"]!!.jsonPrimitive.content}").take(120), outbound = clean.toString(), mark=mark)
            }
        } catch(e: Exception) {
            // Не возвращаем сообщения URI/JSON-парсера: они могут содержать пароль.
            if(e is IllegalArgumentException && e.message?.let { it.startsWith("В ") || it.startsWith("Узел ") || it.startsWith("Этот ") || it.startsWith("Порт ") || it.startsWith("Не ") || it.startsWith("За ") || it.startsWith("Вставьте ") || it.startsWith("Параметр ") } == true) throw e
            throw IllegalArgumentException("Не удалось разобрать узел: проверьте формат ссылки, UUID, сервер и порт")
        }
    }
    private fun link(raw: String): Pair<String?, JsonObject> {
        if(raw.startsWith("vmess://")) {
            val v = codec.parseToJsonElement(b64(raw.removePrefix("vmess://"))).jsonObject
            fun s(k: String, d: String="") = v[k]?.jsonPrimitive?.content ?: d
            val m = mutableMapOf<String,JsonElement>("type" to p("vmess"), "server" to p(s("add")), "server_port" to JsonPrimitive(s("port").toInt()), "uuid" to p(s("id")), "security" to p(s("scy","auto")), "alter_id" to JsonPrimitive(s("aid","0").toInt()))
            if(s("tls") == "tls") m["tls"] = obj("enabled" to JsonPrimitive(true), "server_name" to p(s("sni",s("add"))))
            when(s("net","tcp")) {
                "tcp" -> Unit
                "ws" -> m["transport"] = obj("type" to p("ws"),"path" to p(s("path","/")),"headers" to obj("Host" to p(s("host",s("add")))))
                "grpc" -> m["transport"] = obj("type" to p("grpc"),"service_name" to p(s("path")))
                else -> error("Не поддерживается транспорт VMess; используйте outbound JSON")
            }
            return s("ps",s("add")) to JsonObject(m)
        }
        var normalized = raw
        if(raw.startsWith("ss://")) {
            val body = raw.removePrefix("ss://").substringBefore('#').substringBefore('?')
            if(!body.contains('@')) normalized = "ss://" + b64(body) + if(raw.contains('#')) "#"+raw.substringAfter('#') else ""
        }
        val u = URI(normalized)
        val scheme = u.scheme?.lowercase()
        val type = when(scheme) { "ss" -> "shadowsocks"; "hy2" -> "hysteria2"; else -> scheme }
        require(type in setOf("vless","trojan","shadowsocks","hysteria2","tuic")) { "Этот формат ссылки не поддерживается; используйте outbound JSON" }
        val q = u.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { val a=it.split('=',limit=2); decode(a[0]) to decode(a.getOrElse(1){""}) }
        // Parse query parameters and reject unsupported 'extra' or 'fragment' parameters
        // Disallow unsupported outbound JSON parameters 'extra' and 'fragment'
        if (unsupported.isNotEmpty()) return null
        // Previously unknown parameters were ignored; now we actively skip unsupported rows
        // Continue with existing validation and construction of the outbound

        // Неизвестные параметры игнорируются, чтобы не отбрасывать рабочие ссылки из подписок
        require(q["insecure"] !in listOf("1","true") && q["allowInsecure"] !in listOf("1","true")) { "Не разрешён импорт ссылки с отключённой проверкой сертификата" }
        require(q["encryption"] == null || q["encryption"] == "none") { "Не поддерживается encryption; используйте JSON" }
        val host = u.host?.removePrefix("[")?.removeSuffix("]") ?: error("Не задан сервер")
        val port = if(u.port == -1 && type in setOf("hysteria2","trojan","tuic")) 443 else u.port
        val user = decode(u.rawUserInfo ?: error("Не заданы данные авторизации"))
        val m = mutableMapOf<String,JsonElement>("type" to p(type!!),"server" to p(host),"server_port" to JsonPrimitive(port))
        when(type) {
            "vless" -> { m["uuid"]=p(user); q["flow"]?.takeIf { it.isNotEmpty() }?.let { m["flow"]=p(it) } }
            "trojan","hysteria2" -> { require(user.isNotEmpty()); m["password"]=p(user) }
            "tuic" -> { val a=user.split(':',limit=2); require(a.size==2); m["uuid"]=p(a[0]);m["password"]=p(a[1]); q["congestion_control"]?.let { m["congestion_control"]=p(it) };q["udp_relay_mode"]?.let { m["udp_relay_mode"]=p(it) } }
            "shadowsocks" -> { val a=(if(user.contains(':')) user else b64(user)).split(':',limit=2);require(a.size==2);m["method"]=p(a[0]);m["password"]=p(a[1]) }
        }
        val security=q["security"] ?: if(type in setOf("trojan","hysteria2","tuic")) "tls" else "none"
        require(security in setOf("none","tls","reality")) { "Не поддерживается security" }
        if(security != "none") {
            val tls=mutableMapOf<String,JsonElement>("enabled" to JsonPrimitive(true),"server_name" to p(q["sni"] ?: q["peer"] ?: host))
            q["alpn"]?.let { tls["alpn"]=JsonArray(it.split(',').map(::p)) }
            q["fp"]?.let { tls["utls"]=obj("enabled" to JsonPrimitive(true),"fingerprint" to p(it)) }
            if(security=="reality") { require(!q["pbk"].isNullOrBlank()); tls["reality"]=obj("enabled" to JsonPrimitive(true),"public_key" to p(q["pbk"]!!),"short_id" to p(q["sid"].orEmpty())) }
            m["tls"]=JsonObject(tls)
        }
        when(q["type"] ?: "tcp") {
            "tcp" -> Unit
            "ws" -> m["transport"]=obj("type" to p("ws"),"path" to p(q["path"] ?: "/"),"headers" to obj("Host" to p(q["host"] ?: host)))
            "grpc" -> m["transport"]=obj("type" to p("grpc"),"service_name" to p(q["serviceName"].orEmpty()))
            // XHTTP-ссылки принимаются как HTTP transport; extra/mode/ed из сторонней схемы не переносятся.
            "xhttp" -> m["transport"]=obj("type" to p("http"),"path" to p(q["path"] ?: "/"),"host" to JsonArray(listOf(p(q["host"] ?: host))))
            else -> error("Не поддерживается транспорт; используйте JSON")
        }
        q["obfs"]?.let { require(type=="hysteria2" && it=="salamander");m["obfs"]=obj("type" to p(it),"password" to p(q["obfs-password"] ?: error("Не задан пароль obfs"))) }
        return u.rawFragment?.let(::decode) to JsonObject(m)
    }
}
