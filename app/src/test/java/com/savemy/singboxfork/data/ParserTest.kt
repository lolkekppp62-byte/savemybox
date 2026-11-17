package com.savemy.singboxfork.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParserTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun parserContractCachesType() {
        val parser: NodeParser = Importer
        val node = parser.parse("vless://$uuid@example.com:443?security=tls#Тест").single()
        assertEquals("vless", node.type)
        assertEquals("vless", node.protocol)
    }

    @Test fun legacyNodeReadsTypeFromOutbound() {
        val legacy = Node(name = "old", outbound = """{"type":"trojan","server":"example.com","server_port":443,"password":"x","tls":{"enabled":true}}""")
        assertEquals("", legacy.type)
        assertEquals("trojan", legacy.protocol)
    }

    @Test fun parseLineSkipsForeignRows() {
        assertNull(Importer.parseLine("# comment"))
        assertNull(Importer.parseLine("   "))
        assertNull(Importer.parseLine("vless://$uuid@example.net:443?fragment=3,1,tlshello,0-5"))
        val json = Importer.parseLine("""{"type":"vless","server":"example.com","server_port":443,"uuid":"$uuid"}""")
        assertNotNull(json)
        assertEquals("vless", json!!.second["type"]!!.jsonPrimitive.content)
        assertNull(json.first)
    }

    @Test fun typeSurvivesSerialization() {
        val node = Importer.parse("trojan://p@example.com:443#T").single()
        val restored = codec.decodeFromString<Node>(codec.encodeToString(node))
        assertEquals("trojan", restored.type)
        assertEquals("trojan", restored.protocol)
    }

    @Test fun protocolSpecificFieldsStillBuilt() {
        val tuic = Importer.parse("tuic://$uuid:p@example.com:443?congestion_control=bbr#T").single()
        assertEquals("tuic", tuic.type)
        val hy2 = Importer.parse("hy2://secret@example.com:443?obfs=salamander&obfs-password=x#H").single()
        assertEquals("hysteria2", hy2.type)
        val ss = Importer.parse("ss://" + java.util.Base64.getEncoder().encodeToString("aes-128-gcm:p".toByteArray()) + "@example.com:8388#S").single()
        assertEquals("shadowsocks", ss.type)
    }
}
