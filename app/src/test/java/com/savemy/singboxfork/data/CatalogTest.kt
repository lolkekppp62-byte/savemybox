package com.savemy.singboxfork.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private fun node(port: Int = 443, server: String = "example.com", kind: String = "trojan") =
        if (kind == "trojan") Importer.parse("trojan://p@$server:$port#N$port").single()
        else Importer.parse("vless://123e4567-e89b-12d3-a456-426614174000@$server:$port?security=tls#N$port").single()

    @Test fun catalogHasBlackAndWhiteSlots() {
        val black = DefaultSubscriptionsRegistry.byMode(SubscriptionMode.BLACKLIST)
        val white = DefaultSubscriptionsRegistry.byMode(SubscriptionMode.WHITELIST)
        assertTrue(black.size >= 2 && white.size >= 2)
        assertTrue(black.all { it.refresh == SubscriptionRefresh.H6 })
        assertTrue(black.all { it.url.startsWith("https://") })
        assertTrue(white.all { it.mode == SubscriptionMode.WHITELIST })
        assertEquals(DefaultSubscriptionsRegistry.entries.size, DefaultSubscriptionsRegistry.entries.map { it.id }.distinct().size)
    }

    @Test fun seedingHappensOnlyOnFirstLaunch() {
        val seeded = DefaultSubscriptionsRegistry.seed(emptyList())
        assertEquals(DefaultSubscriptionsRegistry.entries.size, seeded.size)
        assertTrue(seeded.all { it.source == "catalog" })
        assertEquals(SubscriptionMode.BLACKLIST, seeded.first().mode)
        // Пользователь что-то добавил — каталог повторно не навязывается.
        val own = listOf(Subscription(name = "своя", url = "https://example.com/list.txt"))
        assertEquals(own, DefaultSubscriptionsRegistry.seed(own))
    }

    @Test fun catalogSubscriptionKeepsModeAndSchedule() {
        val entry = DefaultSubscriptionsRegistry.byMode(SubscriptionMode.WHITELIST).first()
        val sub = DefaultSubscriptionsRegistry.toSubscription(entry)
        assertEquals(entry.url, sub.url)
        assertEquals(SubscriptionMode.WHITELIST, sub.mode)
        assertEquals(entry.refresh, sub.refresh)
        assertEquals("catalog", sub.source)
    }

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
}
