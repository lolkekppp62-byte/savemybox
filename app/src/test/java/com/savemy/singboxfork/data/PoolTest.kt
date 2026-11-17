package com.savemy.singboxfork.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пул узлов: дедупликация, лимиты, мёртвые подписки и уникальные id (теги outbound). */
class PoolTest {
    private fun node(port: Int = 443, server: String = "example.com", kind: String = "trojan") =
        if (kind == "trojan") Importer.parse("trojan://p@$server:$port#N$port").single()
        else Importer.parse("vless://123e4567-e89b-12d3-a456-426614174000@$server:$port?security=tls#N$port").single()

    @Test fun duplicateNodesAreRemovedByAddressPortProtocol() {
        val nodes = listOf(node(443), node(443), node(8443), node(443, kind = "vless"))
        assertEquals(3, SubscriptionManager.distinct(nodes).size)
        // Разные адреса и протоколы на том же порту — разные узлы.
        assertEquals(3, SubscriptionManager.distinct(listOf(node(443), node(443), node(443, "other.example"), node(443, kind = "vless"))).size)
    }

    @Test fun subscriptionImportIsLimitedTo150Nodes() {
        val many = (1..200).map { node(1000 + it) }
        val limited = SubscriptionManager.distinct(many)
        assertEquals(MAX_NODES_PER_SUBSCRIPTION, limited.size)
        assertEquals(150, MAX_NODES_PER_SUBSCRIPTION)
        assertEquals(10, SubscriptionManager.distinct((1..10).map { node(2000 + it) }).size)
    }

    @Test fun activePoolIsCappedAt500() {
        val pool = (1..600).map { node(3000 + it) }
        assertEquals(MAX_ACTIVE_POOL, SubscriptionManager.capPool(pool).size)
        assertEquals(500, MAX_ACTIVE_POOL)
        assertEquals(3, SubscriptionManager.capPool(pool.take(3)).size)
    }

    @Test fun deadSubscriptionStatusNeedsCheckedZeroAlive() {
        val unchecked = listOf(node().copy(status = CheckStatus.UNCHECKED))
        assertFalse(SubscriptionManager.isDead(unchecked))
        val dead = listOf(node().copy(status = CheckStatus.TCP_FAILED), node(8443).copy(status = CheckStatus.URL_FAILED))
        assertTrue(SubscriptionManager.isDead(dead))
        assertEquals(0, SubscriptionManager.aliveCount(dead))
        val mixed = dead + node(9443).copy(status = CheckStatus.ALIVE, latencyMs = 40)
        assertFalse(SubscriptionManager.isDead(mixed))
        assertEquals(1, SubscriptionManager.aliveCount(mixed))
    }

    @Test fun deadSubscriptionSortsToBottom() {
        val ok = Subscription(name = "живая", url = "https://a")
        val deadSub = Subscription(name = "мёртвая", url = "https://b")
        val nodesOf = { sub: Subscription ->
            if (sub.id == ok.id) listOf(node().copy(status = CheckStatus.ALIVE, latencyMs = 50))
            else listOf(node().copy(status = CheckStatus.TCP_FAILED))
        }
        val sorted = SubscriptionManager.sortedForDisplay(listOf(deadSub, ok), nodesOf)
        assertEquals(listOf("живая", "мёртвая"), sorted.map { it.name })
    }

    @Test fun modeSwitchPicksNodeOfBoundSubscription() {
        val black = Subscription(name = "ЧС", url = "https://x", mode = SubscriptionMode.BLACKLIST)
        val white = Subscription(name = "БС", url = "https://y", mode = SubscriptionMode.WHITELIST)
        val blackNode = node(443).copy(id = "${black.id}::black", name = "black")
        val whiteNode = node(8443).copy(id = "${white.id}::white", name = "white")
        val data = AppData(
            listOf(blackNode, whiteNode),
            Settings(subscriptions = listOf(black, white), selectedId = whiteNode.id)
        )
        assertEquals("white", data.preferredForMode(RoutingMode.WHITELIST_BYPASS)?.name)
        assertEquals("black", data.preferredForMode(RoutingMode.BLACKLIST_PROXY)?.name)
        // Узлы подписки отдаются по её id, ручные узлы не принадлежат никому.
        assertEquals(listOf(whiteNode), data.nodesOf(white))
        assertNull(data.subscriptionOf(node()))
    }

    /** Повторяющиеся имена в публичных списках раньше давали одинаковые теги и роняли ядро. */
    @Test fun repeatedNamesGetUniqueIds() {
        val raw = (1..4).map { port -> node(port).copy(name = "🇩🇪 DE") }
        val tagged = subscriptionNodes("sub", raw)
        assertEquals(4, tagged.map { it.id }.distinct().size)
        assertEquals("sub::🇩🇪 DE", tagged.first().id)
        assertEquals("sub::🇩🇪 DE#2", tagged[1].id)
        assertTrue(tagged.all { it.id.startsWith("sub::") })
        // Содержимое узла не меняется: правится только id.
        assertEquals(raw.first().outbound, tagged.first().outbound)
    }

    @Test fun differentSubscriptionsKeepDifferentPrefixes() {
        val first = subscriptionNodes("a", listOf(node(443).copy(name = "node")))
        val second = subscriptionNodes("b", listOf(node(443).copy(name = "node")))
        assertEquals("a::node", first.single().id)
        assertEquals("b::node", second.single().id)
    }

    @Test fun nameThatAlreadyHasSuffixGetsNextOne() {
        val nodes = listOf(
            node(1).copy(name = "edge"),
            node(2).copy(name = "edge#2"),
            node(3).copy(name = "edge")
        )
        val ids = subscriptionNodes("s", nodes).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(listOf("s::edge", "s::edge#2", "s::edge#3"), ids)
    }
}
