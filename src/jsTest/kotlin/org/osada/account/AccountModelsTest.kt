package org.osada.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountModelsTest {
    private fun parse(text: String): dynamic = JSON.parse(text)

    @Test
    fun aServerWithoutAccountsOrWithoutKnownProvidersIsOff() {
        assertEquals(AccountServerConfig.OFF, AccountJson.config(parse("""{"enabled":false}""")))
        assertEquals(AccountServerConfig.OFF, AccountJson.config(null))
        assertFalse(AccountJson.config(parse("""{"enabled":true,"providers":["myspace"]}""")).enabled)
    }

    @Test
    fun configKeepsOnlyKnownProvidersInServerOrder() {
        val config =
            AccountJson.config(
                parse("""{"enabled":true,"signupEnabled":false,"providers":["github","evil","google"]}"""),
            )
        assertTrue(config.enabled)
        assertFalse(config.signupEnabled)
        assertEquals(listOf("github", "google"), config.providers)
    }

    @Test
    fun signedOutOrIncompleteProfilesAreGuests() {
        assertNull(AccountJson.profile(parse("""{"signedIn":false}""")))
        assertNull(AccountJson.profile(parse("""{"signedIn":true,"accountId":"a_1"}""")), "no CSRF token, no session")
    }

    @Test
    fun profileCarriesIdentitiesUsageAndTheRetentionDeadline() {
        val profile =
            AccountJson.profile(
                parse(
                    """{"signedIn":true,"accountId":"a_1","csrf":"t","createdAt":1000,"lastActiveAt":2000,
                       "dataEpoch":3,"usedBytes":512,"quotaBytes":67108864,
                       "identities":[{"provider":"google","linkedAt":1000},{"provider":"github","linkedAt":1500}]}""",
                ),
            )!!
        assertEquals("a_1", profile.accountId)
        assertEquals(3, profile.dataEpoch)
        assertEquals(listOf("google", "github"), profile.identities.map { it.provider })
        assertTrue(profile.hasProvider("github"))
        assertEquals(2000.0 + 365.0 * 86_400_000.0, profile.retentionDeadline)
    }

    @Test
    fun onlyAWellFormedFragmentIsAResult() {
        assertEquals("signed-in", AccountRedirect.resultCode("#osada-account=signed-in"))
        assertNull(AccountRedirect.resultCode("#other=signed-in"))
        assertNull(AccountRedirect.resultCode("#osada-account=<img src=x>"))
        assertNull(AccountRedirect.resultCode(""))
    }

    @Test
    fun unknownResultCodesShareTheGenericMessage() {
        assertEquals("account.result.identity-in-use", AccountRedirect.messageKey("identity-in-use"))
        assertEquals("account.result.failed", AccountRedirect.messageKey("server-error"))
        assertEquals("account.result.failed", AccountRedirect.messageKey("provider-refused"))
    }
}
