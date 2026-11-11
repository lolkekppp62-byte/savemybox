package com.savemy.singboxfork.core

import kotlinx.serialization.json.JsonObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

/** Только защищённый случайным ключом локальный API. Без разрешения cleartext для внешних сайтов. */
class LocalApi(private val port: Int, private val secret: String) {
    private val sockets=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    fun cancelPending() { sockets.forEach { runCatching { it.close() } } }
    fun delay(tag: String): String = request("GET","/proxies/${URLEncoder.encode(tag,"UTF-8")}/delay?timeout=5000&url=https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204")
    fun select(tag: String) { request("PUT","/proxies/proxy", "{\"name\":\"$tag\"}") }
    private fun request(method: String,path: String,body: String=""): String = Socket().use { socket ->
        sockets.add(socket)
        try {
        socket.connect(InetSocketAddress("127.0.0.1",port),1500); socket.soTimeout=8000
        val bytes=body.toByteArray(Charsets.UTF_8)
        val head="$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $secret\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
        val input=socket.getInputStream().buffered()
        fun line(): String {
            val out=StringBuilder()
            while(true) { val c=input.read(); check(c>=0) { "Локальный API закрыл соединение" }; if(c==10) break; if(c!=13) out.append(c.toChar()); check(out.length<8192) }
            return out.toString()
        }
        val status=line().split(' ').getOrNull(1)?.toIntOrNull() ?: error("Ответ локального API повреждён")
        val headers=mutableMapOf<String,String>()
        while(true) { val l=line(); if(l.isEmpty()) break; headers[l.substringBefore(':').lowercase()]=l.substringAfter(':').trim() }
        val out=java.io.ByteArrayOutputStream()
        fun readBytes(count: Int) { require(count in 0..65536 && out.size()+count<=65536); repeat(count) { val c=input.read(); check(c>=0); out.write(c) } }
        if(headers["transfer-encoding"]?.contains("chunked")==true) {
            while(true) { val n=line().substringBefore(';').toInt(16); if(n==0) break; readBytes(n); line() }
        } else headers["content-length"]?.toIntOrNull()?.let { readBytes(it) }
        check(status in 200..299) { "Проверка через прокси не удалась" }
        out.toString("UTF-8")
        } finally { sockets.remove(socket) }
    }
}
