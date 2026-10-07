package org.osada.ui

import kotlinx.browser.document
import org.osada.account.AccountProfile
import org.osada.account.AccountServerConfig
import org.osada.account.LinkedProvider
import org.osada.account.OnlineAccount
import org.osada.i18n.installEnglishUiBundleForTests
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OnlineSavesPanelTest {
    private val both = AccountServerConfig(enabled = true, signupEnabled = true, providers = listOf("google", "github"))

    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        if (document.getElementById("mainbody") == null) {
            document.body!!.appendChild(document.createElement("div").also { it.id = "mainbody" })
        }
    }

    @AfterTest
    fun teardown() {
        OnlineSavesPanel.close()
        OnlineAccount.apply(AccountServerConfig.OFF, null)
        document.getElementById("onlineSavesEntryHost")?.remove()
    }

    private fun buttons(): List<String> =
        document
            .querySelectorAll("#osadaOnlineSaves button")
            .asList()
            .map { (it as HTMLElement).textContent.orEmpty() }

    private fun profile(vararg providers: String) =
        AccountProfile(
            accountId = "a_1",
            csrf = "csrf",
            createdAt = 0.0,
            lastActiveAt = 0.0,
            dataEpoch = 1,
            usedBytes = 2048.0,
            quotaBytes = 64.0 * 1024 * 1024,
            identities = providers.map { LinkedProvider(it, 0.0) },
        )

    @Test
    fun theEntryRowStaysHiddenWhileTheServerOffersNoAccounts() {
        val host = document.createElement("div") as HTMLElement
        host.id = "onlineSavesEntryHost"
        document.body!!.appendChild(host)
        OnlineSavesPanel.buildEntry(host)
        val entry = assertNotNull(document.getElementById("onlineSavesEntry") as? HTMLElement)
        OnlineAccount.apply(AccountServerConfig.OFF, null)
        assertEquals("none", entry.style.display)
        OnlineAccount.apply(both, null)
        assertEquals("", entry.style.display)
        assertEquals("Online saves · Off", entry.textContent)
        OnlineAccount.apply(both, profile("google"))
        assertEquals("Online saves · Signed in", entry.textContent)
    }

    @Test
    fun signedOutOffersEachProviderAndAGuestWayOut() {
        OnlineAccount.apply(both, null)
        OnlineSavesPanel.open(result = null, refresh = false)
        val labels = buttons()
        assertTrue("Continue with Google" in labels, labels.toString())
        assertTrue("Continue with GitHub" in labels, labels.toString())
        assertTrue("Keep playing without an account" in labels, labels.toString())
        assertEquals("dialog", document.getElementById("osadaOnlineSaves")?.getAttribute("role"))
    }

    @Test
    fun aSingleIdentityCannotBeUnlinkedAndTheMissingProviderCanBeAdded() {
        OnlineAccount.apply(both, profile("google"))
        OnlineSavesPanel.open(result = null, refresh = false)
        val labels = buttons()
        assertFalse("Unlink" in labels, labels.toString())
        assertTrue("Also sign in with GitHub" in labels, labels.toString())
        val text = document.getElementById("osadaOnlineSaves")!!.textContent!!
        assertTrue(text.contains("Online storage: 2 KB of 64 MB"), text)
    }

    @Test
    fun aProviderResultIsAnnouncedAsTextNotMarkup() {
        OnlineAccount.apply(both, null)
        OnlineSavesPanel.open(result = "identity-in-use", refresh = false)
        val status = assertNotNull(document.getElementById("osadaOnlineSaves-status"))
        assertTrue(status.textContent!!.startsWith("That sign-in already belongs"))
        assertEquals(0, status.children.length)
    }
}
