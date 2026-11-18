package com.savemy.singboxfork.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Методы проверки, тестовый URL, таймаут и вид пинга: настройки и их значения по умолчанию. */
class ProbeSettingsTest {
    @Test fun methodUsesItsOwnProfile() {
        val settings = Settings(
            checkMode = CheckMode.FAST,
            headMode = CheckMode.PRECISE,
            getMode = CheckMode.FAST,
            icmpMode = CheckMode.PRECISE
        )
        assertEquals(CheckMode.FAST, settings.modeFor(ProbeMethod.TCP))
        assertEquals(CheckMode.PRECISE, settings.modeFor(ProbeMethod.HTTP_HEAD))
        assertEquals(CheckMode.FAST, settings.modeFor(ProbeMethod.HTTP_GET))
        assertEquals(CheckMode.PRECISE, settings.modeFor(ProbeMethod.ICMP))
    }

    @Test fun customTimeoutOverridesProfile() {
        val profile = CheckMode.FAST
        assertEquals(1_500, Settings().firstTimeout(profile))
        assertEquals(2_000, Settings().urlTimeout(profile))
        val custom = Settings(probeTimeoutMs = 9_000)
        assertEquals(9_000, custom.firstTimeout(profile))
        assertEquals(9_000, custom.urlTimeout(profile))
        // Меньше 500 мс не считается своим таймаутом и не ломает проверку.
        assertEquals(1_500, Settings(probeTimeoutMs = 100).firstTimeout(profile))
    }

    @Test fun probeUrlFallsBackToGoogle() {
        assertEquals(4, ProbeUrls.presets.size)
        assertEquals(ProbeUrls.GOOGLE, ProbeUrls.choose(""))
        assertEquals(ProbeUrls.GOOGLE, ProbeUrls.choose("http://insecure.test"))
        assertEquals(ProbeUrls.CLOUDFLARE, ProbeUrls.choose("  ${ProbeUrls.CLOUDFLARE}  "))
        assertTrue(ProbeUrls.valid("https://example.test/204"))
        assertFalse(ProbeUrls.valid("example.test"))
        assertEquals("Google", ProbeUrls.label(ProbeUrls.GOOGLE))
        assertEquals("Свой", ProbeUrls.label("https://my.test/204"))
    }

    @Test fun displayAndMethodLabels() {
        assertEquals("TCP", ProbeLabels.method(ProbeMethod.TCP))
        assertEquals("HTTP HEAD", ProbeLabels.method(ProbeMethod.HTTP_HEAD))
        assertEquals("HTTP GET", ProbeLabels.method(ProbeMethod.HTTP_GET))
        assertEquals("ICMP", ProbeLabels.method(ProbeMethod.ICMP))
        assertEquals("В цифрах (мс)", ProbeLabels.display(PingDisplay.NUMBERS))
        assertEquals("Шкала", ProbeLabels.display(PingDisplay.BAR))
        assertEquals("Шкала и цифры", ProbeLabels.display(PingDisplay.BAR_NUMBERS))
        assertEquals("Точки", ProbeLabels.display(PingDisplay.DOTS))
    }

    @Test fun defaultsAreSafeAndOldDataStillLoads() {
        val defaults = Settings()
        assertEquals(ProbeMethod.TCP, defaults.probeMethod)
        assertEquals(ProbeUrls.GOOGLE, defaults.probeUrl)
        assertEquals(0, defaults.probeTimeoutMs)
        assertEquals(PingDisplay.NUMBERS, defaults.pingDisplay)
        // Старые записи без новых полей читаются: значения берутся по умолчанию.
        val old = codec.decodeFromString(Settings.serializer(), """{"mtu":1280}""")
        assertEquals(1280, old.mtu)
        assertEquals(ProbeMethod.TCP, old.probeMethod)
        assertEquals(ProbeUrls.GOOGLE, old.probeUrl)
        val restored = codec.decodeFromString(Settings.serializer(), codec.encodeToString(Settings.serializer(), defaults.copy(
            probeMethod = ProbeMethod.ICMP, headMode = CheckMode.PRECISE, probeTimeoutMs = 3_000, pingDisplay = PingDisplay.DOTS
        )))
        assertEquals(ProbeMethod.ICMP, restored.probeMethod)
        assertEquals(CheckMode.PRECISE, restored.headMode)
        assertEquals(3_000, restored.probeTimeoutMs)
        assertEquals(PingDisplay.DOTS, restored.pingDisplay)
    }
}
