package org.osada.ui

// Screen-builder forwarders for [UIBuilder], split out to keep its function count in bounds.

// --- Start menu (StartMenuBuilder) ---
fun UIBuilder.resetStartMenuBuilt() = StartMenuBuilder.resetStartMenuBuilt()

fun UIBuilder.hideStartMenu() = StartMenuBuilder.hideStartMenu()

fun UIBuilder.buildStartMenu() = StartMenuBuilder.buildStartMenu()

// --- Save/Load sub-screen (GameStateMenuBuilder) ---
fun UIBuilder.buildGameStateMenu() = GameStateMenuBuilder.buildGameStateMenu()

fun UIBuilder.gameStateButton(id: String) = GameStateMenuBuilder.gameStateButton(id)

// --- Main menu (MainMenuBuilder) ---
fun UIBuilder.buildMainMenu() = MainMenuBuilder.buildMainMenu()

// --- Unit info panel (UnitInfoBuilder) ---
fun UIBuilder.buildUnitInfoWindow() = UnitInfoBuilder.buildUnitInfoWindow()

// --- Layout (UILayout) ---
fun UIBuilder.scaleUI(scale: Double) = UILayout.scaleUI(scale)

fun UIBuilder.createSlider(
    container: dynamic,
    id: String,
    value: Double,
    step: Double,
    min: Double,
    max: Double,
    callback: (() -> Unit)?,
) = UILayout.createSlider(container, id, value, step, min, max, callback)

fun UIBuilder.resizeUI(size: Int) = UILayout.resizeUI(size)

fun UIBuilder.setLayoutConstrains(small: Boolean) = UILayout.setLayoutConstrains(small)
