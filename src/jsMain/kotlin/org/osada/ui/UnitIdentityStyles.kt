package org.osada.ui

import kotlinx.browser.document

/**
 * Installs the identity/transparency styles without modifying the shared theme file.
 * Keeping this feature stylesheet isolated makes the patch tolerant of local theme customisation.
 */
internal object UnitIdentityStyles {
    private const val STYLE_ID = "osada-unit-identity-styles"

    // LongMethod false-positive: the function body is 4 statements: check style tag doesn't exist,
    // set its content to a CSS literal (most of the "length"), append. Splitting the CSS text into
    // several string constants would not change what the function does, only how detekt counts it.
    @Suppress("LongMethod")
    fun ensureInstalled() {
        if (document.getElementById(STYLE_ID) != null) return
        val style = document.createElement("style")
        style.id = STYLE_ID
        style.textContent =
            """
/* ============================================================================
 * UNIT IDENTITY / COMBAT TRANSPARENCY / SERVICE RECORD (2026-07-23)
 * ============================================================================ */
/* The unit card's own track was capped at 600px while the zone is ~1650px wide and the other two
   tracks (hover forecast, enemy card) are hidden most of the time -- which is the "there is plenty
   of space" in the report about the name and the commander line being cramped. 840px still leaves
   840+150+460 = 1450 < 1650, so the forecast and enemy card lose nothing when they do appear.

   `fit-content(840px)`, not `minmax(420px, 840px)`: the TRACK hugs the card, instead of the card
   hugging inside a track that stays 840px wide. With minmax, a 670px card in an 840px track left
   170px of dead grid between it and the forecast -- reported as *"on a wide screen there is too
   much room to the left of #osadaForecast and to the right of #uc-inner"*. 840px is still the
   ceiling; the floor moved onto the card as `min-width`, since fit-content() takes no minimum. */
#osada-bottomzone { height: 112px; grid-template-columns: fit-content(840px) 150px minmax(340px, 460px); }
/* The card fills its (now content-sized) track. `min-width` is what stops a one-word unit name
   from shrinking the whole card to a stub, and it is also the track's automatic minimum. */
#unit-info { padding: 7px 10px; width: auto; min-width: 420px; max-width: 100%; }
#uc-inner { gap: 9px; }
#uc-main { justify-content: flex-start; gap: 3px; }
/* ONE line, always. A second line of name pushed the whole card down by ~16px, which is what put
   the FUEL bar past the bottom of the zone ("for 32nd 76mm 29-K I can't see the FUEL"). The full
   name is never lost -- #uName carries it as a title, set in UnitStatCard.showUnitInfo. */
#uName {
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; line-height: 1.15;
    text-transform: none; font-size: 14px;
}

/* OSADA compact unit name / rename / commander alignment: all three share one row. */
#uc-nameline {
    display: flex; align-items: center; justify-content: flex-start;
    gap: 4px; min-width: 0; width: 100%;
}
/* The name box is exactly as wide as the name (`flex-grow: 0`) -- it used to grow into all the
   free width, which is the blank the report is about. It still gets first claim when the row is
   full: both it and the commander line may shrink, but the commander line yields three times as
   fast, because a truncated unit name is worse than a truncated "no permanent commander". */
#uName {
    flex: 0 1 auto; width: auto; max-width: 100%; min-width: 0;
}
#ucRename {
    position: static !important; inset: auto !important; flex: 0 0 auto;
    width: 20px; min-width: 20px; margin: 0; align-self: center;
}
/* Follows the name instead of being pinned to the far right, so the two read as one line rather
   than as two ends of an empty one; it is also the half that gives way when the row is full.
   NO max-width: the card is `width: fit-content`, so a 45% clamp truncated the commander line
   ("Hero yet to eme...") while the card still had room to grow -- reported for 14th T-34/41. The
   name is protected by the 3x shrink factor here, which is what the clamp was standing in for. */
#uc-commandline {
    flex: 0 3 auto; min-width: 0;
    display: flex; align-items: center; justify-content: flex-start;
}
.uc-commander-line { text-align: left; }
/* The class/status chips must not stack either: the row wraps only as a last resort, and the
   longest chip (unit class + "SCENARIO PART") ellipsises instead of taking a second line. */
#osadaUcClass { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: block; }
.uc-commander-line {
    position: static; flex: 1 1 auto; min-width: 0; height: auto; width: auto;
    display: block; padding: 1px 0; border: 0; border-radius: 0; background: none;
    color: var(--osada-text-dim); font-size: 11px; line-height: 1.2; cursor: pointer;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.uc-commander-line:hover, .uc-commander-line:focus { color: var(--osada-brass); outline: none; }
.uc-commander-line--hero { color: #e5c979; font-weight: bold; }
.uc-commander-line--candidate { color: #b9ad8c; font-style: italic; }
.uc-commander-line--disabled { color: #777; cursor: default; }
#uc-statusline { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 3px; min-height: 18px; }
.uc-chip, .osada-ec-chip {
    display: inline-flex; align-items: center; min-height: 16px; padding: 0 5px; box-sizing: border-box;
    border: 1px solid var(--osada-metal-600); border-radius: 3px; background: rgba(0,0,0,.28);
    color: var(--osada-text-dim); font-size: 9px; font-weight: bold; letter-spacing: .04em; text-transform: uppercase;
}
.uc-chip--support { color: #e5c979; border-color: rgba(217,178,90,.65); background: rgba(217,178,90,.10); }
.uc-chip--warning { color: #d98b79; border-color: rgba(201,70,61,.5); }
#uc-bars { gap: 2px; }
#uc-actions { align-self: center; }
.osada-action:focus { outline: 1px solid var(--osada-brass); outline-offset: 1px; }
.osada-formation-detail__summary { padding: 2px 0; color: var(--osada-text-dim); font-size: 11px; }
.osada-service-record-button {
    margin: 7px 0 5px; padding: 5px 9px; cursor: pointer; color: #f4e3c6;
    background: linear-gradient(180deg, #8f2926, #681916); border: 1px solid var(--osada-brass);
    border-radius: 3px; font-size: 10px; font-weight: bold; letter-spacing: .08em;
}
.osada-service-record-button:hover { background: linear-gradient(180deg, #aa3530, #7a1e1a); }
.osada-service-record-overlay {
    position: fixed; inset: 0; z-index: var(--z-msg); display: flex; align-items: center; justify-content: center;
    padding: 24px; box-sizing: border-box; background: rgba(4,5,7,.78); backdrop-filter: blur(2px);
}
.osada-service-record {
    width: min(760px, 94vw); max-height: min(760px, 90vh); overflow: hidden; display: flex; flex-direction: column;
    color: var(--osada-text); background: linear-gradient(180deg, #252a31, #111318);
    border: 2px solid var(--osada-brass); border-radius: 6px; box-shadow: 0 24px 80px rgba(0,0,0,.85);
}
.osada-service-record__header {
    flex: 0 0 auto; display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
    padding: 16px 18px 12px; border-bottom: 1px solid var(--osada-metal-600);
}
.osada-service-record__eyebrow { color: var(--osada-brass); font-size: 10px; font-weight: bold; letter-spacing: .12em; text-transform: uppercase; }
.osada-service-record__title { margin: 3px 0 0; color: #f4e3c6; font-size: 22px; }
.osada-service-record__sub { margin-top: 3px; color: var(--osada-text-dim); font-size: 12px; }
.osada-service-record__close {
    flex: 0 0 auto; width: 32px; height: 32px; cursor: pointer; color: var(--osada-text);
    background: #1b1e24; border: 1px solid var(--osada-metal-600); border-radius: 3px; font-size: 21px;
}
.osada-service-record__close:hover { color: var(--osada-brass); border-color: var(--osada-brass); }
.osada-service-record__summary {
    flex: 0 0 auto; display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 7px 14px;
    padding: 12px 18px; background: rgba(0,0,0,.20); border-bottom: 1px solid var(--osada-metal-600);
}
.osada-service-record__kv { display: flex; justify-content: space-between; gap: 8px; font-size: 11px; }
.osada-service-record__key { color: var(--osada-text-dim); }
.osada-service-record__value { color: var(--osada-text); text-align: right; }
.osada-service-record__history { min-height: 0; overflow-y: auto; padding: 14px 18px 18px; }
.osada-service-record__section-title { margin: 0 0 10px; color: var(--osada-brass); font-size: 12px; letter-spacing: .08em; text-transform: uppercase; }
.osada-service-record__event { padding: 9px 10px; margin-bottom: 7px; border-left: 2px solid var(--osada-brass); background: rgba(0,0,0,.22); }
.osada-service-record__event-title { color: var(--osada-text); font-size: 13px; font-weight: bold; }
.osada-service-record__event-context, .osada-service-record__empty { margin-top: 3px; color: var(--osada-text-dim); font-size: 11px; }
.osada-ec-chips { display: flex; flex-wrap: wrap; gap: 3px; margin: 1px 0; }
.osada-ec-chip { color: #d8b4b4; border-color: rgba(139,75,75,.7); background: rgba(91,31,31,.18); }
/* DEFERRED.md §1.20: the ENT chip keeps showing the real entrenchment value (it IS still that
 * entrenched) but struck through, because the selected attacker will ignore it. The paired
 * ENT BYPASSED chip says why; this one says which number stops counting. */
.osada-ec-chip--struck { text-decoration: line-through; opacity: .6; }
#ecMain { gap: 2px; }
.osada-ec-name { white-space: normal; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.osada-ec-stat { cursor: help; }

/* Reserve enough scrollable room below the map for the fixed identity card. The spacer is outside
 * the terrain/canvas content, so the final hex row can move above the HUD instead of being veiled. */
#mainbody:not(.osada-strategic) #game { padding-bottom: 112px; background-clip: content-box; }

@media (max-width: 1120px) {
    #osada-bottomzone { right: 40px; grid-template-columns: minmax(360px, 1fr) 120px minmax(300px, .8fr); }
    /* The 420px floor is for reclaiming space on a wide zone; here there is none to reclaim, and
       holding it would push the forecast and enemy card off their own tracks. */
    #unit-info { min-width: 0; }
    .osada-service-record__summary { grid-template-columns: repeat(2, minmax(0,1fr)); }
}

            """.trimIndent()
        document.head?.appendChild(style)
    }
}
