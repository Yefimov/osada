package org.osada.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryCampaignDetectorTest {
    @Test
    fun backgroundOnlyBriefingIsNotStoryContent() {
        val campaign =
            """
            [
              {
                "scenario": "forward0.xml",
                "briefing": {
                  "background": "resources/ui/wallpapers/fc-1.jpg"
                }
              }
            ]
            """.trimIndent()

        assertFalse(StoryCampaignDetector.computeStory(campaign))
    }

    @Test
    fun parsedDialogueMarksCampaignAsStoryContent() {
        val campaign =
            """
            [
              {
                "scenario": "n_kiel.xml",
                "briefing": {
                  "background": "resources/ui/wallpapers/novrev-1.jpg",
                  "dialogue": [
                    {
                      "speaker": "Karl Artelt",
                      "text": "The fleet refuses."
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        assertTrue(StoryCampaignDetector.computeStory(campaign))
    }

    @Test
    fun emptyOrMalformedDialogueDoesNotMarkCampaignAsStoryContent() {
        val campaign =
            """
            [
              {
                "scenario": "test.xml",
                "briefing": {
                  "dialogue": [
                    {
                      "speaker": "",
                      "text": ""
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        assertFalse(StoryCampaignDetector.computeStory(campaign))
    }
}
