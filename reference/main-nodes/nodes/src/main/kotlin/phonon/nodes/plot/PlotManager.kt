package phonon.nodes.plot

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.constants.TownPermissions
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Town
import java.io.File
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID

private data class ChunkKey(val world: String, val x: Int, val z: Int)

public object PlotManager {
    private val plotsByName: LinkedHashMap<String, Plot> = linkedMapOf()
    private val plotNamesByChunk: HashMap<ChunkKey, MutableSet<String>> = hashMapOf()
    private val selectionsPos1: HashMap<UUID, Location> = hashMapOf()
    private val selectionsPos2: HashMap<UUID, Location> = hashMapOf()
    private val selectionModePlayers: HashSet<UUID> = hashSetOf()

    private var dirty: Boolean = false
    private var saveTask: BukkitTask? = null

    private val dataFile: File
        get() = File(Config.pathPlugin, "plots.yml")

    fun load() {
        plotsByName.clear()
        plotNamesByChunk.clear()

        if (!dataFile.exists()) {
            Nodes.logger?.info("No plot data found, starting fresh")
            return
        }

        try {
            val config = YamlConfiguration.loadConfiguration(dataFile)
            val section = config.getConfigurationSection("plots") ?: return
            for (key in section.getKeys(false)) {
                val base = section.getConfigurationSection(key) ?: continue
                val name = base.getString("name", key) ?: key
                val townName = base.getString("town") ?: continue
                val world = base.getString("world") ?: continue

                val sel = PlotSelection(
                    world = world,
                    x1 = base.getInt("x1"),
                    y1 = base.getInt("y1"),
                    z1 = base.getInt("z1"),
                    x2 = base.getInt("x2"),
                    y2 = base.getInt("y2"),
                    z2 = base.getInt("z2"),
                )

                val ownerUuid = base.getString("ownerUuid")?.takeIf { it.isNotBlank() }?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                }
                val ownerName = base.getString("ownerName")

                val permissions: EnumMap<PlotPermissionType, EnumSet<PlotPermissionGroup>> =
                    EnumMap(PlotPermissionType::class.java)
                val residentPermissions: EnumMap<PlotPermissionType, LinkedHashMap<UUID, String>> =
                    EnumMap(PlotPermissionType::class.java)
                for (type in enumValues<PlotPermissionType>()) {
                    val strList = base.getStringList("permissions.${type.name}")
                    val groups = EnumSet.noneOf(PlotPermissionGroup::class.java)
                    for (str in strList) {
                        runCatching { PlotPermissionGroup.valueOf(str.uppercase()) }.getOrNull()?.let { groups.add(it) }
                    }
                    if (groups.isEmpty()) {
                        groups.add(PlotPermissionGroup.OWNER)
                        groups.add(PlotPermissionGroup.OFFICER)
                    }
                    permissions[type] = groups

                    val residents = linkedMapOf<UUID, String>()
                    for (entry in base.getMapList("residentPermissions.${type.name}")) {
                        val uuid = (entry["uuid"] as? String)
                            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                            ?: continue
                        val name = ((entry["name"] as? String)?.takeIf { it.isNotBlank() })
                            ?: Nodes.getResidentFromUUID(uuid)?.name
                            ?: Bukkit.getOfflinePlayer(uuid).name
                            ?: uuid.toString()
                        residents[uuid] = name
                    }
                    residentPermissions[type] = residents
                }

                val plot = Plot(
                    id = key,
                    name = name,
                    townName = townName,
                    ownerUuid = ownerUuid,
                    ownerName = ownerName,
                    selection = sel,
                    permissions = permissions,
                    residentPermissions = residentPermissions,
                )

                plotsByName[key.lowercase()] = plot
                indexPlot(plot)
            }

            Nodes.logger?.info("Loaded ${plotsByName.size} plots")
        } catch (e: Exception) {
            Nodes.logger?.severe("Failed to load plot data: ${e.message}")
        }
    }

    fun initializeAutoSave() {
        saveTask?.cancel()
        val plugin = Nodes.plugin ?: return
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            Runnable {
                if (!dirty) return@Runnable
                save()
            },
            20L * 45L,
            20L * 45L,
        )
    }

    fun shutdown() {
        saveTask?.cancel()
        saveTask = null
        if (dirty) {
            save()
        }
    }

    fun save() {
        try {
            if (!dataFile.parentFile.exists()) {
                dataFile.parentFile.mkdirs()
            }

            val cfg = YamlConfiguration()
            for ((id, plot) in plotsByName) {
                val base = "plots.$id"
                cfg.set("$base.name", plot.name)
                cfg.set("$base.town", plot.townName)
                cfg.set("$base.world", plot.selection.world)
                cfg.set("$base.x1", plot.selection.x1)
                cfg.set("$base.y1", plot.selection.y1)
                cfg.set("$base.z1", plot.selection.z1)
                cfg.set("$base.x2", plot.selection.x2)
                cfg.set("$base.y2", plot.selection.y2)
                cfg.set("$base.z2", plot.selection.z2)
                cfg.set("$base.ownerUuid", plot.ownerUuid?.toString() ?: "")
                cfg.set("$base.ownerName", plot.ownerName ?: "")

                for (type in enumValues<PlotPermissionType>()) {
                    val groups = plot.permissions[type] ?: EnumSet.noneOf(PlotPermissionGroup::class.java)
                    cfg.set("$base.permissions.${type.name}", groups.map { it.name })
                    val residents = plot.residentPermissions[type]?.map { (uuid, name) ->
                        mapOf("uuid" to uuid.toString(), "name" to name)
                    } ?: emptyList()
                    cfg.set("$base.residentPermissions.${type.name}", residents)
                }
            }

            cfg.save(dataFile)
            dirty = false
        } catch (e: Exception) {
            Nodes.logger?.severe("Failed to save plot data: ${e.message}")
        }
    }

    fun setSelectionPos1(playerUuid: UUID, location: Location) {
        selectionsPos1[playerUuid] = location.clone()
    }

    fun setSelectionPos2(playerUuid: UUID, location: Location) {
        selectionsPos2[playerUuid] = location.clone()
    }

    fun clearSelection(playerUuid: UUID) {
        selectionsPos1.remove(playerUuid)
        selectionsPos2.remove(playerUuid)
        selectionModePlayers.remove(playerUuid)
    }

    fun setSelectionMode(playerUuid: UUID, enabled: Boolean) {
        if (enabled) {
            selectionModePlayers.add(playerUuid)
        } else {
            selectionModePlayers.remove(playerUuid)
        }
    }

    fun isSelectionMode(playerUuid: UUID): Boolean = selectionModePlayers.contains(playerUuid)

    fun getSelection(playerUuid: UUID): PlotSelection? {
        val pos1 = selectionsPos1[playerUuid] ?: return null
        val pos2 = selectionsPos2[playerUuid] ?: return null
        val world = pos1.world ?: return null
        if (pos2.world?.name != world.name) return null

        return PlotSelection(
            world = world.name,
            x1 = pos1.blockX,
            y1 = pos1.blockY,
            z1 = pos1.blockZ,
            x2 = pos2.blockX,
            y2 = pos2.blockY,
            z2 = pos2.blockZ,
        )
    }

    fun createPlot(name: String, town: Town, owner: Resident?, selection: PlotSelection): Result<Plot> {
        val id = name.lowercase()
        if (plotsByName.containsKey(id)) {
            return Result.failure(IllegalStateException("Plot already exists"))
        }

        if (!selectionInsideTown(selection, town)) {
            return Result.failure(IllegalStateException("Plot must be fully inside your town territory"))
        }

        for (existing in plotsByName.values) {
            if (existing.selection.intersects(selection)) {
                return Result.failure(IllegalStateException("Plot overlaps with existing plot '${existing.name}'"))
            }
        }

        val permissions: EnumMap<PlotPermissionType, EnumSet<PlotPermissionGroup>> = EnumMap(PlotPermissionType::class.java)
        val residentPermissions: EnumMap<PlotPermissionType, LinkedHashMap<UUID, String>> = EnumMap(PlotPermissionType::class.java)
        for (type in enumValues<PlotPermissionType>()) {
            permissions[type] = EnumSet.of(PlotPermissionGroup.OWNER, PlotPermissionGroup.OFFICER)
            residentPermissions[type] = linkedMapOf()
        }

        val plot = Plot(
            id = id,
            name = name,
            townName = town.name,
            ownerUuid = owner?.uuid,
            ownerName = owner?.name,
            selection = selection,
            permissions = permissions,
            residentPermissions = residentPermissions,
        )

        plotsByName[id] = plot
        indexPlot(plot)
        dirty = true
        return Result.success(plot)
    }

    fun deletePlot(name: String): Boolean {
        val id = name.lowercase()
        val plot = plotsByName.remove(id) ?: return false
        deindexPlot(plot)
        dirty = true
        return true
    }

    fun setOwner(plot: Plot, resident: Resident?) {
        plot.ownerUuid = resident?.uuid
        plot.ownerName = resident?.name
        dirty = true
    }

    fun setPermission(plot: Plot, type: PlotPermissionType, group: PlotPermissionGroup, allow: Boolean) {
        val groups = plot.permissions[type] ?: EnumSet.noneOf(PlotPermissionGroup::class.java)
        if (allow) {
            groups.add(group)
        } else {
            groups.remove(group)
        }
        plot.permissions[type] = groups
        dirty = true
    }

    fun setResidentPermission(plot: Plot, type: PlotPermissionType, resident: Resident, allow: Boolean) {
        val residents = plot.residentPermissions[type] ?: linkedMapOf()
        if (allow) {
            residents[resident.uuid] = resident.name
        } else {
            residents.remove(resident.uuid)
        }
        plot.residentPermissions[type] = residents
        dirty = true
    }

    fun clearPermission(plot: Plot, type: PlotPermissionType) {
        plot.permissions[type] = EnumSet.noneOf(PlotPermissionGroup::class.java)
        dirty = true
    }

    fun getPlot(name: String): Plot? = plotsByName[name.lowercase()]

    fun getPlotAt(block: Block): Plot? {
        val key = ChunkKey(block.world.name, block.chunk.x, block.chunk.z)
        val names = plotNamesByChunk[key] ?: return null
        for (name in names) {
            val plot = plotsByName[name] ?: continue
            if (plot.selection.contains(block)) {
                return plot
            }
        }
        return null
    }

    fun getNearbyPlot(location: Location, radius: Int = 32): Plot? {
        val world = location.world ?: return null
        val minX = location.blockX - radius
        val maxX = location.blockX + radius
        val minZ = location.blockZ - radius
        val maxZ = location.blockZ + radius

        val minCx = Math.floorDiv(minX, 16)
        val maxCx = Math.floorDiv(maxX, 16)
        val minCz = Math.floorDiv(minZ, 16)
        val maxCz = Math.floorDiv(maxZ, 16)

        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                val key = ChunkKey(world.name, cx, cz)
                val names = plotNamesByChunk[key] ?: continue
                for (name in names) {
                    val plot = plotsByName[name] ?: continue
                    if (plot.selection.contains(location)) {
                        return plot
                    }
                }
            }
        }

        return null
    }

    fun listTownPlots(townName: String): List<Plot> = plotsByName.values.filter { it.townName.equals(townName, ignoreCase = true) }

    fun listPlayerPlots(name: String): List<Plot> {
        val lower = name.lowercase()
        return plotsByName.values.filter { p ->
            val ownerName = p.ownerName?.lowercase()
            ownerName == lower
        }
    }

    fun allPlots(): List<Plot> = plotsByName.values.toList()

    fun hasPermission(resident: Resident, block: Block, type: PlotPermissionType): Boolean {
        val plot = getPlotAt(block) ?: return true
        return plot.isAllowed(resident, type)
    }

    private fun indexPlot(plot: Plot) {
        for (cx in plot.selection.chunkRangeX()) {
            for (cz in plot.selection.chunkRangeZ()) {
                val key = ChunkKey(plot.selection.world, cx, cz)
                val bucket = plotNamesByChunk.getOrPut(key) { linkedSetOf() }
                bucket.add(plot.id)
            }
        }
    }

    private fun deindexPlot(plot: Plot) {
        for (cx in plot.selection.chunkRangeX()) {
            for (cz in plot.selection.chunkRangeZ()) {
                val key = ChunkKey(plot.selection.world, cx, cz)
                val bucket = plotNamesByChunk[key] ?: continue
                bucket.remove(plot.id)
                if (bucket.isEmpty()) {
                    plotNamesByChunk.remove(key)
                }
            }
        }
    }

    private fun selectionInsideTown(selection: PlotSelection, town: Town): Boolean {
        for (cx in selection.chunkRangeX()) {
            for (cz in selection.chunkRangeZ()) {
                val terr = Nodes.getTerritoryFromChunkCoords(cx, cz) ?: return false
                if (terr.town !== town) {
                    return false
                }
            }
        }
        return true
    }

    private fun defaultGroupFor(type: PlotPermissionType): PlotPermissionGroup = PlotPermissionGroup.OWNER

    fun mapTownPermissionToPlotType(perm: TownPermissions): PlotPermissionType = when (perm) {
        TownPermissions.BUILD -> PlotPermissionType.BUILD
        TownPermissions.DESTROY -> PlotPermissionType.DESTROY
        TownPermissions.INTERACT -> PlotPermissionType.INTERACT
        TownPermissions.CHESTS -> PlotPermissionType.CONTAINERS
        TownPermissions.USE_ITEMS -> PlotPermissionType.ITEMS
        TownPermissions.INCOME -> PlotPermissionType.INTERACT
    }

    fun showAllPlotsTo(player: org.bukkit.entity.Player, seconds: Int = 5) {
        val plugin = Nodes.plugin ?: return
        val world = player.world
        val all = allPlots().filter { it.selection.world == world.name }
        if (all.isEmpty()) {
            return
        }

        val runs = (seconds * 20) / 5
        var count = 0
        var task: BukkitTask? = null
        task = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                if (!player.isOnline || count >= runs) {
                    task?.cancel()
                    return@Runnable
                }

                for (plot in all) {
                    val s = plot.selection
                    val minY = s.minY.coerceAtLeast(world.minHeight + 1)
                    val maxY = s.maxY.coerceAtMost(world.maxHeight - 1)
                    val dust = Particle.DustOptions(Color.fromRGB(0, 255, 64), 1.0f)

                    for (x in s.minX..s.maxX step 2) {
                        for (y in minY..maxY step 2) {
                            player.spawnParticle(Particle.DUST, x + 0.5, y.toDouble(), s.minZ + 0.5, 1, 0.0, 0.0, 0.0, 0.0, dust)
                            player.spawnParticle(Particle.DUST, x + 0.5, y.toDouble(), s.maxZ + 0.5, 1, 0.0, 0.0, 0.0, 0.0, dust)
                        }
                    }
                    for (z in s.minZ..s.maxZ step 2) {
                        for (y in minY..maxY step 2) {
                            player.spawnParticle(Particle.DUST, s.minX + 0.5, y.toDouble(), z + 0.5, 1, 0.0, 0.0, 0.0, 0.0, dust)
                            player.spawnParticle(Particle.DUST, s.maxX + 0.5, y.toDouble(), z + 0.5, 1, 0.0, 0.0, 0.0, 0.0, dust)
                        }
                    }
                }

                count += 1
            },
            0L,
            5L,
        )
    }
}
