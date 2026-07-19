package org.osada.ui

import org.osada.model.GameUnit

// Equipment-window/dossier/ui-tooltip forwarders for [UIBuilder], split out to keep its
// function count in bounds.

// --- Equipment window (EquipmentWindowBuilder) ---
fun UIBuilder.setDefaultUserSelections() = EquipmentWindowBuilder.setDefaultUserSelections()

fun UIBuilder.buildEquipmentWindow() = EquipmentWindowBuilder.buildEquipmentWindow()

fun UIBuilder.buildEquipmentSortOptions() = EquipmentWindowBuilder.buildEquipmentSortOptions()

fun UIBuilder.showEquipmentCosts(
    prestige: Int,
    buy: Int,
    upgrade: Int,
    sell: Int,
) = EquipmentWindowBuilder.showEquipmentCosts(prestige, buy, upgrade, sell)

fun UIBuilder.showAttackInfo(
    attacker: GameUnit,
    defender: GameUnit,
) = EquipmentWindowBuilder.showAttackInfo(attacker, defender)

// --- Dossier / campaign end (DossierBuilder) ---
fun UIBuilder.simulateDossier(): dynamic = DossierBuilder.simulateDossier()

fun UIBuilder.showCampaignEnd(
    outcome: String,
    text: String,
    callback: (() -> Unit)?,
): Boolean = DossierBuilder.showCampaignEnd(outcome, text, callback)

fun UIBuilder.showDossier(
    docked: Boolean,
    callback: (() -> Unit)? = null,
): Boolean = DossierBuilder.showDossier(docked, callback)

fun UIBuilder.closeDossier() = DossierBuilder.closeDossier()

// --- UI-anchored tooltips (TooltipBuilder) ---
fun UIBuilder.uiToolTip(
    text: String,
    x: Int,
    y: Int,
    right: Boolean,
) = TooltipBuilder.uiToolTip(text, x, y, right)

fun UIBuilder.uiToolTipAtElement(
    element: dynamic,
    text: String,
    right: Boolean,
) = TooltipBuilder.uiToolTipAtElement(element, text, right)
