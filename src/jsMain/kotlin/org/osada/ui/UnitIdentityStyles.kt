package org.osada.ui

import kotlinx.browser.document

/**
 * Installs the identity/transparency styles without modifying the shared theme file.
 * Keeping this feature stylesheet isolated makes the patch tolerant of local theme customisation.
 */
internal object UnitIdentityStyles {
    private const val STYLE_ID = "osada-unit-identity-styles"

    fun ensureInstalled() {
        if (document.getElementById(STYLE_ID) != null) return
        val style = document.createElement("style")
        style.id = STYLE_ID
        style.textContent = """
/* ============================================================================
 * UNIT IDENTITY / COMBAT TRANSPARENCY / SERVICE RECORD (2026-07-23)
 * ============================================================================ */
#osada-bottomzone { height: 112px; grid-template-columns: minmax(420px, 600px) 150px minmax(340px, 460px); }
#unit-info { padding: 7px 10px; }
#uc-inner { gap: 9px; }
#uc-main { justify-content: flex-start; gap: 3px; }
#uName {
    white-space: normal; overflow: hidden; text-overflow: clip; line-height: 1.15;
    display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
    text-transform: none; font-size: 14px;
}

/* OSADA compact unit name / rename alignment */
#uc-nameline {
    display: flex; align-items: flex-start; justify-content: flex-start;
    gap: 4px; min-width: 0;
}
#uName {
    flex: 0 1 auto; width: auto; max-width: calc(100% - 26px); min-width: 0;
}
#ucRename {
    position: static !important; inset: auto !important; flex: 0 0 auto;
    width: 20px; min-width: 20px; margin: 0; align-self: flex-start;
}
#uc-commandline { min-width: 0; display: flex; align-items: center; }
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
#uc-statusline { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; min-height: 18px; }
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
#ecMain { gap: 2px; }
.osada-ec-name { white-space: normal; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.osada-ec-stat { cursor: help; }

/* Reserve enough scrollable room below the map for the fixed identity card. The spacer is outside
 * the terrain/canvas content, so the final hex row can move above the HUD instead of being veiled. */
#mainbody:not(.osada-strategic) #game { padding-bottom: 112px; background-clip: content-box; }

@media (max-width: 1120px) {
    #osada-bottomzone { right: 40px; grid-template-columns: minmax(360px, 1fr) 120px minmax(300px, .8fr); }
    .osada-service-record__summary { grid-template-columns: repeat(2, minmax(0,1fr)); }
}

        """.trimIndent()
        document.head?.appendChild(style)
    }
}
