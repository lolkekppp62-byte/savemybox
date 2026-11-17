package com.savemy.singboxfork.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.encodeToByteArray())

    private fun node() = Importer.parse("trojan://p@example.com:443#T").single()

    @Test fun plainTextListIsParsed() {
        val text = "# Список\nvless://$uuid@a.example:443?security=tls#A\ntrojan://p@b.example:443#B\n\n"
        val nodes = SubscriptionManager.parse(text)
        assertEquals(listOf("A", "B"), nodes.map { it.name })
        assertTrue(SubscriptionManager.looksLikeUrl("https://raw.githubusercontent.com/u/r/main/list.txt"))
        assertFalse(SubscriptionManager.looksLikeUrl("vless://$uuid@a.example:443"))
    }

    @Test fun base64SubscriptionIsDecoded() {
        val raw = b64("trojan://p@b64.example:443#Кодированная")
        assertEquals("Кодированная", SubscriptionManager.parse(raw).single().name)
        // Уже декодированный текст не портится повторным Base64.
        assertEquals("Кодированная", SubscriptionManager.parse("trojan://p@b64.example:443#Кодированная").single().name)
    }

    @Test fun jsonSubscriptionIsParsed() {
        val json = """[{"type":"trojan","server":"j1.example","server_port":443,"password":"x","tls":{"enabled":true},"tag":"J1"}]"""
        val nodes = SubscriptionManager.parse(json)
        assertEquals("J1", nodes.single().name)
        assertEquals("trojan", nodes.single().protocol)
    }

    @Test fun titlePrefersHeaderComment() {
        assertEquals("Моя подписка", SubscriptionManager.title("# Моя подписка\ntrojan://p@x:443", "https://host/list.txt"))
        assertEquals("list.txt", SubscriptionManager.title("trojan://p@x:443", "https://host/list.txt?token=1"))
    }

    @Test fun scheduleMinutesMatchRefreshMode() {
        assertEquals(360L, SubscriptionManager.minutes(SubscriptionRefresh.H6))
        assertEquals(720L, SubscriptionManager.minutes(SubscriptionRefresh.H12))
        assertEquals(1440L, SubscriptionManager.minutes(SubscriptionRefresh.H24))
        assertNull(SubscriptionManager.minutes(SubscriptionRefresh.OFF))
        assertNull(SubscriptionManager.minutes(SubscriptionRefresh.ON_START))
    }

    @Test fun subscriptionModeBindsToRoutingMode() {
        val black = Subscription(name = "ЧС", url = "https://x", mode = SubscriptionMode.BLACKLIST)
        val white = Subscription(name = "БС", url = "https://y", mode = SubscriptionMode.WHITELIST)
        val any = Subscription(name = "Оба", url = "https://z", mode = SubscriptionMode.UNIVERSAL)
        assertTrue(SubscriptionManager.modeAllows(black, RoutingMode.BLACKLIST_PROXY))
        assertFalse(SubscriptionManager.modeAllows(black, RoutingMode.WHITELIST_BYPASS))
        assertTrue(SubscriptionManager.modeAllows(white, RoutingMode.WHITELIST_BYPASS))
        assertFalse(SubscriptionManager.modeAllows(white, RoutingMode.BLACKLIST_PROXY))
        assertTrue(SubscriptionManager.modeAllows(any, RoutingMode.WHITELIST_BYPASS))
        assertTrue(SubscriptionManager.modeAllows(any, RoutingMode.BLACKLIST_PROXY))
    }

    @Test fun candidatesRespectSubscriptionBinding() {
        val black = Subscription(name = "ЧС", url = "https://x", mode = SubscriptionMode.BLACKLIST)
        val white = Subscription(name = "БС", url = "https://y", mode = SubscriptionMode.WHITELIST)
        val blackNode = node().copy(id = "${black.id}::b", name = "b")
        val whiteNode = node().copy(id = "${white.id}::w", name = "w")
        val manual = node().copy(id = "manual", name = "m")
        val data = AppData(listOf(blackNode, whiteNode, manual), Settings(subscriptions = listOf(black, white)))
        assertEquals(setOf("b", "m"), data.candidates(RoutingMode.BLACKLIST_PROXY).map { it.name }.toSet())
        assertEquals(setOf("w", "m"), data.candidates(RoutingMode.WHITELIST_BYPASS).map { it.name }.toSet())
        assertEquals(black, data.subscriptionOf(blackNode))
        assertNull(data.subscriptionOf(manual))
    }

    @Test fun blackMarkedNodeIsNeverCandidate() {
        val node = node().copy(id = "manual", mark = Mark.BL)
        assertTrue(AppData(listOf(node), Settings()).candidates(RoutingMode.WHITELIST_BYPASS).isEmpty())
    }
}
