package com.savemy.singboxfork.data

import com.savemy.singboxfork.core.ConfigFactory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CoreTest {
    private val uuid="123e4567-e89b-12d3-a456-426614174000"
    private fun node()=Importer.parse("vless://$uuid@example.com:443?security=tls#Тест").single()
    @Test fun vlessTls() { val n=node();assertEquals("Тест",n.name);assertEquals("vless",n.protocol);assertTrue(codec.parseToJsonElement(n.outbound).jsonObject["tls"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean) }
    @Test fun protocols() {
        val links=listOf("trojan://secret@example.com:443", "hy2://secret@example.com:443?obfs=salamander&obfs-password=xxx", "tuic://$uuid:secret@example.com:443", "ss://"+Base64.getEncoder().encodeToString("aes-128-gcm:secret".toByteArray())+"@example.com:8388")
        assertEquals(listOf("trojan","hysteria2","tuic","shadowsocks"),Importer.parse(links.joinToString("\n")).map { it.protocol })
    }
    @Test fun vmess() { val raw="""{"v":"2","ps":"test","add":"example.com","port":"443","id":"$uuid","net":"ws","path":"/ws","tls":"tls"}""";assertEquals("vmess",Importer.parse("vmess://"+Base64.getEncoder().encodeToString(raw.toByteArray())).single().protocol) }
    @Test fun plusInPassword() { val n=Importer.parse("trojan://a+b@example.com:443").single();assertEquals("a+b",codec.parseToJsonElement(n.outbound).jsonObject["password"]!!.jsonPrimitive.content) }
    @Test fun ipv6() { assertEquals("trojan",Importer.parse("trojan://p@[2001:db8::1]:443").single().protocol) }
    @Test fun malformedDoesNotExposeSecret() { listOf("vless://secret@example.com:99999", "{broken-secret}","trojan://secret@example.com:443?plugin=bad").forEach { raw -> try { Importer.parse(raw);fail("must reject") } catch(e:IllegalArgumentException) { assertFalse(e.message.orEmpty().contains("secret")) } } }
    @Test fun unsafeLinkRejected() { assertThrows(IllegalArgumentException::class.java) { Importer.parse("trojan://p@example.com:443?insecure=1") } }
    @Test fun vlessXhttpSubscriptionLine() {
        val n=Importer.parse("# comment\nvless://$uuid@example.com:443?security=tls&sni=accounts.example.com&encryption=none&insecure=0&allowInsecure=0&type=xhttp&host=cdn.example.com&path=%2F&mode=auto&ed=2048&fp=chrome#XHTTP").single()
        assertEquals("XHTTP",n.name)
        val out=codec.parseToJsonElement(n.outbound).jsonObject
        assertEquals("http",out["transport"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com",out["transport"]!!.jsonObject["host"]!!.jsonArray.single().jsonPrimitive.content)
    }
    @Test fun listIgnoresCommentsAndEmptyLines() {
        assertEquals(2,Importer.parse("# subscription header\n\nvless://$uuid@example.com:443\n# another\nvless://$uuid@example.net:443").size)
    }
    @Test fun subscriptionSkipsUnsupportedAndMalformedRows() {
        val imported=Importer.parse("# list\\nvless://$uuid@example.com:443?security=tls&type=xhttp&host=cdn.example.com&extra={\\\"mux\\\":true}#XHTTP\\nvless://bad@example.net:443?fragment=3,1,tlshello,0-5#unsupported\\nnot-a-node")
        assertEquals(1,imported.size)
        assertEquals("XHTTP",imported.single().name)
    }
    @Test fun jsonRoundTrip() { val original="""{"type":"trojan","tag":"x","server":"example.com","server_port":443,"password":"x","tls":{"enabled":true}}""";val n=Importer.parse(original,Mark.WL).single();assertEquals(Mark.WL,n.mark);assertEquals("x",n.name) }
    @Test fun intervalBounds() { assertEquals(45L,Interval.parse("45","minutes"));assertEquals(4320L,Interval.parse("3","days"));assertThrows(IllegalArgumentException::class.java) { Interval.parse("14","minutes") };assertThrows(IllegalArgumentException::class.java) { Interval.parse("999999999999999","days") } }
    @Test fun blacklistNeverSelected() {
        val a=node().copy(id="a",mark=Mark.BL);val b=node().copy(id="b",mark=Mark.WL)
        val data=AppData(listOf(a,b),Settings(selectedId="a",bypassPackages=setOf("com.example.app")))
        val config=codec.parseToJsonElement(ConfigFactory.create(data,true,12456,"secret","/tmp/log")).jsonObject
        val selector=config["outbounds"]!!.jsonArray.last().jsonObject
        assertEquals("b",selector["default"]!!.jsonPrimitive.content)
        assertEquals(listOf("b"),selector["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1400,config["inbounds"]!!.jsonArray.first().jsonObject["mtu"]!!.jsonPrimitive.int)
        assertTrue(config["inbounds"]!!.jsonArray.first().jsonObject["exclude_package"]!!.jsonArray.any { it.jsonPrimitive.content=="com.example.app" })
    }
    @Test fun emptyVpnFailsClosed() { assertThrows(IllegalArgumentException::class.java) { ConfigFactory.create(AppData(),true,12456,"secret","log") } }
    @Test fun packetRecoveryOnlyTuic() {
        val n=Importer.parse("tuic://$uuid:p@example.com:443").single()
        val data=AppData(listOf(n,node()),Settings(packetRecovery=true))
        val out=codec.parseToJsonElement(ConfigFactory.create(data,true,12345,"s","log")).jsonObject["outbounds"]!!.jsonArray
        assertEquals("quic",out[0].jsonObject["udp_relay_mode"]!!.jsonPrimitive.content);assertNull(out[1].jsonObject["udp_relay_mode"])
    }
}
