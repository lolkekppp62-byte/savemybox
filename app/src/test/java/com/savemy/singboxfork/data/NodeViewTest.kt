package com.savemy.singboxfork.data

import com.savemy.singboxfork.core.NodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeViewTest {
    private fun node(id: String, ping: Long? = null, status: CheckStatus = CheckStatus.UNCHECKED, protocol: String = "trojan") =
        Node(id = id, name = id, outbound = """{"type":"$protocol","server":"$id.example","server_port":443}""",
            latencyMs = ping, status = status, type = protocol)

    private fun alive(id: String, ping: Long) = id to NodeResult(id, 5, ping, 0, CheckStatus.ALIVE, 1)
    private fun failed(id: String) = id to NodeResult(id, null, null, null, CheckStatus.TCP_FAILED, 1)

    @Test fun fastModeIsDefaultProfile() {
        assertEquals(20, CheckMode.FAST.tcpConcurrency)
        assertEquals(1_500, CheckMode.FAST.tcpTimeoutMs)
        assertEquals(2_000, CheckMode.FAST.urlTimeoutMs)
        assertEquals(3_500, CheckMode.PRECISE.tcpTimeoutMs)
        assertEquals(4_000, CheckMode.PRECISE.urlTimeoutMs)
        assertEquals(CheckMode.FAST, Settings().checkMode)
    }

    @Test fun fastestNodesRiseToTopAsAnswersArrive() {
        val nodes = listOf(node("slow"), node("fast"), node("medium"))
        val live = mapOf(alive("slow", 320), alive("medium", 120), alive("fast", 40))
        val split = NodeView.layout(nodes, live, PingFilterState.ALL, NodeSortState.PING, hideDead = true)
        assertEquals(listOf("fast", "medium", "slow"), split.main.map { it.id })
        assertTrue(split.dead.isEmpty())
    }

    @Test fun deadNodesGoToFolderAndBackOnRevival() {
        val nodes = listOf(node("dead"), node("live", 90, CheckStatus.ALIVE))
        val deadSplit = NodeView.layout(nodes, mapOf(failed("dead")), PingFilterState.ALL, NodeSortState.PING, hideDead = true)
        assertEquals(listOf("live"), deadSplit.main.map { it.id })
        assertEquals(listOf("dead"), deadSplit.dead.map { it.id })
        assertEquals(1, deadSplit.deadCount)

        // Повторная проверка вернула ответ: узел снова в основном списке, папка пуста.
        val revived = NodeView.layout(nodes, mapOf(failed("dead"), alive("dead", 60)), PingFilterState.ALL, NodeSortState.PING, hideDead = true)
        assertEquals(listOf("dead", "live"), revived.main.map { it.id })
        assertTrue(revived.dead.isEmpty())
    }

    @Test fun uncheckedNodesAreNotDead() {
        assertFalse(NodeView.isDead(node("new")))
        assertTrue(NodeView.isDead(node("bad", null, CheckStatus.URL_FAILED)))
    }

    @Test fun deadStayAtBottomWhenFolderDisabled() {
        val nodes = listOf(node("dead"), node("aaa"), node("zzz"))
        val live = mapOf(failed("dead"))
        val split = NodeView.layout(nodes, live, PingFilterState.ALL, NodeSortState.NAME, hideDead = false)
        assertTrue(split.dead.isEmpty())
        assertEquals(listOf("aaa", "zzz", "dead"), split.main.map { it.id })
    }

    @Test fun pingFiltersSelectOnlyFastNodes() {
        val nodes = listOf(node("a", 90, CheckStatus.ALIVE), node("b", 200, CheckStatus.ALIVE), node("c", 280, CheckStatus.ALIVE), node("d", null, CheckStatus.TCP_FAILED))
        val live = emptyMap<String, NodeResult>()
        val all = NodeView.layout(nodes, live, PingFilterState.ALL, NodeSortState.PING, true)
        assertEquals(listOf("a", "b", "c"), all.main.map { it.id })
        assertEquals(listOf("d"), all.dead.map { it.id })
        assertEquals(listOf("a", "b", "c"), NodeView.layout(nodes, live, PingFilterState.ALIVE, NodeSortState.PING, true).main.map { it.id })
        assertEquals(listOf("a"), NodeView.layout(nodes, live, PingFilterState.FAST_150, NodeSortState.PING, true).main.map { it.id })
        assertEquals(listOf("a", "b", "c"), NodeView.layout(nodes, live, PingFilterState.FAST_300, NodeSortState.PING, true).main.map { it.id })
    }

    @Test fun sortingByNameAndProtocol() {
        val nodes = listOf(
            node("z", 10, CheckStatus.ALIVE, "vless").copy(name = "Яндекс"),
            node("a", 20, CheckStatus.ALIVE, "trojan").copy(name = "Альфа"),
            node("m", 30, CheckStatus.ALIVE, "shadowsocks").copy(name = "Бета")
        )
        assertEquals(listOf("Альфа", "Бета", "Яндекс"), NodeView.layout(nodes, emptyMap(), PingFilterState.ALL, NodeSortState.NAME, true).main.map { it.name })
        assertEquals(listOf("shadowsocks", "trojan", "vless"), NodeView.layout(nodes, emptyMap(), PingFilterState.ALL, NodeSortState.PROTOCOL, true).main.map { it.protocol })
    }

    @Test fun labelsForPanel() {
        assertEquals("Все", NodeView.filterLabel(PingFilterState.ALL))
        assertEquals("< 150 мс", NodeView.filterLabel(PingFilterState.FAST_150))
        assertEquals("По пингу", NodeView.sortLabel(NodeSortState.PING))
        assertEquals("По протоколу", NodeView.sortLabel(NodeSortState.PROTOCOL))
    }
}
