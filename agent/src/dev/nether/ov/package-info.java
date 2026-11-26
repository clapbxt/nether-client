/**
 * The in-game client (overlay). Every class under this package is defined inside the game's LWJGL class loader by
 * {@code dev.nether.agent.Agent} (the build lists them in ov.list), then {@link dev.nether.ov.Overlay#install} starts it.
 * <p>Layers, lowest first; a package should only use the packages listed before it:
 * <ul>
 * <li>{@code platform}: windowing, input and GL back ends per game generation (GLFW, LWJGL 2, SDL3)</li>
 * <li>{@code game}: reading the game through reflection, name tables, assets</li>
 * <li>{@code framework}: modules, settings, config, input routing, HTTP</li>
 * <li>{@code ui}: the 2D renderer, theme, icons and shared widgets</li>
 * <li>{@code menu}: the in-game menu, settings pages, HUD editor; {@code menu.title} the title screen</li>
 * <li>{@code features.*}: one package per client feature (HUD, lyrics, cosmetics, social...)</li>
 * </ul>
 * Today some lower layers still call features directly (the module registry, the menu); new code should not add more.
 */
package dev.nether.ov;
