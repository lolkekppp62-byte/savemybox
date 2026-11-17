package com.savemy.singboxfork.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private fun release(version: String, url: String = "https://example.com/app.apk", pre: Boolean = false) =
        ReleaseInfo(version = version, tag = "v$version", notes = "", apkUrl = url, sizeBytes = 0, prerelease = pre)

    private val payload = """
        [
          {"tag_name":"v0.1.2","prerelease":false,"draft":false,"body":"Заметки","assets":[
            {"name":"SaveMyBox-0.1.2.apk","browser_download_url":"https://github.com/x/y/releases/download/v0.1.2/a.apk","size":123},
            {"name":"checksum.txt","browser_download_url":"https://example.com/b.txt","size":10}]},
          {"tag_name":"v0.0.9","prerelease":true,"draft":false,"body":"","assets":[
            {"name":"SaveMyBox-0.0.9.apk","browser_download_url":"https://example.com/beta.apk","size":1}]},
          {"tag_name":"v0.2.0","prerelease":false,"draft":true,"body":"","assets":[
            {"name":"x.apk","browser_download_url":"https://example.com/draft.apk","size":1}]},
          {"tag_name":"v0.3.0","prerelease":false,"draft":false,"body":"","assets":[]},
          {"tag_name":"v0.4.0","prerelease":false,"draft":false,"body":"","assets":[
            {"name":"bundle.tar.gz","browser_download_url":"https://example.com/l","size":1}]}
        ]
    """.trimIndent()

    @Test fun versionsCompareNumerically() {
        assertEquals(0, UpdateLogic.compare("0.1.2", "0.1.2"))
        assertTrue(UpdateLogic.isNewer("0.1.2", "0.1.1"))
        assertFalse(UpdateLogic.isNewer("0.1.1", "0.1.2"))
        // 0.2 больше 0.1.9, хотя строковое сравнение сказало бы иначе.
        assertTrue(UpdateLogic.isNewer("0.2", "0.1.9"))
        assertTrue(UpdateLogic.isNewer("0.0.10", "0.0.9"))
        assertEquals("0.1.2", UpdateLogic.normalizeTag("v0.1.2"))
    }

    @Test fun betaChannelAcceptsOnlyZeroZeroVersions() {
        val releases = listOf(release("0.1.2"), release("0.0.7"), release("0.0.8", pre = true))
        // Бета живёт отдельной линией: стабильная 0.1.1 «новее» по номеру, но 0.0.8 — это бета-сборка.
        assertEquals("0.0.8", UpdateLogic.available(releases, UpdateChannel.BETA, "0.1.1")?.version)
        assertEquals("0.1.2", UpdateLogic.available(releases, UpdateChannel.STABLE, "0.1.1")?.version)
    }

    @Test fun betaLineComparesWithinItself() {
        val releases = listOf(release("0.0.7"), release("0.0.8"))
        assertNull(UpdateLogic.available(releases, UpdateChannel.BETA, "0.0.8"))
        assertEquals("0.0.8", UpdateLogic.available(releases, UpdateChannel.BETA, "0.0.7")?.version)
        assertNull(UpdateLogic.available(listOf(release("0.0.8")), UpdateChannel.STABLE, "0.1.0"))
    }

    @Test fun stableChannelSkipsBetaAndPrerelease() {
        val releases = listOf(release("0.0.9"), release("0.1.3"), release("0.1.4", pre = true))
        assertEquals("0.1.3", UpdateLogic.available(releases, UpdateChannel.STABLE, "0.1.1")?.version)
    }

    @Test fun installedVersionIsNotAnUpdate() {
        assertNull(UpdateLogic.available(listOf(release("0.1.2")), UpdateChannel.STABLE, "0.1.2"))
        assertNull(UpdateLogic.available(emptyList(), UpdateChannel.BETA, "0.0.1"))
    }

    @Test fun picksHighestOfSeveralCandidates() {
        val releases = listOf(release("0.1.3"), release("0.1.10"), release("0.1.9"))
        assertEquals("0.1.10", UpdateLogic.available(releases, UpdateChannel.STABLE, "0.1.2")?.version)
    }

    @Test fun parsesGithubPayload() {
        val releases = UpdateLogic.parseReleases(payload)
        assertEquals(2, releases.size)
        val stable = releases.first()
        assertEquals("0.1.2", stable.version)
        assertEquals("v0.1.2", stable.tag)
        assertEquals("Заметки", stable.notes)
        assertEquals(123L, stable.sizeBytes)
        assertTrue(stable.apkUrl.endsWith("a.apk"))
        assertFalse(stable.beta)
        assertTrue(releases[1].beta)
        // Черновики и выпуски без APK в список не попадают.
        assertTrue(releases.none { it.version == "0.2.0" || it.version == "0.3.0" || it.version == "0.4.0" })
    }

    @Test fun brokenPayloadGivesEmptyList() {
        assertEquals(emptyList<ReleaseInfo>(), UpdateLogic.parseReleases("не json"))
        assertEquals(emptyList<ReleaseInfo>(), UpdateLogic.parseReleases("{\"message\":\"Not Found\"}"))
        assertEquals(emptyList<ReleaseInfo>(), UpdateLogic.parseReleases("[]"))
    }

    @Test fun channelMatchRules() {
        assertTrue(UpdateLogic.matches(UpdateChannel.BETA, release("0.0.5")))
        assertFalse(UpdateLogic.matches(UpdateChannel.STABLE, release("0.0.5")))
        assertFalse(UpdateLogic.matches(UpdateChannel.STABLE, release("0.1.5", pre = true)))
        assertTrue(UpdateLogic.matches(UpdateChannel.STABLE, release("1.0.0")))
    }
}
