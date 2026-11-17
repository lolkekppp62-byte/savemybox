package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.CheckStatus
import com.savemy.singboxfork.data.Importer
import com.savemy.singboxfork.data.Mark
import com.savemy.singboxfork.data.Node
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeCheckRunnerTest {
    private class FakeProxy : ProxyProbe {
        override suspend fun urlTest(tag: String, url: String, timeoutMs: Int) = 30L
    }

    private class FakeTcp(private val dead: Set<String>) : TcpProbe {
        val calls = mutableListOf<String>()
        override suspend fun connect(host: String, port: Int, timeoutMs: Int): Long? {
            calls += host
            return if (host in dead) null else 5L
        }
    }

    private fun node(link: String, name: String, mark: Mark = Mark.NONE) =
        Importer.parse(link, mark).single().copy(name = name)

    @Test fun mapsResultsAndKeepsIdentity() = runBlocking {
        val source = node("vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls#X", "Мой узел", Mark.WL)
        val checked = NodeCheckRunner(FakeTcp(emptySet())).run(listOf(source), FakeProxy()).single()
        assertEquals(source.id, checked.id)
        assertEquals("Мой узел", checked.name)
        assertEquals(Mark.WL, checked.mark)
        assertEquals(CheckStatus.ALIVE, checked.status)
        assertEquals(30L, checked.latencyMs)
        assertEquals(5L, checked.tcpMs)
        assertEquals(source.outbound, checked.outbound)
    }

    @Test fun tuicSkipsTcpPrefilter() = runBlocking {
        val source = node("tuic://123e4567-e89b-12d3-a456-426614174000:p@example.com:443#T", "TUIC")
        val tcp = FakeTcp(emptySet())
        val checked = NodeCheckRunner(tcp).run(listOf(source), FakeProxy()).single()
        assertTrue(tcp.calls.isEmpty())
        assertEquals(CheckStatus.ALIVE, checked.status)
        assertNull(checked.tcpMs)
    }

    @Test fun brokenOutboundIsSkipped() = runBlocking {
        val broken = Node(name = "broken", outbound = """{"type":"trojan"}""")
        assertTrue(NodeCheckRunner(FakeTcp(emptySet())).run(listOf(broken), FakeProxy()).isEmpty())
    }

    // Живой сосед обязателен: без него срабатывает откат на URLTest по всем узлам.
    @Test fun deadServerGetsNoLatency() = runBlocking {
        val dead = node("trojan://p@dead.example:443#D", "dead")
        val alive = node("trojan://p@alive.example:443#A", "alive")
        val checked = NodeCheckRunner(FakeTcp(setOf("dead.example"))).run(listOf(dead, alive), FakeProxy())
        val failed = checked.first { it.name == "dead" }
        assertEquals(CheckStatus.TCP_FAILED, failed.status)
        assertNull(failed.latencyMs)
        assertEquals(CheckStatus.ALIVE, checked.first { it.name == "alive" }.status)
    }
}
