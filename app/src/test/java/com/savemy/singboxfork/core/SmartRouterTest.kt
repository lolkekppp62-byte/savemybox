package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.CheckStatus
import com.savemy.singboxfork.data.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartRouterTest {
    private fun node(id: String, latency: Long?, status: CheckStatus) =
        Node(id = id, name = id, outbound = "{}", latencyMs = latency, status = status)

    @Test fun healthyActiveIsKept() {
        assertNull(SmartRouter.bestFallback(node("a", 40, CheckStatus.ALIVE), listOf(node("b", 10, CheckStatus.ALIVE))))
    }

    @Test fun fastestAliveAlternativeWins() {
        val alternatives = listOf(
            node("dead", null, CheckStatus.TCP_FAILED),
            node("slow", 90, CheckStatus.ALIVE),
            node("fast", 20, CheckStatus.ALIVE)
        )
        assertEquals("fast", SmartRouter.bestFallback(node("a", null, CheckStatus.URL_FAILED), alternatives)?.id)
    }

    @Test fun noSwitchWithoutAliveAlternatives() {
        assertNull(SmartRouter.bestFallback(node("a", null, CheckStatus.TCP_FAILED), listOf(node("b", null, CheckStatus.URL_FAILED))))
        assertNull(SmartRouter.bestFallback(node("a", null, CheckStatus.URL_FAILED), emptyList()))
    }

    @Test fun missingActiveAllowsFallback() {
        assertEquals("b", SmartRouter.bestFallback(null, listOf(node("b", 15, CheckStatus.ALIVE)))?.id)
    }
}
