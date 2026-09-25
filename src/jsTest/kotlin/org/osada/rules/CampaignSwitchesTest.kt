package org.osada.rules

import org.osada.model.GameUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `.xcam` campaign switches (`rules/CampaignSwitches`), read from the campaign record. */
class CampaignSwitchesTest {
    @Test
    fun theCoreSwitchesAreReadFromTheRecordAndAbsentMeansOff() {
        assertTrue(CampaignSwitches.restartsCore(js("({scenario: 'bn4s19.xml', restartcore: true})")))
        assertTrue(CampaignSwitches.adoptsMainCountry(js("({scenario: 'aljf_4.xml', coremaincountry: true})")))
        assertTrue(CampaignSwitches.skipsScore(js("({noscore: true})")))
        assertFalse(CampaignSwitches.restartsCore(js("({scenario: 'bn4s18.xml'})")))
        assertFalse(CampaignSwitches.adoptsMainCountry(null))
    }

    @Test
    fun disablePurchaseBindsOnlyTheCampaignHuman() {
        val record = js("({nopurchase: true})")
        assertTrue(CampaignSwitches.purchaseForbiddenBy(record, HUMAN, HUMAN))
        assertFalse(CampaignSwitches.purchaseForbiddenBy(record, HUMAN, AI), "the AI still buys")
        assertFalse(CampaignSwitches.purchaseForbiddenBy(js("({})"), HUMAN, HUMAN))
        assertFalse(CampaignSwitches.purchaseForbiddenBy(record, null, HUMAN), "no campaign, no switch")
    }

    /** OG's "at initial HQ": the reserve is refused, a formation already on the map is not. */
    @Test
    fun disableUpgradeCoversTheReserveOnly() {
        val record = js("({noupgrade: true})")
        val reserve = core(deployed = false)
        val onMap = core(deployed = true)
        assertTrue(CampaignSwitches.upgradeForbiddenBy(record, HUMAN, reserve))
        assertFalse(CampaignSwitches.upgradeForbiddenBy(record, HUMAN, onMap))
        assertFalse(CampaignSwitches.upgradeForbiddenBy(js("({})"), HUMAN, reserve))
    }

    private fun core(deployed: Boolean) =
        GameUnit(0).apply {
            owner = HUMAN
            isCore = true
            isDeployed = deployed
        }

    private companion object {
        const val HUMAN = 0
        const val AI = 1
    }
}
