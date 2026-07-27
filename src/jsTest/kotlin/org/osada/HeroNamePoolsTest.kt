package org.osada

import org.osada.hero.HeroNamePools
import org.osada.hero.HeroNaming
import org.osada.hero.PortraitComposerV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §4.11: a hero the portrait draws as a woman must also carry a woman's name, not a man's given
 * name with an unmarked surname. [HeroNaming.nameFor] must agree with
 * [PortraitComposerV2.genderFor] of the same seed -- that is the whole point of deriving gender
 * from the seed in one place instead of rolling it twice.
 */
class HeroNamePoolsTest {
    private val soviet = 19 // HeroNamePools.byCountry
    private val german = 7

    @Test
    fun femaleHeroesGetAFemaleGivenNameFromTheCountrysPool() {
        val femaleSeed = (0..2000).first { PortraitComposerV2.genderFor(it) == "female" }
        val name = HeroNaming.nameFor(femaleSeed, soviet)
        val given = name.substringBefore(" ")

        assertTrue(
            given in HeroNamePools.poolFor(soviet).givenNamesFemale,
            "\"$given\" (from seed $femaleSeed) must come from the Soviet female given-name pool",
        )
        assertTrue(
            given !in HeroNamePools.poolFor(soviet).givenNames,
            "\"$given\" must not be one of the male given names",
        )
    }

    @Test
    fun maleHeroesGetAMaleGivenNameFromTheCountrysPool() {
        val maleSeed = (0..2000).first { PortraitComposerV2.genderFor(it) == "male" }
        val name = HeroNaming.nameFor(maleSeed, soviet)
        val given = name.substringBefore(" ")

        assertTrue(given in HeroNamePools.poolFor(soviet).givenNames)
    }

    @Test
    fun sovietFemaleSurnamesAreFeminized() {
        // slavicOvFeminine only rewrites -ov/-ev/-in/-sky surnames; every entry in the Soviet pool
        // matches one of those, so every feminized surname must end in "a".
        val femaleSeed = (0..2000).first { PortraitComposerV2.genderFor(it) == "female" }
        val name = HeroNaming.nameFor(femaleSeed, soviet)
        val surname = name.substringAfter(" ")

        assertTrue(surname.endsWith("a"), "\"$surname\" should be feminized (e.g. Ivanov -> Ivanova)")
    }

    @Test
    fun culturesWithoutInflectedSurnamesKeepTheSurnameUnchangedForBothGenders() {
        val maleSeed = (0..2000).first { PortraitComposerV2.genderFor(it) == "male" }
        val femaleSeed = (0..2000).first { PortraitComposerV2.genderFor(it) == "female" }
        val pool = HeroNamePools.poolFor(german)

        assertEquals(null, pool.femininizeSurname, "German surnames don't inflect by gender in this pool")
        val maleName = HeroNaming.nameFor(maleSeed, german)
        val femaleName = HeroNaming.nameFor(femaleSeed, german)
        assertTrue(maleName.substringAfter(" ") in pool.surnames)
        assertTrue(femaleName.substringAfter(" ") in pool.surnames)
    }

    @Test
    fun nameForIsDeterministic() {
        val first = HeroNaming.nameFor(4242, soviet)
        val second = HeroNaming.nameFor(4242, soviet)

        assertEquals(first, second)
    }
}
