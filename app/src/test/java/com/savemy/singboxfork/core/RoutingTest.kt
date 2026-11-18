package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RoutingTest {
    private fun node() = Importer.parse("trojan://p@example.com:443#T").single()

    private fun config(profile: RoutingProfile, dir: String? = null, advanced: String? = null): kotlinx.serialization.json.JsonObject =
        codec.parseToJsonElement(
            ConfigFactory.build(CoreSpec(AppData(listOf(node()), Settings(routing = profile)), true, 12345, "s", "log",
                ruleSetsDir = dir, advancedJson = advanced))
        ).jsonObject

    private fun rules(cfg: kotlinx.serialization.json.JsonObject) =
        cfg["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    @Test fun onlyExistingRuleSetsAreWired() {
        val dir = Files.createTempDirectory("rules").toFile()
        File(dir, "ru.srs").writeBytes(byteArrayOf(1, 2, 3))
        val cfg = config(RoutingProfile(ruleSets = listOf(
            RuleSetFile(file = "ru.srs", tag = "ru"),
            RuleSetFile(file = "missing.srs", tag = "missing")
        )), dir.path)
        val route = cfg["route"]!!.jsonObject
        val sets = route["rule_set"]!!.jsonArray
        assertEquals(1, sets.size)
        assertEquals("local", sets[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("ru", sets[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("binary", sets[0].jsonObject["format"]!!.jsonPrimitive.content)
        assertTrue(sets[0].jsonObject["path"]!!.jsonPrimitive.content.endsWith("ru.srs"))
        assertTrue(rules(cfg).any { it["rule_set"]?.jsonPrimitive?.content == "ru" && it["outbound"]?.jsonPrimitive?.content == "direct" })
    }

    @Test fun ruleSetOutsideDirectoryIsIgnored() {
        val dir = Files.createTempDirectory("rules").toFile()
        val outside = File(dir.parentFile, "escape.srs").apply { writeBytes(byteArrayOf(1)) }
        try {
            val cfg = config(RoutingProfile(ruleSets = listOf(RuleSetFile(file = "../escape.srs", tag = "esc"))), dir.path)
            assertNull(cfg["route"]!!.jsonObject["rule_set"])
        } finally {
            outside.delete()
        }
    }

    @Test fun blockRulesPrecedeDirectRules() {
        val cfg = config(RoutingProfile(directDomains = listOf("example.com"), blockDomains = listOf("ads.example"), blockPackages = listOf("com.evil")))
        val list = rules(cfg)
        val block = list.indexOfFirst { r -> r["domain_suffix"]?.jsonArray?.any { it.jsonPrimitive.content == "ads.example" } == true }
        val direct = list.indexOfFirst { r -> r["domain_suffix"]?.jsonArray?.any { it.jsonPrimitive.content == "example.com" } == true }
        assertTrue(block >= 0)
        assertTrue(direct > block)
        assertEquals("reject", list[block]["action"]!!.jsonPrimitive.content)
        assertEquals("direct", list[direct]["outbound"]!!.jsonPrimitive.content)
        assertTrue(list.any { r -> r["package_name"]?.jsonArray?.any { it.jsonPrimitive.content == "com.evil" } == true && r["action"]!!.jsonPrimitive.content == "reject" })
    }

    @Test fun advancedJsonMergesButKeepsLocalApi() {
        val cfg = config(RoutingProfile(), advanced = """{"route":{"final":"direct"},"log":{"level":"debug"},"experimental":{"clash_api":{"external_controller":"0.0.0.0:1","secret":"x"}}}""")
        assertEquals("debug", cfg["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertEquals("direct", cfg["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        assertTrue(cfg["route"]!!.jsonObject["rules"]!!.jsonArray.isNotEmpty())
        val clash = cfg["experimental"]!!.jsonObject["clash_api"]!!.jsonObject
        assertEquals("127.0.0.1:12345", clash["external_controller"]!!.jsonPrimitive.content)
        assertEquals("s", clash["secret"]!!.jsonPrimitive.content)
    }

    @Test fun advancedJsonMustBeObject() {
        listOf("[]", "{", "\"text\"").forEach { bad ->
            try {
                config(RoutingProfile(), advanced = bad)
                fail("должно быть отклонено")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.orEmpty().contains("secret"))
            }
        }
    }

    @Test fun bootstrapDnsHasNoExplicitDirectDetour() {
        val cfg = config(RoutingProfile())
        val bootstrap = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray.first().jsonObject
        assertEquals("bootstrap", bootstrap["tag"]!!.jsonPrimitive.content)
        assertEquals("udp", bootstrap["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", bootstrap["server"]!!.jsonPrimitive.content)
        // Явный detour на пустой direct ядро отвергает: «detour to an empty direct outbound makes no sense».
        assertNull(bootstrap["detour"])
        assertEquals("direct", cfg["outbounds"]!!.jsonArray.first { it.jsonObject["tag"]?.jsonPrimitive?.content == "direct" }.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun remoteDnsStillDetoursThroughProxy() {
        val dns = config(RoutingProfile())["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
        val remote = dns.first { it["tag"]!!.jsonPrimitive.content == "remote" }
        assertEquals("proxy", remote["detour"]!!.jsonPrimitive.content)
    }

    @Test fun noRuleSetsWithoutDirectory() {
        val cfg = config(RoutingProfile(ruleSets = listOf(RuleSetFile(file = "ru.srs", tag = "ru"))), dir = null)
        assertNull(cfg["route"]!!.jsonObject["rule_set"])
    }

    @Test fun directPackagesPrecedeRuleSetsAndDomains() {
        val dir = Files.createTempDirectory("rules").toFile()
        File(dir, "ru.srs").writeBytes(byteArrayOf(1))
        val cfg = config(RoutingProfile(
            directPackages = listOf("com.direct.app"),
            directDomains = listOf("example.com"),
            ruleSets = listOf(RuleSetFile(file = "ru.srs", tag = "ru"))
        ), dir.path)
        val list = rules(cfg)
        val pkg = list.indexOfFirst { r -> r["package_name"]?.jsonArray?.any { it.jsonPrimitive.content == "com.direct.app" } == true }
        val set = list.indexOfFirst { r -> r["rule_set"]?.jsonPrimitive?.content == "ru" }
        val domain = list.indexOfFirst { r -> r["domain_suffix"]?.jsonArray?.any { it.jsonPrimitive.content == "example.com" } == true }
        assertTrue(pkg >= 0)
        assertEquals("direct", list[pkg]["outbound"]!!.jsonPrimitive.content)
        assertTrue(pkg < set)
        assertTrue(set < domain)
    }
}
