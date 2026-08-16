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

    /** The exact shape `tools/og-import/deploy_campaigns.py` emits for an OG choice node — the
     *  reason three narrative-free imported campaigns were wearing the story badge. */
    @Test
    fun importerGeneratedPathSelectionPromptIsNotStoryContent() {
        val campaign =
            """
            [
              {
                "scenario": "forward15.xml",
                "briefing": {
                  "dialogue": [
                    {
                      "id": "forward17-choice",
                      "speaker": "General Staff",
                      "role": "Path selection",
                      "side": "right",
                      "text": "Choose where you want to fight:",
                      "choices": [
                        { "id": "c0", "text": "HEERESGRUPPE NORTH" },
                        { "id": "c1", "text": "HEERESGRUPPE MITTE" }
                      ]
                    }
                  ]
                }
              }
            ]
            """.trimIndent()

        assertFalse(StoryCampaignDetector.computeStory(campaign))
    }

    /** A path-selection node must not suppress real dialogue elsewhere in the same campaign. */
    @Test
    fun authoredDialogueAlongsideAPathSelectionPromptIsStillStoryContent() {
        val campaign =
            """
            [
              {
                "scenario": "bn4c01.xml",
                "briefing": {
                  "dialogue": [
                    { "speaker": "General Staff", "role": "Path selection", "text": "Choose:" }
                  ]
                }
              },
              {
                "scenario": "bn4s03.xml",
                "briefing": {
                  "dialogue": [
                    { "speaker": "Colonel Davakis", "role": "Pindus Detachment", "text": "They are in the pass." }
                  ]
                }
              }
            ]
            """.trimIndent()

        assertTrue(StoryCampaignDetector.computeStory(campaign))
    }

    /** An authored conversation may branch too: `choices` alone must never disqualify a line. */
    @Test
    fun authoredBranchingDialogueIsStoryContent() {
        val campaign =
            """
            [
              {
                "scenario": "n_kiel.xml",
                "briefing": {
                  "dialogue": [
                    {
                      "speaker": "Karl Artelt",
                      "role": "Sailor, First Torpedo Division",
                      "text": "Do we open the gates?",
                      "choices": [
                        { "id": "c0", "text": "Open them" },
                        { "id": "c1", "text": "Hold the line" }
                      ]
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
