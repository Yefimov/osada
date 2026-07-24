package org.osada.ui

import org.osada.GameHolder
import org.osada.hero.FormationIdentity
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroId
import org.osada.model.getUnits

/** Selects and centres the deployed unit commanded by [heroId]. */
@Suppress("ReturnCount")
internal fun locateHero(heroId: HeroId): Boolean {
    val formationId = HeroCampaign.formationIdForHero(heroId) ?: return false
    val game = GameHolder.instance ?: return false
    val unit =
        game.scenario
            ?.map
            ?.getUnits()
            ?.firstOrNull { FormationIdentity.of(it) == formationId } ?: return false
    val ui = game.ui ?: return false
    CommanderRosterPresenter.close()
    LeaderDossierPresenter.close()
    ui.uiUnitSelect(unit)
    unit.getPos()?.let(ui::uiSetCellOnViewPort)
    return true
}
