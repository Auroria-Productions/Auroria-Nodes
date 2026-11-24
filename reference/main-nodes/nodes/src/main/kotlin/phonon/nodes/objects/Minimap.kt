/**
 * Minimap object for player
 *
 * Displays a square chunk area around player
 * [-x, x] chunks in each direction, x is configurable
 *
 * Internally uses the Bukkit scoreboard api for rendering.
 * Limitations of API:
 * Team names: max 16 chars
 * Score names: max 40 chars
 *
 * Each glyph in minimap is 3 chars (color code "&x" = 2 chars)
 * For [-5, 5] extent -> 33 chars
 * For [-6, 6] extent -> 39 chars
 * Initial line symbol required to distinguish lines,
 * so max size depends on scoreboard entry length
 *
 * Performance notes (vs original):
 * - render() now uses incremental updates when the player moves exactly one
 *   chunk in a cardinal or diagonal direction.  Only the new edge column/row
 *   of (2*size+1) cells is re-rendered; the rest of the grid is shifted in
 *   memory without touching WorldMap.  This reduces renderToken() calls from
 *   O((2n+1)²) to O(2n+1) per chunk step — ~11× fewer calls at size=5.
 * - A full rebuild is done on first render, after a teleport (large jump),
 *   when size changes, OR when the player hasn't moved (dx==0, dz==0).
 *   The stationary-player case requires a full rebuild because attack/occupation
 *   state on any visible chunk may have changed since the last render tick —
 *   repainting only the center cell left surrounding chunks stale, causing the
 *   "flag icon stuck on home node" glitch where players couldn't see a cap in
 *   progress on neighbouring chunks until they physically moved.
 * - The raw (un-oriented) token grid is cached between renders.  The player
 *   token cell is the only cell that changes position on every move and is
 *   always regenerated correctly because the whole edge (which includes the
 *   player's new position) is re-rendered, and the old player position falls
 *   inside the shifted region and is re-rendered as a normal terrain cell.
 * - scoreboard packets are sent for ALL rows every render call (unchanged),
 *   because the scoreboard entry strings change whenever the player position
 *   shifts (LINE_ID prefix + content).  Diffing individual rows to avoid
 *   redundant packets is a possible future optimisation.
 */

package phonon.nodes.objects

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import phonon.nodes.Config
import phonon.nodes.nms.sendObjectiveCreate
import phonon.nodes.nms.sendObjectiveDisplay
import phonon.nodes.nms.sendObjectiveRemove
import phonon.nodes.nms.sendScore
import phonon.nodes.objects.MinimapOrientation

// max allowed minimap size
private const val MAX_MINIMAP_SIZE = 6

// used to start each line in scoreboard
// ensures each line name is unique
private val LINE_ID = arrayOf(
    "${ChatColor.AQUA}${ChatColor.RESET}",
    "${ChatColor.BLACK}${ChatColor.RESET}",
    "${ChatColor.BLUE}${ChatColor.RESET}",
    "${ChatColor.DARK_AQUA}${ChatColor.RESET}",
    "${ChatColor.DARK_BLUE}${ChatColor.RESET}",
    "${ChatColor.DARK_GRAY}${ChatColor.RESET}",
    "${ChatColor.DARK_GREEN}${ChatColor.RESET}",
    "${ChatColor.DARK_PURPLE}${ChatColor.RESET}",
    "${ChatColor.DARK_RED}${ChatColor.RESET}",
    "${ChatColor.GOLD}${ChatColor.RESET}",
    "${ChatColor.GRAY}${ChatColor.RESET}",
    "${ChatColor.GREEN}${ChatColor.RESET}",
    "${ChatColor.LIGHT_PURPLE}${ChatColor.RESET}",
    "${ChatColor.RED}${ChatColor.RESET}",
    "${ChatColor.WHITE}${ChatColor.RESET}",
    "${ChatColor.YELLOW}${ChatColor.RESET}",
)

private fun buildHeader(size: Int): String {
    val totalWidth = (2 * size + 1) * 2
    val left = "-$size"
    val right = "$size"
    val centerPos = totalWidth / 2

    val sb = StringBuilder()
    sb.append(left)

    val padToCenter = (centerPos - sb.length).coerceAtLeast(1)
    sb.append(" ".repeat(padToCenter))
    sb.append("0")

    val padToRight = (totalWidth - right.length - sb.length).coerceAtLeast(1)
    sb.append(" ".repeat(padToRight))
    sb.append(right)

    return "${ChatColor.RED}$sb"
}

private fun effectiveSizeLimits(onlinePlayers: Int): Pair<Int, Int> {
    val (minSizeRaw, maxSizeRaw) = Config.getMinimapSizeLimits(onlinePlayers)
    val minSize = minSizeRaw.coerceAtLeast(1).coerceAtMost(MAX_MINIMAP_SIZE)
    val maxSize = maxSizeRaw.coerceAtLeast(minSize).coerceAtMost(MAX_MINIMAP_SIZE)
    return Pair(minSize, maxSize)
}

private fun clampMinimapSize(requestedSize: Int, onlinePlayers: Int): Int {
    val (minSize, maxSize) = effectiveSizeLimits(onlinePlayers)
    return requestedSize.coerceIn(minSize, maxSize)
}

public class Minimap(
    val resident: Resident,
    val player: Player,
    var size: Int, // display square half-extent, renders [-size, size]
) {
    companion object {
        public fun defaultSize(onlinePlayers: Int): Int = clampMinimapSize(Config.minimapSizeDefault, onlinePlayers)
        public fun clampSize(requestedSize: Int, onlinePlayers: Int): Int = clampMinimapSize(requestedSize, onlinePlayers)
        public fun effectiveLimits(onlinePlayers: Int): Pair<Int, Int> = effectiveSizeLimits(onlinePlayers)
    }

    private val objectiveName = "nodes_minimap"

    // Cached raw token grid (north-up, before orientation transform).
    // grid[row][col] where row 0 = lowest z (south-most) visually rendered at
    // the top of the sidebar — same layout as buildTokenGrid original.
    // Null until the first render.
    private var cachedGrid: Array<Array<String>>? = null

    // The center coord that cachedGrid was built for.
    private var cachedCenter: Coord? = null

    init {
        this.size = clampMinimapSize(this.size, Bukkit.getOnlinePlayers().size)

        player.sendObjectiveCreate(objectiveName, "Minimap")
        player.sendObjectiveDisplay(objectiveName, DisplaySlot.SIDEBAR)

        val loc = this.player.location
        val coord = Coord.fromBlockCoords(
            kotlin.math.floor(loc.x).toInt(),
            kotlin.math.floor(loc.z).toInt(),
        )
        this.render(coord)
    }

    /**
     * Render the minimap centered at [coord].
     *
     * Uses an incremental update when the player moved exactly one chunk
     * in any of the 8 directions (cardinal + diagonal).  Falls back to a
     * full rebuild for teleports, first render, or size changes.
     */
    public fun render(coord: Coord) {
        // Additional sidebar lines are applied after native grid rendering; see packaging hook manifest.

        val grid = resolveGrid(coord)

        // Write packets.
        player.sendObjectiveRemove(objectiveName)
        player.sendObjectiveCreate(objectiveName, "Minimap")
        player.sendObjectiveDisplay(objectiveName, DisplaySlot.SIDEBAR)

        player.sendScore(objectiveName, buildHeader(size), size + 1)

        val finalGrid = orientGrid(grid, resident, player)
        val verticalScores = (size downTo -size)
        for ((i, score) in verticalScores.withIndex()) {
            val renderedLine = finalGrid[i].joinToString("")
            player.sendScore(objectiveName, "${LINE_ID[i]}$renderedLine", score)
        }
    }

    public fun destroy() {
        player.sendObjectiveDisplay("", DisplaySlot.SIDEBAR)
        player.sendObjectiveRemove(objectiveName)
        cachedGrid = null
        cachedCenter = null
    }

    // ── Grid resolution ───────────────────────────────────────────────────────

    /**
     * Returns a fully up-to-date token grid for [coord], updating [cachedGrid]
     * in place when possible (incremental shift), or rebuilding it fully.
     *
     * After this call [cachedCenter] == [coord] and [cachedGrid] is valid.
     */
    private fun resolveGrid(coord: Coord): Array<Array<String>> {
        val prev = cachedCenter
        val grid = cachedGrid

        if (prev == null || grid == null) {
            // First render — full build.
            return buildFullGrid(coord)
        }

        val dx = coord.x - prev.x
        val dz = coord.z - prev.z

        if (dx == 0 && dz == 0) {
            // Same chunk — player hasn't moved, but attack/occupation/flag state
            // on ANY visible chunk may have changed (e.g. a neighbouring chunk is
            // being captured and its flag token needs to appear).  Repainting only
            // the center cell left surrounding chunks stale, causing the classic
            // "flag icon stuck in the middle of the home node" glitch where players
            // couldn't see a cap in progress on their home territory until they moved.
            // Full rebuild is the only safe option here.
            return buildFullGrid(coord)
        }

        if (kotlin.math.abs(dx) <= 1 && kotlin.math.abs(dz) <= 1) {
            // Moved at most one chunk in each axis — do an incremental shift.
            return shiftGrid(grid, coord, dx, dz)
        }

        // Large jump (teleport etc.) — full rebuild.
        return buildFullGrid(coord)
    }

    /**
     * Build a complete [(2*size+1) × (2*size+1)] token grid from scratch.
     * Cost: (2*size+1)² renderToken calls.
     */
    private fun buildFullGrid(center: Coord): Array<Array<String>> {
        val dimension = size * 2 + 1
        val grid = Array(dimension) { Array(dimension) { "" } }
        val xStart = center.x - size
        val zStart = center.z - size

        for (row in 0 until dimension) {
            val z = zStart + row
            for (col in 0 until dimension) {
                val x = xStart + col
                grid[row][col] = WorldMap.renderToken(resident, center, Coord(x, z))
            }
        }

        cachedGrid = grid
        cachedCenter = center
        return grid
    }

    /**
     * Shift the existing cached grid by (dx, dz) chunks and render only the
     * newly exposed edge(s).
     *
     * For a cardinal move this costs (2*size+1) renderToken calls.
     * For a diagonal move this costs (2*size+1) + (2*size+1) - 1 calls
     * (one full edge plus the corner of the perpendicular edge).
     *
     * The shift is done in-place by rotating rows/columns of the existing
     * array to avoid allocating a new grid.
     *
     * Convention: grid row 0 corresponds to z = center.z - size (north/top of
     * display), row increases southward.  grid col 0 = x = center.x - size.
     *
     * A move of dz = +1 (player moved one chunk south) means the grid scrolls
     * up: old row 0 is discarded, all rows shift up by one, a new row is
     * painted at the bottom.
     */
    private fun shiftGrid(
        grid: Array<Array<String>>,
        newCenter: Coord,
        dx: Int,
        dz: Int,
    ): Array<Array<String>> {
        val dimension = size * 2 + 1
        val newXStart = newCenter.x - size
        val newZStart = newCenter.z - size

        // ── Z axis shift (rows) ───────────────────────────────────────────
        // dz > 0: player moved south → grid scrolls up (row 0 expelled, new row at bottom)
        // dz < 0: player moved north → grid scrolls down (last row expelled, new row at top)
        if (dz != 0) {
            if (dz > 0) {
                // Rotate rows up: save row 0, shift rows 1..n-1 down by one index, paint new bottom row.
                val recycled = grid[0]
                for (row in 0 until dimension - 1) grid[row] = grid[row + 1]
                grid[dimension - 1] = recycled
                // Paint new bottom row (z = newZStart + dimension - 1).
                val newZ = newZStart + dimension - 1
                for (col in 0 until dimension) {
                    val x = newXStart + col
                    grid[dimension - 1][col] = WorldMap.renderToken(resident, newCenter, Coord(x, newZ))
                }
            } else {
                // Rotate rows down: save last row, shift rows 0..n-2 up by one index, paint new top row.
                val recycled = grid[dimension - 1]
                for (row in dimension - 1 downTo 1) grid[row] = grid[row - 1]
                grid[0] = recycled
                // Paint new top row (z = newZStart).
                val newZ = newZStart
                for (col in 0 until dimension) {
                    val x = newXStart + col
                    grid[0][col] = WorldMap.renderToken(resident, newCenter, Coord(x, newZ))
                }
            }
        }

        // ── X axis shift (columns) ────────────────────────────────────────
        // dx > 0: player moved east → grid scrolls left (col 0 expelled, new col at right)
        // dx < 0: player moved west → grid scrolls right (last col expelled, new col at left)
        if (dx != 0) {
            if (dx > 0) {
                // Shift each row's columns left by one, paint new rightmost column.
                val newX = newXStart + dimension - 1
                for (row in 0 until dimension) {
                    val rowArr = grid[row]
                    val recycled = rowArr[0]
                    for (col in 0 until dimension - 1) rowArr[col] = rowArr[col + 1]
                    // Paint new right cell.
                    val z = newZStart + row
                    rowArr[dimension - 1] = WorldMap.renderToken(resident, newCenter, Coord(newX, z))
                }
            } else {
                // Shift each row's columns right by one, paint new leftmost column.
                val newX = newXStart
                for (row in 0 until dimension) {
                    val rowArr = grid[row]
                    val recycled = rowArr[dimension - 1]
                    for (col in dimension - 1 downTo 1) rowArr[col] = rowArr[col - 1]
                    // Paint new left cell.
                    val z = newZStart + row
                    rowArr[0] = WorldMap.renderToken(resident, newCenter, Coord(newX, z))
                }
            }
        }

        // ── Repaint previous player-token cell ────────────────────────────
        // The old center cell was rendered as a player token; after shifting
        // it is now at a non-center position and needs to show terrain instead.
        // Its new grid position: old center was (size, size); after shifting
        // by (dx, dz) it maps to (size - dz, size - dx) in the new grid.
        // Only repaint if it didn't fall off the edge (always true for |dx|,|dz| <= 1).
        val oldCenterRow = size - dz
        val oldCenterCol = size - dx
        if (oldCenterRow in 0 until dimension && oldCenterCol in 0 until dimension) {
            val prevCoord = Coord(newCenter.x - dx, newCenter.z - dz)
            grid[oldCenterRow][oldCenterCol] = WorldMap.renderToken(resident, newCenter, prevCoord)
        }

        // ── Repaint new center cell (player token) ────────────────────────
        grid[size][size] = WorldMap.renderToken(resident, newCenter, newCenter)

        cachedGrid = grid
        cachedCenter = newCenter
        return grid
    }

    private fun orientGrid(
        grid: Array<Array<String>>,
        resident: Resident,
        player: Player,
    ): Array<Array<String>> = if (resident.minimapOrientation == MinimapOrientation.FORWARD_UP) {
        val steps = rotationStepsForYaw(player.location.yaw)
        rotateGrid(grid, steps)
    } else {
        grid
    }
}

private fun rotationStepsForYaw(yaw: Float): Int {
    val normalized = ((yaw % 360) + 360) % 360
    return when {
        normalized in 45.0f..<135.0f -> 3
        normalized in 135.0f..<225.0f -> 0
        normalized in 225.0f..<315.0f -> 1
        else -> 2
    }
}

private fun rotateGrid(grid: Array<Array<String>>, steps: Int): Array<Array<String>> {
    var rotatedGrid = grid
    repeat(steps % 4) {
        rotatedGrid = rotate90CCW(rotatedGrid)
    }
    return rotatedGrid
}

private fun rotate90CCW(grid: Array<Array<String>>): Array<Array<String>> {
    val dimension = grid.size
    val rotated = Array(dimension) { Array(dimension) { "" } }
    for (row in 0 until dimension) {
        for (col in 0 until dimension) {
            rotated[dimension - 1 - col][row] = grid[row][col]
        }
    }
    return rotated
}
