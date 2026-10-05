package org.osada.ui.music

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicQueueTest {
    @Test
    fun staleTabMergesOnlyEditedSettingsAndCampaign() {
        val baseline =
            JSON.parse<dynamic>(
                """{"selected":["old"],"volume":0.2,"autoResume":false,
                "campaignMixes":[{"key":"forward","selected":["old"]}],
                "pendingPresetUpgrades":["forward","greek"]}""",
            )
        val current = JSON.parse<dynamic>(JSON.stringify(baseline))
        current.volume = 0.4
        current.campaignMixes[0].selected = arrayOf("new-forward")
        current.pendingPresetUpgrades = arrayOf("greek")
        val latest =
            JSON.parse<dynamic>(
                """{"selected":["greek"],"volume":0.2,"autoResume":true,
                "campaignMixes":[{"key":"forward","selected":["old"]},{"key":"greek","selected":["greek"]}],
                "pendingPresetUpgrades":["forward"],"userPlaying":true}""",
            )
        val merged = mergeMusicSettings(latest, baseline, current)
        assertEquals(listOf("greek"), MusicCatalog.strings(merged.selected))
        assertEquals(0.4, merged.volume as Double)
        assertTrue(merged.autoResume as Boolean)
        assertTrue(merged.userPlaying as Boolean)
        assertEquals(emptyList(), MusicCatalog.strings(merged.pendingPresetUpgrades))
        val mixes =
            MusicCatalog.array(merged.campaignMixes).associate {
                (it.key as String) to
                    MusicCatalog.strings(it.selected)
            }
        assertEquals(mapOf("forward" to listOf("new-forward"), "greek" to listOf("greek")), mixes)
    }

    @Test
    fun explicitMatchingCanReapplyLocalSelectionAfterAnotherTabChangedIt() {
        val baseline =
            JSON.parse<dynamic>(
                """{"selected":["forward"],"campaignMixes":[{"key":"forward","selected":["forward"]}]}""",
            )
        val latest =
            JSON.parse<dynamic>(
                """{"selected":["greek"],"campaignMixes":[{"key":"forward","selected":["custom"]}]}""",
            )
        val merged = mergeMusicSettings(latest, baseline, baseline, setOf("selected"), "forward")
        assertEquals(listOf("forward"), MusicCatalog.strings(merged.selected))
        assertEquals(listOf("forward"), MusicCatalog.strings(merged.campaignMixes[0].selected))
    }

    @Test
    fun clearingCurrentSelectionCannotReplayItFromForwardHistory() {
        val queue = MusicQueue(Random(11))
        queue.reconcile(setOf("a", "b", "c"), shuffled = false)
        queue.direct("a")
        queue.direct("b")
        assertEquals("a", queue.previous(setOf("a", "b", "c")))
        queue.reconcile(setOf("c"), shuffled = false)
        assertEquals("c", queue.next(setOf("c"), "a"))
        assertNull(queue.next(setOf("c"), "c"))
    }

    @Test
    fun orderedPlaybackUsesSelectionOrderWithoutChangingGameRandomness() {
        val queue = MusicQueue(Random(11))
        queue.reconcile(linkedSetOf("a", "b", "c"), shuffled = false)
        assertEquals(listOf("a", "b", "c"), queue.upcoming)
    }

    @Test
    fun reportedCampaignsChooseTheirWholeThematicCollections() {
        assertEquals(
            listOf("revolution-1917", "civil-war-red-army"),
            MusicPresets.collectionsFor("ccampdfc.json", null),
        )
        assertEquals(listOf("eastern-front", "instrumental"), MusicPresets.collectionsFor("forward.json", null))
        assertEquals(
            listOf("greek-resistance", "greek-democratic-army", "instrumental"),
            MusicPresets.collectionsFor("camp6bn4.json", null),
        )
        assertEquals(listOf("og-scores"), MusicPresets.collectionsFor(null, "battle_cuito.xml"))
        assertEquals(
            listOf("eastern-front", "instrumental"),
            MusicPresets.collectionsFor("forward.json", "battle_cuito.xml"),
        )
        assertNull(MusicPresets.collectionsFor("unknown.json", null))
    }

    @Test
    fun migrationRecognizesOldGeneratedSubsetsButPreservesCustomChoices() {
        val oldCivilWar = setOf("osada-workers-marseillaise", "osada-smelo-tovarishchi")
        assertTrue(MusicPresets.wasLegacyDefault(oldCivilWar, "ccampdfc.json", setOf("instrumental")))
        assertFalse(
            MusicPresets.wasLegacyDefault(oldCivilWar + "osada-budyonny-march", "ccampdfc.json", setOf("instrumental")),
        )
        assertFalse(
            MusicPresets.wasLegacyDefault(
                oldCivilWar - "osada-smelo-tovarishchi",
                "ccampdfc.json",
                setOf("instrumental"),
            ),
        )
        assertFalse(MusicPresets.wasLegacyDefault(emptySet(), "ccampdfc.json", emptySet()))
        assertTrue(MusicPresets.wasLegacyDefault(setOf("instrumental"), "camp6bn4.json", setOf("instrumental")))
    }

    @Test
    fun regionalAdditionsPreserveEarlierInstrumentalChoices() {
        val campaigns =
            mapOf(
                "ncampdfn.json" to "korean-war-dprk",
                "rsoc.json" to "chinese-revolution",
                "nvc.json" to "vietnam-revolutionary",
            )
        campaigns.forEach { (campaign, collection) ->
            assertEquals(listOf(collection, "instrumental"), MusicPresets.collectionsFor(campaign, null))
            assertFalse(MusicPresets.wasLegacyDefault(setOf("instrumental"), campaign, setOf("instrumental")))
        }
        assertEquals(listOf("cuban-revolution", "instrumental"), MusicPresets.collectionsFor(null, "cubali01.xml"))
        assertFalse(MusicPresets.wasLegacyDefault(setOf("instrumental"), null, setOf("instrumental"), "cubali01.xml"))
        assertEquals(listOf("og-scores"), MusicPresets.collectionsFor(null, "vkampala_sfb.xml"))
    }

    @Test
    fun individualHistoricalBattlesUseWholeThemesWithoutChangingCampaignPrecedence() {
        val spanish = listOf("spanish-republic", "antifascist-resistance", "instrumental")
        listOf("madrid_36.xml", "mallorca.xml", "teruel.xml", "ebro.xml", "aran.xml", "bn9s00.xml", "gce2.xml")
            .forEach { assertEquals(spanish, MusicPresets.collectionsFor(null, it)) }
        listOf("1954_dienbienphu.xml", "nvc07.xml").forEach {
            assertEquals(listOf("vietnam-revolutionary", "instrumental"), MusicPresets.collectionsFor(null, it))
        }
        listOf("forward13.xml", "ruscam10.xml", "rcampsev.xml", "ga4_16.xml").forEach {
            assertEquals(listOf("eastern-front", "instrumental"), MusicPresets.collectionsFor(null, it))
        }
        assertEquals(listOf("cuban-revolution", "instrumental"), MusicPresets.collectionsFor(null, "op_hooper.xml"))
        assertEquals(
            listOf("eastern-front", "instrumental"),
            MusicPresets.collectionsFor("forward.json", "madrid_36.xml"),
        )
        assertNull(MusicPresets.collectionsFor(null, "amilastc_sfb.xml"))
        assertNull(MusicPresets.collectionsFor("unknown.json", "madrid_36.xml"))
    }

    @Test
    fun newStandalonePresetsPreserveExistingInstrumentalAndEmptyCustomMixes() {
        listOf("madrid_36.xml", "1954_dienbienphu.xml", "forward13.xml", "op_hooper.xml").forEach {
            assertFalse(MusicPresets.wasLegacyDefault(setOf("instrumental"), null, setOf("instrumental"), it))
            assertFalse(MusicPresets.wasLegacyDefault(emptySet(), null, emptySet(), it))
        }
    }

    @Test
    fun overlappingCollectionsHaveOneEntryPerRecording() {
        val queue = MusicQueue(Random(11))
        queue.reconcile((listOf("a", "b") + listOf("b", "c")).toSet())
        assertEquals(setOf("a", "b", "c"), queue.upcoming.toSet())
        assertEquals(3, queue.upcoming.size)
    }

    @Test
    fun singleTrackStopsAndEmptyPoolCannotRepeatCurrent() {
        val queue = MusicQueue(Random(11))
        queue.reconcile(setOf("a"))
        assertEquals("a", queue.next(setOf("a"), null))
        assertNull(queue.next(setOf("a"), "a"))
        queue.reconcile(emptySet())
        assertNull(queue.next(setOf("a"), "a"))
    }

    @Test
    fun everyCycleVisitsAllRecordingsWithoutBoundaryRepeat() {
        val ids = setOf("a", "b", "c", "d")
        val queue = MusicQueue(Random(91))
        queue.reconcile(ids)
        var current: String? = null
        repeat(20) {
            val cycle =
                (1..ids.size).map {
                    val next = queue.next(ids, current)
                    assertNotEquals(current, next)
                    current = next
                    next
                }
            assertEquals(ids, cycle.toSet())
        }
    }

    @Test
    fun selectionEditsPreserveFutureOrderAndDoNotReinsertHeardTracks() {
        val ids = setOf("a", "b", "c", "d")
        val queue = MusicQueue(Random(41))
        queue.reconcile(ids)
        val heard = queue.next(ids, null)!!
        val before = queue.upcoming.toList()
        queue.reconcile(ids - heard)
        queue.reconcile(ids)
        assertEquals(before, queue.upcoming)
        assertFalse(heard in queue.upcoming)
        queue.reconcile(ids + "new")
        assertEquals(before, queue.upcoming.filter { it != "new" })
        assertTrue("new" in queue.upcoming)
    }

    @Test
    fun previousFollowsHistoryAndNextFollowsForwardHistoryBeforeBag() {
        val ids = setOf("a", "b", "c", "d")
        val queue = MusicQueue(Random(31))
        queue.reconcile(ids)
        val first = queue.next(ids, null)
        val second = queue.next(ids, first)
        val third = queue.next(ids, second)
        val before = queue.upcoming.toList()
        assertEquals(second, queue.previous(ids))
        assertEquals(third, queue.next(ids, second))
        assertEquals(before, queue.upcoming)
        assertEquals(first, queue.previous(ids - second!!))
    }

    @Test
    fun directPlaybackConsumesQueuedTrackAndDoesNotEnableItsSource() {
        val queue = MusicQueue(Random(9))
        queue.reconcile(setOf("a", "b", "c"))
        queue.direct("b")
        assertFalse("b" in queue.upcoming)
        queue.direct("one-off")
        assertEquals("one-off", queue.history.last())
        assertFalse("one-off" in queue.upcoming)
    }

    @Test
    fun excludedHistoryIsSkippedInBothDirections() {
        val queue = MusicQueue(Random(9))
        queue.direct("a")
        queue.direct("b")
        queue.direct("c")
        assertEquals("a", queue.previous(setOf("a", "c")))
        assertEquals("c", queue.next(setOf("a", "c"), "a"))
    }

    @Test
    fun relatedWorksAreSeparatedWhenAnAlternativeIsAvailable() {
        val ids = setOf("a", "b", "c", "d")
        val works = mapOf("a" to "first", "b" to "first", "c" to "second", "d" to "second")
        repeat(40) { seed ->
            val queue = MusicQueue(Random(seed))
            queue.reconcile(ids, works)
            val first = queue.next(ids, null)!!
            assertNotEquals(works[first], works[queue.upcoming.first()])
        }
    }

    @Test
    fun historyRemainsBounded() {
        val queue = MusicQueue(Random(7))
        repeat(150) { queue.direct("recording-$it") }
        assertEquals(100, queue.history.size)
        assertEquals("recording-50", queue.history.first())
        assertEquals(99, queue.cursor)
    }

    @Test
    fun persistedVolumeRejectsNonfiniteAndOutOfRangeValues() {
        assertNull(MusicPreferences.level(Double.NaN))
        assertNull(MusicPreferences.level(-0.1))
        assertNull(MusicPreferences.level(1.1))
        assertNull(MusicPreferences.level("0.5"))
        assertEquals(0.2, MusicPreferences.level(0.2))
    }
}
