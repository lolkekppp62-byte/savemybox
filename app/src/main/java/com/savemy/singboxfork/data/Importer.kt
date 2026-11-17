package com.savemy.singboxfork.data

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/** Импорт не записывает исходные ссылки в журнал и не отключает проверку сертификата. */
object Importer : NodeParser {
    private val types = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "socks", "http", "hysteria", "anytls")
    private val linkTypes = setOf("vless", "trojan", "shadowsocks", "hysteria2", "tuic")
    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    private fun b64(s: String): String = runCatching { String(Base64.getDecoder().decode(s)) }
        .getOrElse { String(Base64.getUrlDecoder().decode(s)) }
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun p(s: String) = JsonPrimitive(s)

    override fun parse(text: String, mark: Mark): List<Node> {
        require(text.length <= 1_000_000) { "Слишком большой импорт (максимум 1 МБ)" }
        try {
            val input = text.trim()
            val entries: List<Pair<String?, JsonObject>> = if (input.startsWith("{") || input.startsWith("[")) {
                val root = codec.parseToJsonElement(input)
                val list = if (root is JsonArray) root.toList() else listOf(root)
                list.map { null to it.jsonObject }
            } else {
                val lines = input.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
                require(lines.size <= 1000) { "В списке слишком много строк (максимум 1000)" }
                // Одна строка — сообщаем причину отказа. В списке/подписке битые строки пропускаем,
                // чтобы экспериментальная схема не отменяла весь импорт.
                if (lines.size == 1) listOfNotNull(parseLine(lines.single()))
                else lines.mapNotNull { line -> runCatching { parseLine(line) }.getOrNull() }
            }
            require(entries.isNotEmpty()) { "Не найдено поддерживаемых узлов в списке" }
            require(entries.size <= 200) { "За один раз можно добавить не более 200 узлов" }
            return entries.map { (name, value) -> node(name, value, mark) }
        } catch (e: Exception) {
            // Не возвращаем сообщения URI/JSON-парсера: они могут содержать пароль.
            if (e is IllegalArgumentException && e.message?.let { it.startsWith("В ") || it.startsWith("Узел ") || it.startsWith("Этот ") || it.startsWith("Порт ") || it.startsWith("Не ") || it.startsWith("За ") || it.startsWith("Вставьте ") || it.startsWith("Параметр ") } == true) throw e
            throw IllegalArgumentException("Не удалось разобрать узел: проверьте формат ссылки, UUID, сервер и порт")
        }
    }

    override fun parseLine(line: String): Pair<String?, JsonObject>? {
        val text = line.trim()
        if (text.isEmpty() || text.startsWith("#")) return null
        return if (text.startsWith("{")) null to JsonObject(codec.parseToJsonElement(text).jsonObject) else link(text)
    }

    /** Проверка самостоятельности outbound и построение узла с закэшированным типом протокола. */
    private fun node(name: String?, value: JsonObject, mark: Mark): Node {
        val type = value["type"]?.jsonPrimitive?.content ?: error("В JSON отсутствует type")
        require(type in types) { "Этот тип outbound не поддерживается импортом" }
        require(value["detour"] == null && value["domain_resolver"] == null) { "Узел должен быть самостоятельным: ссылки на другие outbound/резолверы не поддерживаются" }
        require(!value["server"]?.jsonPrimitive?.content.isNullOrBlank()) { "Не задан сервер" }
        require(value["server_port"]?.jsonPrimitive?.intOrNull in 1..65535) { "Порт должен быть от 1 до 65535" }
        if (type in setOf("vless", "vmess", "tuic")) {
            val uuid = value["uuid"]!!.jsonPrimitive.content
            require(UUID.fromString(uuid).toString().equals(uuid, true)) { "Не задан корректный UUID" }
        }
        if (type in setOf("trojan", "hysteria2", "tuic", "shadowsocks", "anytls")) require(!value["password"]?.jsonPrimitive?.content.isNullOrEmpty()) { "Не задан пароль" }
        if (type in setOf("trojan", "hysteria2", "tuic", "anytls")) require(value["tls"]?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull == true) { "Не включён обязательный TLS" }
        if (type == "shadowsocks") require(!value["method"]?.jsonPrimitive?.content.isNullOrBlank()) { "Не задан метод шифрования" }
        val clean = JsonObject(value.filterKeys { it != "tag" })
        val display = (name ?: value["tag"]?.jsonPrimitive?.content ?: "$type · ${value["server"]!!.jsonPrimitive.content}").take(120)
        return Node(name = display, outbound = clean.toString(), mark = mark, type = type)
    }

    private fun link(raw: String): Pair<String?, JsonObject>? =
        if (raw.startsWith("vmess://")) vmess(raw) else uriLink(raw)

    private fun vmess(raw: String): Pair<String?, JsonObject> {
        val v = codec.parseToJsonElement(b64(raw.removePrefix("vmess://"))).jsonObject
        fun s(k: String, d: String = "") = v[k]?.jsonPrimitive?.content ?: d
        val m = mutableMapOf<String, JsonElement>("type" to p("vmess"), "server" to p(s("add")), "server_port" to JsonPrimitive(s("port").toInt()), "uuid" to p(s("id")), "security" to p(s("scy", "auto")), "alter_id" to JsonPrimitive(s("aid", "0").toInt()))
        if (s("tls") == "tls") m["tls"] = obj("enabled" to JsonPrimitive(true), "server_name" to p(s("sni", s("add"))))
        when (s("net", "tcp")) {
            "tcp" -> Unit
            "ws" -> m["transport"] = obj("type" to p("ws"), "path" to p(s("path", "/")), "headers" to obj("Host" to p(s("host", s("add")))))
            "grpc" -> m["transport"] = obj("type" to p("grpc"), "service_name" to p(s("path")))
            else -> error("Не поддерживается транспорт VMess; используйте outbound JSON")
        }
        return s("ps", s("add")) to JsonObject(m)
    }

    /** Разобранные части URI-ссылки, общие для vless/trojan/shadowsocks/hysteria2/tuic. */
    private data class Link(
        val type: String,
        val host: String,
        val port: Int,
        val user: String,
        val query: Map<String, String>,
        val name: String?
    )

    private fun readLink(raw: String): Link {
        var normalized = raw
        if (raw.startsWith("ss://")) {
            val body = raw.removePrefix("ss://").substringBefore('#').substringBefore('?')
            if (!body.contains('@')) normalized = "ss://" + b64(body) + if (raw.contains('#')) "#" + raw.substringAfter('#') else ""
        }
        val u = URI(normalized)
        val scheme = u.scheme?.lowercase()
        val type = requireNotNull(when (scheme) { "ss" -> "shadowsocks"; "hy2" -> "hysteria2"; else -> scheme }) {
            "Этот формат ссылки не поддерживается; используйте outbound JSON"
        }
        require(type in linkTypes) { "Этот формат ссылки не поддерживается; используйте outbound JSON" }
        val q = u.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
            .associate { val a = it.split('=', limit = 2); decode(a[0]) to decode(a.getOrElse(1) { "" }) }
        val host = u.host?.removePrefix("[")?.removeSuffix("]") ?: error("Не задан сервер")
        val port = if (u.port == -1 && type in setOf("hysteria2", "trojan", "tuic")) 443 else u.port
        val user = decode(u.rawUserInfo ?: error("Не заданы данные авторизации"))
        return Link(type, host, port, user, q, u.rawFragment?.let(::decode))
    }

    private fun uriLink(raw: String): Pair<String?, JsonObject>? {
        val l = readLink(raw)
        val q = l.query
        // fragment (uTLS-фрагментация) в sing-box не переносится: пропускаем только эту строку.
        // extra/mode/ed схемы XHTTP не копируются: из такой ссылки берётся только transport=http.
        if (q["fragment"] != null) return null
        require(q["plugin"].isNullOrEmpty()) { "Параметр plugin не поддерживается в ссылке; используйте outbound JSON" }
        require(q["insecure"] !in listOf("1", "true") && q["allowInsecure"] !in listOf("1", "true")) { "Не разрешён импорт ссылки с отключённой проверкой сертификата" }
        require(q["encryption"] == null || q["encryption"] == "none") { "Не поддерживается encryption; используйте JSON" }
        val m = mutableMapOf<String, JsonElement>("type" to p(l.type), "server" to p(l.host), "server_port" to JsonPrimitive(l.port))
        credentials(l, m)
        applyTls(l, m)
        applyTransport(l, m)
        applyObfs(l, m)
        return l.name to JsonObject(m)
    }

    /** Учётные данные и протокольные поля, специфичные для каждого типа. */
    private fun credentials(l: Link, m: MutableMap<String, JsonElement>) {
        when (l.type) {
            "vless" -> {
                m["uuid"] = p(l.user)
                l.query["flow"]?.takeIf { it.isNotEmpty() }?.let { m["flow"] = p(it) }
            }
            "trojan", "hysteria2" -> {
                require(l.user.isNotEmpty()) { "Не заданы данные авторизации" }
                m["password"] = p(l.user)
            }
            "tuic" -> {
                val a = l.user.split(':', limit = 2); require(a.size == 2) { "Не заданы данные авторизации" }
                m["uuid"] = p(a[0]); m["password"] = p(a[1])
                l.query["congestion_control"]?.let { m["congestion_control"] = p(it) }
                l.query["udp_relay_mode"]?.let { m["udp_relay_mode"] = p(it) }
            }
            "shadowsocks" -> {
                val a = (if (l.user.contains(':')) l.user else b64(l.user)).split(':', limit = 2)
                require(a.size == 2) { "Не заданы данные авторизации" }
                m["method"] = p(a[0]); m["password"] = p(a[1])
            }
        }
    }

    private fun applyTls(l: Link, m: MutableMap<String, JsonElement>) {
        val q = l.query
        val security = q["security"] ?: if (l.type in setOf("trojan", "hysteria2", "tuic")) "tls" else "none"
        require(security in setOf("none", "tls", "reality")) { "Не поддерживается security" }
        if (security == "none") return
        val tls = mutableMapOf<String, JsonElement>("enabled" to JsonPrimitive(true), "server_name" to p(q["sni"] ?: q["peer"] ?: l.host))
        q["alpn"]?.let { tls["alpn"] = JsonArray(it.split(',').map(::p)) }
        q["fp"]?.let { tls["utls"] = obj("enabled" to JsonPrimitive(true), "fingerprint" to p(it)) }
        if (security == "reality") {
            require(!q["pbk"].isNullOrBlank()) { "Не задан public key Reality" }
            tls["reality"] = obj("enabled" to JsonPrimitive(true), "public_key" to p(q["pbk"]!!), "short_id" to p(q["sid"].orEmpty()))
        }
        m["tls"] = JsonObject(tls)
    }

    private fun applyTransport(l: Link, m: MutableMap<String, JsonElement>) {
        val q = l.query
        when (q["type"] ?: "tcp") {
            "tcp" -> Unit
            "ws" -> m["transport"] = obj("type" to p("ws"), "path" to p(q["path"] ?: "/"), "headers" to obj("Host" to p(q["host"] ?: l.host)))
            "grpc" -> m["transport"] = obj("type" to p("grpc"), "service_name" to p(q["serviceName"].orEmpty()))
            // Сторонняя схема XHTTP принимается как HTTP transport: extra/mode/ed не переносятся.
            "xhttp" -> m["transport"] = obj("type" to p("http"), "path" to p(q["path"] ?: "/"), "host" to JsonArray(listOf(p(q["host"] ?: l.host))))
            else -> error("Не поддерживается транспорт; используйте JSON")
        }
    }

    private fun applyObfs(l: Link, m: MutableMap<String, JsonElement>) {
        l.query["obfs"]?.let {
            require(l.type == "hysteria2" && it == "salamander") { "Не поддерживается obfs" }
            m["obfs"] = obj("type" to p(it), "password" to p(l.query["obfs-password"] ?: error("Не задан пароль obfs")))
        }
    }
}
