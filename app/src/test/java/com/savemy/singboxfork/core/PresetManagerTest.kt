package com.savemy.singboxfork.core

import com.savemy.singboxfork.data.Importer
import com.savemy.singboxfork.data.RoutingMode
import com.savemy.singboxfork.data.RoutingProfile
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetManagerTest {
    @Test fun bothSidesHaveFivePresetPacks() {
        assertEquals(5, PresetManager.bySide(PresetSide.WHITELIST).size)
        assertEquals(5, PresetManager.bySide(PresetSide.BLACKLIST).size)
        assertEquals(PresetManager.presets.size, PresetManager.presets.map { it.id }.distinct().size)
        assertTrue(PresetManager.presets.all { it.domains.isNotEmpty() })
        assertTrue(PresetManager.defaultIds.all { PresetManager.byId(it)?.side == PresetSide.WHITELIST })
    }

    @Test fun packsCarryExpectedBrands() {
        val youtube = PresetManager.byId(PresetManager.YOUTUBE)!!
        assertTrue("googlevideo.com" in youtube.domains)
        assertTrue("app.revanced.android.youtube" in youtube.packages)
        val ai = PresetManager.byId(PresetManager.AI)!!
        assertTrue("generativelanguage.googleapis.com" in ai.domains)
        assertTrue(ai.packages.any { it.contains("openai") })
        val gov = PresetManager.byId(PresetManager.GOV)!!
        assertTrue("gosuslugi.ru" in gov.domains && "ru" in gov.domains && "рф" in gov.domains)
        assertTrue(PresetManager.byId(PresetManager.LAN)!!.cidrs.any { it == "192.168.0.0/16" })
    }

    @Test fun installedAppsAreIntersected() {
        val youtube = PresetManager.byId(PresetManager.YOUTUBE)!!
        val installed = setOf("com.google.android.youtube", "com.android.chrome")
        assertEquals(listOf("com.google.android.youtube"), PresetManager.installedApps(youtube, installed))
        assertEquals(0, PresetManager.installedApps(youtube, emptySet()).size)
        assertEquals(
            listOf("com.discord"),
            PresetManager.installedApps(setOf(PresetManager.DISCORD), PresetSide.BLACKLIST, setOf("com.discord", "ru.ozon.app.android"))
        )
    }

    @Test fun whitelistPresetsBecomeDirectRules() {
        val routing = SmartRouter.effective(RoutingProfile(), RoutingMode.WHITELIST_BYPASS, setOf(PresetManager.GOV, PresetManager.BANKS))
        assertTrue("gosuslugi.ru" in routing.directDomains)
        assertTrue("ru" in routing.directDomains)
        assertTrue("sberbank.ru" in routing.directDomains)
        assertTrue(routing.proxyDomains.isEmpty())
        assertEquals("proxy", routing.finalOutbound)
        // БС: приложения белого списка исключены из TUN, а не ограничены им.
        assertTrue(routing.excludedApps.isNotEmpty())
        assertTrue(routing.includedApps.isEmpty())
    }

    @Test fun blacklistPresetsBecomeProxyRules() {
        val routing = SmartRouter.effective(RoutingProfile(), RoutingMode.BLACKLIST_PROXY, setOf(PresetManager.YOUTUBE, PresetManager.AI))
        assertTrue("youtube.com" in routing.proxyDomains)
        assertTrue("chatgpt.com" in routing.proxyDomains)
        assertTrue("com.google.android.youtube" in routing.proxyPackages)
        assertEquals("direct", routing.finalOutbound)
        // ЧС: в TUN попадают только приложения чёрного списка.
        assertTrue(routing.includedApps.contains("com.discord").not())
        assertTrue(routing.includedApps.contains("com.google.android.youtube"))
        assertTrue(routing.excludedApps.isEmpty())
    }

    @Test fun manualRulesAndTorrentSurviveMapping() {
        val profile = RoutingProfile(
            directDomains = listOf("Example.COM"),
            proxyDomains = listOf("https://OpenAI.com/v1"),
            blockPackages = listOf("com.evil")
        )
        val routing = SmartRouter.effective(profile, RoutingMode.WHITELIST_BYPASS, setOf(PresetManager.LAN), installed = emptySet(),
            extraDirectApps = setOf("com.manual.direct"), extraProxyApps = setOf("com.manual.proxy"))
        assertTrue("example.com" in routing.directDomains)
        assertTrue("openai.com" in routing.proxyDomains)
        assertTrue("com.manual.direct" in routing.directPackages)
        assertTrue("com.manual.proxy" in routing.proxyPackages)
        assertTrue(routing.torrentDirect)
        assertTrue(routing.directCidrs.contains("10.0.0.0/8"))
        assertEquals(listOf("com.evil"), routing.blockPackages)
    }

    @Test fun configAppliesBlacklistRulesAndDirectFinal() {
        val node = Importer.parse("trojan://p@example.com:443#T").single()
        val settings = com.savemy.singboxfork.data.Settings(
            presetIds = setOf(PresetManager.YOUTUBE, PresetManager.GOV),
            routingMode = RoutingMode.BLACKLIST_PROXY
        )
        val data = com.savemy.singboxfork.data.AppData(listOf(node), settings)
        val cfg = com.savemy.singboxfork.data.codec.parseToJsonElement(
            ConfigFactory.build(CoreSpec(data, true, 1234, "s", "log"))
        ).jsonObject
        val route = cfg["route"]!!.jsonObject
        assertEquals("direct", route["final"]!!.jsonPrimitive.content)
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val proxyDomain = rules.indexOfFirst { r -> r["domain_suffix"]?.jsonArray?.any { it.jsonPrimitive.content == "youtube.com" } == true }
        val directDomain = rules.indexOfFirst { r -> r["domain_suffix"]?.jsonArray?.any { it.jsonPrimitive.content == "gosuslugi.ru" } == true }
        assertTrue(proxyDomain >= 0)
        assertTrue(directDomain >= 0)
        // Явный ЧС проверяется раньше доменного суффикса БС.
        assertTrue(proxyDomain < directDomain)
        assertEquals("proxy", rules[proxyDomain]["outbound"]!!.jsonPrimitive.content)
        // ЧС не исключает приложения из TUN: используется addAllowedApplication.
        assertTrue(cfg["inbounds"]!!.jsonArray.first().jsonObject["exclude_package"]!!.jsonArray.isEmpty())
    }

    @Test fun whitelistModeExcludesAppsFromTun() {
        val node = Importer.parse("trojan://p@example.com:443#T").single()
        val settings = com.savemy.singboxfork.data.Settings(
            presetIds = setOf(PresetManager.GOV),
            bypassPackages = setOf("com.example.reader")
        )
        val data = com.savemy.singboxfork.data.AppData(listOf(node), settings)
        val cfg = com.savemy.singboxfork.data.codec.parseToJsonElement(
            ConfigFactory.build(CoreSpec(data, true, 1234, "s", "log"))
        ).jsonObject
        assertEquals("proxy", cfg["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        val excluded = cfg["inbounds"]!!.jsonArray.first().jsonObject["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(excluded.contains("com.example.reader"))
        assertTrue(excluded.contains("ru.rostel"))
        assertFalse(excluded.contains("com.google.android.youtube"))
    }
}
