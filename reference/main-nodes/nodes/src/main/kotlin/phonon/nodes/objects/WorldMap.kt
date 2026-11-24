/**
 * WorldMap Renderer
 *
 * Manager to render territories from the world into
 * strings to display in chat or other objects.
 *
 * Renders world chunk coords into ascii characters.
 */

package phonon.nodes.objects

import org.bukkit.ChatColor
import phonon.nodes.Nodes
import phonon.nodes.warzone.WarzoneManager
import phonon.nodes.warzone.WarzoneTownRegistry

// minimap display primitive glyphs MUST BE SAME ASCII SIZE
private val SOLID = "\u2588" // full solid block
private val SHADE0 = "\u2592" // medium shade
private val SHADE1 = "\u2593" // dark shade
private val SHADE2 = "\u2588" // full solid block
private val HOME = SHADE2 // home territory
private val CORE = "\u256B" // core chunk H

// Use glyphs that render reliably in the default Minecraft font (sidebar + chat).
// Some box-drawing variants show up as missing-glyph "tofu" blocks on certain clients, and mixing
// different glyphs can create a "patchy" captured look.
// Captured chunk marker. Use the same "full block" glyph as normal territory shading so captured
// regions don't visually "shrink" on some fonts/resource packs.
private val CONQUERED0 = "\u255E"
private val CONQUERED1 = "\u255F"
private val ATTACK_FLAG = "\u2691" // attack flag symbol
private val PLAYER_TOKEN = "\u253C" // player token
private val PLAYER_IN_OCCUPIED_TOKEN = "\u256C" // player token in occupied chunk

// Keep tokens a fixed length (one color code + one glyph) so scoreboard minimap lines don't exceed
// client wrapping limits. Avoid extra formatting codes like BOLD here.
private val PORT_TOKEN = "\u2693" // port anchor symbol

// minimap display tokens
private val EMPTY = "${ChatColor.BLACK}$SHADE0"
private val PLAYER_IN_EMPTY = "${ChatColor.WHITE}$PLAYER_TOKEN"

// territory cores
private val CORE_UNCLAIMED = "${ChatColor.GRAY}$CORE"
private val CORE_TOWN = "${ChatColor.GREEN}$CORE"
private val CORE_NEUTRAL = "${ChatColor.YELLOW}$CORE"
private val CORE_ENEMY = "${ChatColor.RED}$CORE"
private val CORE_ALLY = "${ChatColor.AQUA}$CORE"
private val CORE_NATION = "${ChatColor.DARK_GREEN}$CORE"
private val CORE_WARZONE_HOME = "${ChatColor.WHITE}$CORE" // high-contrast marker for warzone home territories
private val CORE_CAPTURED = "${ChatColor.GREEN}$CORE" // captured by your town
private val CORE_OCCUPIED = "${ChatColor.RED}$CORE" // occupied by another town
private val CORE_OCCUPIED_TOWN = "${ChatColor.GREEN}$CORE"
private val CORE_OCCUPIED_NEUTRAL = "${ChatColor.YELLOW}$CORE"
private val CORE_OCCUPIED_ALLY = "${ChatColor.AQUA}$CORE"
private val CORE_OCCUPIED_ENEMY = "${ChatColor.RED}$CORE"
private val CORE_OCCUPIED_NATION = "${ChatColor.DARK_GREEN}$CORE"

// home territory
private val HOME_TOWN = "${ChatColor.GREEN}$HOME" // captured by your town
private val HOME_NEUTRAL = "${ChatColor.YELLOW}$HOME" // captured by neutral town
private val HOME_ALLY = "${ChatColor.AQUA}$HOME" // captured by ally town
private val HOME_ENEMY = "${ChatColor.RED}$HOME" // captured by enemy
private val HOME_NATION = "${ChatColor.DARK_GREEN}$HOME" // town in your nation

// occupied chunk
private val OCCUPIED_TOWN = "${ChatColor.GREEN}$CONQUERED0" // captured by your town
private val OCCUPIED_NEUTRAL = "${ChatColor.YELLOW}$CONQUERED0" // captured by neutral town
private val OCCUPIED_ALLY = "${ChatColor.AQUA}$CONQUERED0" // captured by ally town
private val OCCUPIED_ENEMY = "${ChatColor.RED}$CONQUERED0" // captured by enemy
private val OCCUPIED_NATION = "${ChatColor.DARK_GREEN}$CONQUERED0" // captured by town in your nation

// territory colors: maps territory color id -> color
// territories have color assigned to them during
// map generation from nodes editor

// unclaimed territory colors
private val COLOR_UNCLAIMED: Array<String> = arrayOf(
    "${ChatColor.GRAY}$SHADE0",
    "${ChatColor.GRAY}$SHADE1",
    "${ChatColor.DARK_GRAY}$SHADE0",
    "${ChatColor.DARK_GRAY}$SHADE1",
    "${ChatColor.GRAY}$SHADE2",
    "${ChatColor.DARK_GRAY}$SHADE2",
)

// player town chunk
private val COLOR_TOWN: Array<String> = arrayOf(
    "${ChatColor.GREEN}$SHADE0",
    "${ChatColor.GREEN}$SHADE1",
    "${ChatColor.DARK_GREEN}$SHADE0",
    "${ChatColor.DARK_GREEN}$SHADE1",
    "${ChatColor.GREEN}$SHADE2",
    "${ChatColor.DARK_GREEN}$SHADE2",
)

// neutral town chunk
private val COLOR_NEUTRAL: Array<String> = arrayOf(
    "${ChatColor.YELLOW}$SHADE0",
    "${ChatColor.YELLOW}$SHADE1",
    "${ChatColor.GOLD}$SHADE0",
    "${ChatColor.GOLD}$SHADE1",
    "${ChatColor.GOLD}$SHADE2",
    "${ChatColor.GOLD}$SHADE2",
)

// ally town chunk
private val COLOR_ALLY: Array<String> = arrayOf(
    "${ChatColor.AQUA}$SHADE0",
    "${ChatColor.AQUA}$SHADE1",
    "${ChatColor.DARK_AQUA}$SHADE0",
    "${ChatColor.DARK_AQUA}$SHADE1",
    "${ChatColor.AQUA}$SHADE2",
    "${ChatColor.DARK_AQUA}$SHADE2",
)

// town in your nation chunk
private val COLOR_NATION: Array<String> = arrayOf(
    "${ChatColor.DARK_GREEN}$SHADE0",
    "${ChatColor.DARK_GREEN}$SHADE1",
    "${ChatColor.DARK_GREEN}$SHADE0",
    "${ChatColor.DARK_GREEN}$SHADE1",
    "${ChatColor.DARK_GREEN}$SHADE2",
    "${ChatColor.DARK_GREEN}$SHADE2",
)

// enemy town chunk
private val COLOR_ENEMY: Array<String> = arrayOf(
    "${ChatColor.RED}$SHADE0",
    "${ChatColor.RED}$SHADE1",
    "${ChatColor.DARK_RED}$SHADE0",
    "${ChatColor.DARK_RED}$SHADE1",
    "${ChatColor.DARK_RED}$SHADE2",
    "${ChatColor.DARK_RED}$SHADE2",
)

// town occupied territory
private val COLOR_OCCUPIED_TOWN: Array<String> = arrayOf(
    "${ChatColor.GREEN}$CONQUERED0",
    "${ChatColor.GREEN}$CONQUERED1",
    "${ChatColor.DARK_GREEN}$CONQUERED0",
    "${ChatColor.DARK_GREEN}$CONQUERED1",
    "${ChatColor.GREEN}$CONQUERED1",
    "${ChatColor.DARK_GREEN}$CONQUERED1",
)

// territory occupied by neutral
private val COLOR_OCCUPIED_NEUTRAL: Array<String> = arrayOf(
    "${ChatColor.YELLOW}$CONQUERED0",
    "${ChatColor.YELLOW}$CONQUERED1",
    "${ChatColor.GOLD}$CONQUERED0",
    "${ChatColor.GOLD}$CONQUERED1",
    "${ChatColor.GOLD}$CONQUERED1",
    "${ChatColor.GOLD}$CONQUERED1",
)

// territory occupied by ally
private val COLOR_OCCUPIED_ALLY: Array<String> = arrayOf(
    "${ChatColor.AQUA}$CONQUERED0",
    "${ChatColor.AQUA}$CONQUERED1",
    "${ChatColor.DARK_AQUA}$CONQUERED0",
    "${ChatColor.DARK_AQUA}$CONQUERED1",
    "${ChatColor.AQUA}$CONQUERED1",
    "${ChatColor.DARK_AQUA}$CONQUERED1",
)

// territory occupied by nation (same nation as resident)
private val COLOR_OCCUPIED_NATION: Array<String> = arrayOf(
    "${ChatColor.DARK_GREEN}$CONQUERED0",
    "${ChatColor.DARK_GREEN}$CONQUERED1",
    "${ChatColor.DARK_GREEN}$CONQUERED0",
    "${ChatColor.DARK_GREEN}$CONQUERED1",
    "${ChatColor.DARK_GREEN}$CONQUERED1",
    "${ChatColor.DARK_GREEN}$CONQUERED1",
)

// territory occupied by enemy
private val COLOR_OCCUPIED_ENEMY: Array<String> = arrayOf(
    "${ChatColor.RED}$CONQUERED0",
    "${ChatColor.RED}$CONQUERED1",
    "${ChatColor.DARK_RED}$CONQUERED0",
    "${ChatColor.DARK_RED}$CONQUERED1",
    "${ChatColor.DARK_RED}$CONQUERED1",
    "${ChatColor.DARK_RED}$CONQUERED1",
)

/**
 * Map from a color token to alternate color token.
 */
private fun getAlternativeColor(c: String): String {
    when (c) {
        "${ChatColor.GRAY}" -> return "${ChatColor.WHITE}"
        "${ChatColor.DARK_GRAY}" -> return "${ChatColor.GRAY}"

        "${ChatColor.GREEN}" -> return "${ChatColor.DARK_GREEN}"
        "${ChatColor.DARK_GREEN}" -> return "${ChatColor.GREEN}"

        "${ChatColor.YELLOW}" -> return "${ChatColor.GOLD}"
        "${ChatColor.GOLD}" -> return "${ChatColor.YELLOW}"

        "${ChatColor.AQUA}" -> return "${ChatColor.DARK_AQUA}"
        "${ChatColor.DARK_AQUA}" -> return "${ChatColor.AQUA}"
        "${ChatColor.BLUE}" -> return "${ChatColor.DARK_BLUE}"
        "${ChatColor.DARK_BLUE}" -> return "${ChatColor.BLUE}"

        "${ChatColor.RED}" -> return "${ChatColor.DARK_RED}"
        "${ChatColor.DARK_RED}" -> return "${ChatColor.RED}"

        else -> return "${ChatColor.WHITE}"
    }
}

private val WARZONE_COLOR = "${ChatColor.DARK_BLUE}"

private fun recolorToken(token: String, color: String): String {
    if (token.isEmpty()) return token
    val glyph = token.last()
    return "$color$glyph"
}

private fun withAttackFlag(token: String): String {
    if (token.isEmpty()) return token
    val color = token.substring(0 until (token.length - 1))
    return "${color}$ATTACK_FLAG"
}

private fun withColoredAttackFlag(color: ChatColor): String = "${color}$ATTACK_FLAG"

public object WorldMap {

    public fun renderToken(resident: Resident, playerCoord: Coord, coord: Coord): String {
        val residentTown = resident.town
        val residentNation = residentTown?.nation
        val warEnabled = Nodes.war.enabled

        val territoryChunk = Nodes.getTerritoryChunkFromCoord(coord)
        if (territoryChunk == null) {
            return if (coord == playerCoord) {
                PLAYER_IN_EMPTY
            } else {
                EMPTY
            }
        }

        var playerToken = PLAYER_TOKEN
        val territory = territoryChunk.territory
        val color = territory.color
        val town = territory.town
        val nation = town?.nation
        // Prefer per-chunk occupier for partial captures, but fall back to territory occupier
        // since some older code paths set the occupier on Territory only.
        val chunkOccupier = territoryChunk.occupier ?: territory.occupier
        // Never show "home" markers for internal warzone towns; it creates noisy/incorrect minimap output.
        val isHomeTerritoryChunk = town != null &&
            !WarzoneTownRegistry.isWarzoneTown(town) &&
            territory.id == town.home &&
            coord != territory.core

        val nowMs = System.currentTimeMillis()
        val warzone = WarzoneManager.findWarzoneByTerritoryId(territory.id)
        val isWarzoneTerritory = warzone != null

        val coordToken = if (chunkOccupier !== null) {
            playerToken = PLAYER_IN_OCCUPIED_TOKEN

            // Core marker should remain visible even if the chunk is occupied/captured.
            // Otherwise the core icon disappears in warzones where occupier is frequently set.
            if (coord == territory.core) {
                val chunkOccupierNation = chunkOccupier.nation
                when {
                    residentTown !== null && chunkOccupier === residentTown -> CORE_OCCUPIED_TOWN
                    residentNation !== null && residentNation === chunkOccupierNation -> CORE_OCCUPIED_NATION
                    residentTown !== null && residentTown.allies.contains(chunkOccupier) -> CORE_OCCUPIED_ALLY
                    residentTown !== null && residentTown.enemies.contains(chunkOccupier) -> CORE_OCCUPIED_ENEMY
                    else -> CORE_OCCUPIED_NEUTRAL
                }
                // (no fallthrough)
            } else if (isHomeTerritoryChunk && !isWarzoneTerritory) {
                // Keep the home marker glyph but colour it based on the OCCUPIER's relation,
                // not the territory owner. Previously this used town/nation which is always the
                // defending town, so from the defender's view the chunk always showed HOME_TOWN
                // (green) even when an enemy was occupying it — making captured home chunks
                // invisible on the minimap.
                val chunkOccupierNation = chunkOccupier.nation
                when {
                    chunkOccupier === residentTown -> HOME_TOWN
                    residentNation !== null && residentNation === chunkOccupierNation -> HOME_NATION
                    residentTown?.enemies?.contains(chunkOccupier) == true -> HOME_ENEMY
                    residentTown?.allies?.contains(chunkOccupier) == true -> HOME_ALLY
                    else -> HOME_NEUTRAL
                }
            } else {
                val chunkOccupierNation = chunkOccupier.nation
                when {
                    residentTown !== null && chunkOccupier === residentTown -> OCCUPIED_TOWN
                    residentNation !== null && residentNation === chunkOccupierNation -> OCCUPIED_NATION
                    residentTown !== null && residentTown.allies.contains(chunkOccupier) -> OCCUPIED_ALLY
                    residentTown !== null && residentTown.enemies.contains(chunkOccupier) -> OCCUPIED_ENEMY
                    else -> OCCUPIED_NEUTRAL
                }
            }
        } else {
            if (town != null) {
                if (coord == territory.core) {
                    when {
                        town === residentTown -> CORE_TOWN
                        residentNation !== null && residentNation === nation -> CORE_NATION
                        residentTown?.enemies?.contains(town) == true -> CORE_ENEMY
                        residentTown?.allies?.contains(town) == true -> CORE_ALLY
                        else -> CORE_NEUTRAL
                    }
                } else {
                    // Use per-chunk occupier so partial territory captures show up on /t map and minimap.
                    // Same fallback logic as above: chunk occupier first, then territory.
                    val occupier = territoryChunk.occupier ?: territory.occupier
                    if (occupier != null) {
                        val occupierNation = occupier.nation
                        when {
                            occupier === residentTown -> COLOR_OCCUPIED_TOWN[color]
                            residentTown?.enemies?.contains(occupier) == true -> COLOR_OCCUPIED_ENEMY[color]
                            residentNation !== null && residentNation === occupierNation -> COLOR_OCCUPIED_NATION[color]
                            residentTown?.allies?.contains(occupier) == true -> COLOR_OCCUPIED_ALLY[color]
                            else -> COLOR_OCCUPIED_NEUTRAL[color]
                        }
                    } else if (!WarzoneTownRegistry.isWarzoneTown(town) && territory.id == town.home) {
                        if (isWarzoneTerritory) {
                            // In warzones, always use a high-contrast marker for home territory. Using relation
                            // colors here can be too dark against the warzone overlay, making the glyph seem "missing".
                            CORE_WARZONE_HOME
                        } else {
                            when {
                                town === residentTown -> HOME_TOWN
                                residentNation !== null && residentNation === nation -> HOME_NATION
                                residentTown?.enemies?.contains(town) == true -> HOME_ENEMY
                                residentTown?.allies?.contains(town) == true -> HOME_ALLY
                                else -> HOME_NEUTRAL
                            }
                        }
                    } else {
                        when {
                            town === residentTown -> COLOR_TOWN[color]
                            residentNation !== null && residentNation === nation -> COLOR_NATION[color]
                            residentTown?.enemies?.contains(town) == true -> COLOR_ENEMY[color]
                            residentTown?.allies?.contains(town) == true -> COLOR_ALLY[color]
                            else -> COLOR_NEUTRAL[color]
                        }
                    }
                }
            } else {
                if (coord == territory.core) {
                    if (residentTown !== null && (territoryChunk.occupier ?: territory.occupier) === residentTown) {
                        CORE_CAPTURED
                    } else {
                        CORE_UNCLAIMED
                    }
                } else {
                    COLOR_UNCLAIMED[color]
                }
            }
        }

        val attackerTown = territoryChunk.attacker
        val isUnderAttack = attackerTown !== null
        val targetTown = territory.town

        val attackFlagToken = if (isUnderAttack) {
            when {
                attackerTown !== null && attackerTown === residentTown ->
                    withColoredAttackFlag(ChatColor.GREEN)
                attackerTown !== null && residentNation !== null && attackerTown.nation === residentNation ->
                    withColoredAttackFlag(ChatColor.DARK_GREEN)
                attackerTown !== null && residentTown?.allies?.contains(attackerTown) == true ->
                    withColoredAttackFlag(ChatColor.AQUA)
                attackerTown !== null && residentTown?.enemies?.contains(attackerTown) == true ->
                    withColoredAttackFlag(ChatColor.RED)
                targetTown !== null && (targetTown === residentTown || (residentNation !== null && targetTown.nation === residentNation)) ->
                    withColoredAttackFlag(ChatColor.RED)
                else ->
                    withColoredAttackFlag(ChatColor.YELLOW)
            }
        } else {
            null
        }

        // Warzone overlay color should not hide markers (core/home) or war UI (occupied/attack flags).
        // Only recolor the "base terrain" shade glyphs.
        val glyph = coordToken.last()
        val isBaseShadeGlyph = glyph == SHADE0[0] || glyph == SHADE1[0] || glyph == SHADE2[0]
        val shouldRecolorWarzone = isWarzoneTerritory && isBaseShadeGlyph
        val finalCoordToken = if (shouldRecolorWarzone) recolorToken(coordToken, WARZONE_COLOR) else coordToken

        if (coord == playerCoord) {
            val color = coordToken.substring(0 until (coordToken.length - 1))
            return "${getAlternativeColor(color)}$playerToken"
        }

        if (attackFlagToken != null) {
            return attackFlagToken
        }

        if (Nodes.chunkToPort[listOf(coord.x, coord.z)] != null) {
            val color = finalCoordToken.substring(0 until (finalCoordToken.length - 1))
            return "${getAlternativeColor(color)}$PORT_TOKEN"
        }

        return finalCoordToken
    }

    public fun renderLine(resident: Resident, playerCoord: Coord, z: Int, xMin: Int, xMax: Int): String {
        val renderedString = StringBuilder((xMax - xMin + 1) * 3)
        for (x in xMin..xMax) {
            val token = renderToken(resident, playerCoord, Coord(x, z))
            renderedString.append(token)
        }

        return renderedString.toString()
    }
}
