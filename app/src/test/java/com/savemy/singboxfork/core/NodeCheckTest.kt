package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.CheckStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class NodeCheckTest {
    private class FakeTcp(private val dead: Set<String>) : TcpProbe {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun connect(host: String, port: Int, timeoutMs: Int): Long? {
            calls += host
            return if (host in dead) null else 7L
        }
    }

    /** failures[tag] = сколько первых проб должно упасть. */
    private class FakeProxy(private val failures: Map<String, Int> = emptyMap()) : ProxyProbe {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        private val seen = ConcurrentHashMap<String, AtomicInteger>()
        override suspend fun urlTest(tag: String, url: String, timeoutMs: Int): Long {
            calls += tag
            val attempt = seen.getOrPut(tag) { AtomicInteger() }.incrementAndGet()
            if ((failures[tag] ?: 0) >= attempt) throw IllegalStateException("проба не удалась")
            return 40L
        }
    }

    @Test fun tcpFailsFastAndSkipsUrlTest() = runBlocking {
        val tcp = FakeTcp(setOf("dead.example"))
        val proxy = FakeProxy()
        val results = NodeChecker(tcp, proxy, batchEveryMs = 10).run(
            listOf(ProbeTarget("a", "dead.example", 443), ProbeTarget("b", "alive.example", 443))
        )
        val dead = results.first { it.id == "a" }
        assertEquals(CheckStatus.TCP_FAILED, dead.status)
        assertNull(dead.latencyMs)
        assertNull(dead.tcpMs)
        assertFalse(proxy.calls.contains("a"))
        assertTrue(proxy.calls.contains("b"))
        assertEquals(CheckStatus.ALIVE, results.first { it.id == "b" }.status)
    }

    @Test fun udpProtocolsSkipTcp() = runBlocking {
        val tcp = FakeTcp(emptySet())
        val results = NodeChecker(tcp, FakeProxy(), batchEveryMs = 10)
            .run(listOf(ProbeTarget("h", "udp.example", 443, udpOnly = true)))
        assertTrue(tcp.calls.isEmpty())
        assertEquals(CheckStatus.ALIVE, results.single().status)
        assertNull(results.single().tcpMs)
    }

    @Test fun batchesDeliverEveryResultOnce() = runBlocking {
        val targets = (1..6).map { ProbeTarget("n$it", "host$it", 443) }
        val batches = mutableListOf<List<NodeResult>>()
        val progress = mutableListOf<CheckProgress>()
        val results = NodeChecker(FakeTcp(emptySet()), FakeProxy(), batchEveryMs = 10)
            .run(targets, onProgress = { progress += it }, onBatch = { batches += it })
        assertEquals(6, results.size)
        assertEquals((1..6).map { "n$it" }.toSet(), batches.flatten().map { it.id }.toSet())
        assertEquals(6, batches.sumOf { it.size })
        assertEquals(TestStage.DONE, progress.last().stage)
        assertEquals(6, progress.last().alive)
    }

    @Test fun attemptsGiveMedianAndLoss() = runBlocking {
        val results = NodeChecker(FakeTcp(emptySet()), FakeProxy(mapOf("n" to 1)), batchEveryMs = 10)
            .run(listOf(ProbeTarget("n", "host", 443)), attempts = 3)
        val result = results.single()
        assertEquals(CheckStatus.ALIVE, result.status)
        assertEquals(33, result.lossPercent)
        assertEquals(40L, result.latencyMs)
    }

    @Test fun allAttemptsFailMarksUrlFailed() = runBlocking {
        val results = NodeChecker(FakeTcp(emptySet()), FakeProxy(mapOf("n" to 9)), batchEveryMs = 10)
            .run(listOf(ProbeTarget("n", "host", 443)), attempts = 2)
        val result = results.single()
        assertEquals(CheckStatus.URL_FAILED, result.status)
        assertEquals(100, result.lossPercent)
        assertNull(result.latencyMs)
    }

    @Test fun tcpBlackoutFallsBackToUrlTest() = runBlocking {
        val tcp = FakeTcp(setOf("a", "b"))
        val proxy = FakeProxy()
        val progress = mutableListOf<CheckProgress>()
        val results = NodeChecker(tcp, proxy, batchEveryMs = 10).run(
            listOf(ProbeTarget("a", "a", 443), ProbeTarget("b", "b", 443)),
            onProgress = { progress += it }
        )
        assertEquals(setOf("a", "b"), proxy.calls.toSet())
        assertTrue(results.all { it.status == CheckStatus.ALIVE })
        assertTrue(results.all { it.tcpMs == null })
        assertTrue(progress.any { it.fallback })
        assertEquals(2, progress.last().alive)
    }

    @Test fun partialTcpSuccessKeepsFailuresMarked() = runBlocking {
        val tcp = FakeTcp(setOf("dead"))
        val proxy = FakeProxy()
        val progress = mutableListOf<CheckProgress>()
        val results = NodeChecker(tcp, proxy, batchEveryMs = 10).run(
            listOf(ProbeTarget("dead", "dead", 443), ProbeTarget("alive", "alive", 443)),
            onProgress = { progress += it }
        )
        assertEquals(CheckStatus.TCP_FAILED, results.first { it.id == "dead" }.status)
        assertFalse(proxy.calls.contains("dead"))
        assertFalse(progress.any { it.fallback })
    }
    @Test fun emptyTargetsReportDone() = runBlocking {
        val progress = mutableListOf<CheckProgress>()
        val results = NodeChecker(FakeTcp(emptySet()), FakeProxy())
            .run(emptyList(), onProgress = { progress += it })
        assertTrue(results.isEmpty())
        assertEquals(TestStage.DONE, progress.last().stage)
    }
}
