package org.osada.ui.briefing

import kotlinx.browser.localStorage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BriefingIntroTrackerTest {
    @BeforeTest
    @AfterTest
    fun clearTracker() {
        BriefingIntroTracker.reset()
    }

    @Test
    fun unseenScenarioReportsNotSeen() {
        assertFalse(BriefingIntroTracker.isSeen("camp6bn9.json", "bn9s00.xml"))
    }

    @Test
    fun markSeenRoundTripsWithinTheSameCampaign() {
        BriefingIntroTracker.markSeen("camp6bn9.json", "bn9s00.xml")

        assertTrue(BriefingIntroTracker.isSeen("camp6bn9.json", "bn9s00.xml"))
        assertFalse(BriefingIntroTracker.isSeen("camp6bn9.json", "bn9s01.xml"))
    }

    @Test
    fun switchingCampaignFilesDropsThePreviousRecord() {
        BriefingIntroTracker.markSeen("camp6bn9.json", "bn9s00.xml")
        BriefingIntroTracker.markSeen("other-campaign.json", "rd01.xml")

        assertFalse(BriefingIntroTracker.isSeen("camp6bn9.json", "bn9s00.xml"))
        assertTrue(BriefingIntroTracker.isSeen("other-campaign.json", "rd01.xml"))
    }

    @Test
    fun resetClearsAllSeenRecords() {
        BriefingIntroTracker.markSeen("camp6bn9.json", "bn9s00.xml")

        BriefingIntroTracker.reset()

        assertFalse(BriefingIntroTracker.isSeen("camp6bn9.json", "bn9s00.xml"))
    }

    @Test
    fun corruptStoredPayloadDegradesToNotSeenRatherThanThrowing() {
        localStorage.setItem("osada-briefing-seen", "{not json")

        assertFalse(BriefingIntroTracker.isSeen("camp6bn9.json", "bn9s00.xml"))
    }
}
