/**
 * Deserializer
 */

package phonon.nodes.serdes

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import phonon.nodes.Nodes
import phonon.nodes.Nodes.createPortGroup
import phonon.nodes.TerritoryCaptureNotificationMode
import phonon.nodes.chat.ChatMode
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.language.PlayerLanguage
import phonon.nodes.objects.Coord
import phonon.nodes.objects.MinimapOrientation
import phonon.nodes.objects.Nation
import phonon.nodes.objects.PortGroup
import phonon.nodes.objects.PortTier
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.TerritoryIdArray
import phonon.nodes.objects.TerritoryPreprocessing
import phonon.nodes.objects.Town
import phonon.nodes.utils.Color
import phonon.nodes.utils.ItemStackCodec
import phonon.nodes.war.AttackMessageMode
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID

public object Deserializer {

    // =========================
    // WORLD (nodes + territories)
    //
    // "nodes" (resource definitions) is a small config section — parsed
    // as a JsonObject tree and passed to DefaultResourceAttributeLoader.
    //
    // "territories" is the large section (50-100k entries) — streamed
    // token-by-token into TerritoryPreprocessing objects, bypassing
    // TerritoryPreprocessing.loadFromJson(JsonObject) entirely.
    // =========================
    public fun worldFromJson(path: Path) {
        val territoriesPreprocessed: ArrayList<TerritoryPreprocessing> = ArrayList()

        JsonReader(
            InputStreamReader(FileInputStream(path.toFile()), StandardCharsets.UTF_8),
        ).use { reader ->
            reader.isLenient = false
            reader.beginObject()

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    // small config section — tree parse is fine
                    "nodes" -> {
                        val jsonNodes = JsonParser.parseReader(reader).asJsonObject
                        Nodes.loadResources(jsonNodes)
                    }

                    // large section — fully streamed
                    "territories" -> {
                        reader.beginObject()

                        while (reader.hasNext()) {
                            val idStr = reader.nextName()
                            val idOrNull = idStr.toIntOrNull()
                            if (idOrNull == null) {
                                reader.skipValue()
                                continue
                            }
                            val id = idOrNull

                            reader.beginObject()
                            try {
                                var name = ""
                                var color = 5
                                var coreChunk: Coord? = null
                                val chunks: MutableList<Coord> = mutableListOf()
                                val resourceNodes: MutableList<String> = mutableListOf()
                                val nodeTypes: MutableList<String> = mutableListOf()
                                val neighbors: MutableList<Int> = mutableListOf()
                                var bordersWilderness = false

                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "name" -> name = reader.nextString()
                                        "color" -> color = reader.nextInt()
                                        "isEdge" -> bordersWilderness = reader.nextBoolean()

                                        "coreChunk" -> {
                                            reader.beginArray()
                                            val cx = reader.nextInt()
                                            val cz = reader.nextInt()
                                            while (reader.hasNext()) reader.skipValue()
                                            reader.endArray()
                                            coreChunk = Coord(cx, cz)
                                        }

                                        // interleaved [x1, z1, x2, z2, ...]
                                        "chunks" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) {
                                                val cx = reader.nextInt()
                                                val cz = reader.nextInt()
                                                chunks.add(Coord(cx, cz))
                                            }
                                            reader.endArray()
                                        }

                                        "nodes" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) resourceNodes.add(reader.nextString())
                                            reader.endArray()
                                        }

                                        // "node_types" is the current key;
                                        // "node_type" is a legacy key that can be
                                        // a string, array of strings, or absent
                                        "node_types" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) nodeTypes.add(reader.nextString())
                                            reader.endArray()
                                        }

                                        "node_type" -> {
                                            when (reader.peek()) {
                                                JsonToken.STRING -> nodeTypes.add(reader.nextString())
                                                JsonToken.BEGIN_ARRAY -> {
                                                    reader.beginArray()
                                                    while (reader.hasNext()) nodeTypes.add(reader.nextString())
                                                    reader.endArray()
                                                }
                                                else -> reader.skipValue()
                                            }
                                        }

                                        "neighbors" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) neighbors.add(reader.nextInt())
                                            reader.endArray()
                                        }

                                        else -> reader.skipValue()
                                    }
                                }

                                reader.endObject()

                                if (coreChunk == null) {
                                    Nodes.logger?.warning("[Nodes] Territory $id missing coreChunk, skipping")
                                } else {
                                    territoriesPreprocessed.add(
                                        TerritoryPreprocessing(
                                            id = TerritoryId(id),
                                            name = name,
                                            color = color,
                                            core = coreChunk,
                                            chunks = chunks,
                                            bordersWilderness = bordersWilderness,
                                            neighbors = TerritoryIdArray(neighbors.toIntArray()),
                                            resourceNodes = resourceNodes,
                                            nodeTypes = nodeTypes,
                                        ),
                                    )
                                }
                            } catch (e: Exception) {
                                Nodes.logger?.warning("[Nodes] Skipping territory $idStr due to error: ${e.message}")
                            }
                        }

                        reader.endObject()
                    }

                    else -> reader.skipValue()
                }
            }

            reader.endObject()
        }

        Nodes.loadTerritoriesFromList(territoriesPreprocessed)
    }

    // =========================
    // TOWNS — fully streamed
    // =========================
    public fun townsFromJson(path: Path) {
        val towns: ArrayList<Town> = ArrayList()
        val townAllies: ArrayList<ArrayList<String>> = ArrayList()
        val townEnemies: ArrayList<ArrayList<String>> = ArrayList()
        val nations: ArrayList<Nation> = ArrayList()
        val nationAllies: ArrayList<ArrayList<String>> = ArrayList()
        val nationEnemies: ArrayList<ArrayList<String>> = ArrayList()

        JsonReader(
            InputStreamReader(FileInputStream(path.toFile()), StandardCharsets.UTF_8),
        ).use { reader ->
            reader.isLenient = false
            reader.beginObject()

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    // ===============================
                    // Residents
                    // ===============================
                    "residents" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val uuid = reader.nextName()
                            reader.beginObject()
                            try {
                                var claims = 0
                                var claimsTime = 0L
                                var prefix = ""
                                var suffix = ""
                                var trusted = false
                                var townCreateCooldown = 0L
                                var language: PlayerLanguage? = null
                                var minimapOrientation = MinimapOrientation.fromName(null)
                                var chatMode = ChatMode.fromName(null)
                                var attackMessageMode = AttackMessageMode.fromLegacyTownOnly(false)
                                var territoryCaptureNotificationMode = TerritoryCaptureNotificationMode.fromName(null)
                                var pointDisabled = false
                                var oreBlocksBroken = 0L

                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "claims" -> {
                                            reader.beginArray()
                                            if (reader.hasNext()) claims = reader.nextInt()
                                            if (reader.hasNext()) claimsTime = reader.nextLong()
                                            while (reader.hasNext()) reader.skipValue()
                                            reader.endArray()
                                        }
                                        "prefix" -> prefix = reader.nextString()
                                        "suffix" -> suffix = reader.nextString()
                                        "trust" -> trusted = reader.nextBoolean()
                                        "townCool" -> townCreateCooldown = reader.nextLong()
                                        "lang" -> {
                                            if (reader.peek() == JsonToken.NULL) {
                                                reader.nextNull()
                                            } else {
                                                language = PlayerLanguage.fromCode(reader.nextString())
                                            }
                                        }
                                        "minimapOrientation" -> minimapOrientation = MinimapOrientation.fromName(reader.nextString())
                                        "chatMode" -> chatMode = ChatMode.fromName(reader.nextString())
                                        "attackMessageMode" -> attackMessageMode = AttackMessageMode.fromName(reader.nextString())
                                        "townAttackMessagesOnly" -> {
                                            // legacy fallback — only used if "attackMessageMode" key absent
                                            if (attackMessageMode == AttackMessageMode.fromLegacyTownOnly(false)) {
                                                attackMessageMode = AttackMessageMode.fromLegacyTownOnly(reader.nextBoolean())
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        "territoryCaptureNotificationMode" -> territoryCaptureNotificationMode = TerritoryCaptureNotificationMode.fromName(reader.nextString())
                                        "pointDisabled" -> pointDisabled = reader.nextBoolean()
                                        "oreBlocksBroken" -> oreBlocksBroken = reader.nextLong()
                                        else -> reader.skipValue()
                                    }
                                }

                                Nodes.loadResident(
                                    UUID.fromString(uuid),
                                    claims,
                                    claimsTime,
                                    prefix,
                                    suffix,
                                    trusted,
                                    townCreateCooldown,
                                    language,
                                    minimapOrientation,
                                    chatMode,
                                    attackMessageMode,
                                    territoryCaptureNotificationMode,
                                    pointDisabled,
                                    oreBlocksBroken,
                                )

                                reader.endObject()
                            } catch (e: Exception) {
                                throw RuntimeException("Invalid resident JSON at $uuid", e)
                            }
                        }
                        reader.endObject()
                    }

                    // ===============================
                    // Towns
                    // ===============================
                    "towns" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            reader.beginObject()
                            try {
                                var uuid = UUID.randomUUID()
                                var homeId: Int? = null
                                var leader: UUID? = null
                                var spawn: Location? = null
                                var color: Color? = null
                                var claimsBonus = 0
                                var claimsAnnexed = 0
                                var claimsPenalty = 0
                                var claimsPenaltyTime = 0L
                                val residentsUUID = ArrayList<UUID>()
                                val officersUUID = ArrayList<UUID>()
                                val generalsUUID = ArrayList<UUID>()
                                val territoryIds = ArrayList<Int>()
                                val capturedIds = ArrayList<Int>()
                                val annexedIds = ArrayList<Int>()
                                val income: EnumMap<Material, Int> = EnumMap(Material::class.java)
                                val incomeSpawnEgg: EnumMap<EntityType, Int> = EnumMap(EntityType::class.java)
                                val incomeItems: MutableList<org.bukkit.inventory.ItemStack> = mutableListOf()
                                val allies = ArrayList<String>()
                                val enemies = ArrayList<String>()
                                val permissions: EnumMap<TownPermissions, EnumSet<PermissionsGroup>> =
                                    enumValues<TownPermissions>().toList().associateWithTo(
                                        EnumMap(TownPermissions::class.java),
                                    ) { EnumSet.noneOf(PermissionsGroup::class.java) }
                                // default permissions
                                permissions[TownPermissions.BUILD]!!.add(PermissionsGroup.TOWN)
                                permissions[TownPermissions.DESTROY]!!.add(PermissionsGroup.TOWN)
                                permissions[TownPermissions.INTERACT]!!.add(PermissionsGroup.TOWN)
                                permissions[TownPermissions.CHESTS]!!.add(PermissionsGroup.TOWN)
                                permissions[TownPermissions.USE_ITEMS]!!.add(PermissionsGroup.TOWN)
                                var isOpen = false
                                val protectedBlocks = hashSetOf<Block>()
                                var moveHomeCooldown = 0L
                                var outpostCredits = 0
                                var townPoints = 0L
                                val outposts = HashMap<String, Triple<Int, Location, PortTier>>()

                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "uuid" -> uuid = UUID.fromString(reader.nextString())
                                        "home" -> homeId = reader.nextInt()
                                        "leader" -> {
                                            if (reader.peek() == JsonToken.NULL) {
                                                reader.nextNull()
                                            } else {
                                                val s = reader.nextString()
                                                leader = if (s.isNullOrEmpty()) null else UUID.fromString(s)
                                            }
                                        }
                                        "spawn" -> {
                                            reader.beginArray()
                                            val coords = DoubleArray(3)
                                            var i = 0
                                            while (reader.hasNext() && i < 3) coords[i++] = reader.nextDouble()
                                            while (reader.hasNext()) reader.skipValue()
                                            reader.endArray()
                                            if (i == 3) spawn = Location(Bukkit.getWorlds()[0], coords[0], coords[1], coords[2])
                                        }
                                        "color" -> {
                                            reader.beginArray()
                                            val rgb = IntArray(3)
                                            var i = 0
                                            while (reader.hasNext() && i < 3) rgb[i++] = reader.nextInt()
                                            while (reader.hasNext()) reader.skipValue()
                                            reader.endArray()
                                            if (i == 3) color = Color(rgb[0], rgb[1], rgb[2])
                                        }
                                        "claimsBonus" -> claimsBonus = reader.nextInt()
                                        "claimsAnnex" -> claimsAnnexed = reader.nextInt()
                                        "claimsPenalty" -> {
                                            reader.beginArray()
                                            if (reader.hasNext()) claimsPenalty = reader.nextInt()
                                            if (reader.hasNext()) claimsPenaltyTime = reader.nextLong()
                                            while (reader.hasNext()) reader.skipValue()
                                            reader.endArray()
                                        }
                                        "residents" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) residentsUUID.add(UUID.fromString(reader.nextString()))
                                            reader.endArray()
                                        }
                                        "officers" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) officersUUID.add(UUID.fromString(reader.nextString()))
                                            reader.endArray()
                                        }
                                        "generals" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) generalsUUID.add(UUID.fromString(reader.nextString()))
                                            reader.endArray()
                                        }
                                        "territories" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) territoryIds.add(reader.nextInt())
                                            reader.endArray()
                                        }
                                        "captured" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) capturedIds.add(reader.nextInt())
                                            reader.endArray()
                                        }
                                        "annexed" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) annexedIds.add(reader.nextInt())
                                            reader.endArray()
                                        }
                                        "income" -> {
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                val type = reader.nextName()
                                                val amount = reader.nextInt()
                                                Material.matchMaterial(type)?.let { income[it] = amount }
                                            }
                                            reader.endObject()
                                        }
                                        "incomeEgg" -> {
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                val type = reader.nextName()
                                                val amount = reader.nextInt()
                                                try {
                                                    incomeSpawnEgg[EntityType.valueOf(type)] = amount
                                                } catch (_: IllegalArgumentException) {}
                                            }
                                            reader.endObject()
                                        }
                                        "incomeItems" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) {
                                                ItemStackCodec.decode(reader.nextString())?.let { incomeItems.add(it) }
                                            }
                                            reader.endArray()
                                        }
                                        "allies" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) allies.add(reader.nextString())
                                            reader.endArray()
                                        }
                                        "enemies" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) enemies.add(reader.nextString())
                                            reader.endArray()
                                        }
                                        "perms" -> {
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                val type = reader.nextName()
                                                reader.beginArray()
                                                try {
                                                    val permType = TownPermissions.valueOf(type)
                                                    permissions[permType]!!.clear()
                                                    while (reader.hasNext()) {
                                                        permissions[permType]!!.add(PermissionsGroup.values[reader.nextInt()])
                                                    }
                                                } catch (_: IllegalArgumentException) {
                                                    System.err.println("Invalid town permission: $type")
                                                    while (reader.hasNext()) reader.skipValue()
                                                }
                                                reader.endArray()
                                            }
                                            reader.endObject()
                                        }
                                        "open" -> isOpen = reader.nextBoolean()
                                        "protect" -> {
                                            val world = Bukkit.getWorlds()[0]
                                            reader.beginArray()
                                            while (reader.hasNext()) {
                                                reader.beginArray()
                                                val coords = IntArray(3)
                                                var i = 0
                                                while (reader.hasNext() && i < 3) coords[i++] = reader.nextInt()
                                                while (reader.hasNext()) reader.skipValue()
                                                reader.endArray()
                                                if (i == 3) protectedBlocks.add(world.getBlockAt(coords[0], coords[1], coords[2]))
                                            }
                                            reader.endArray()
                                        }
                                        "homeCool" -> moveHomeCooldown = reader.nextLong()
                                        "outpostCredits" -> outpostCredits = reader.nextInt()
                                        "townPoints" -> townPoints = reader.nextLong()
                                        "outpost" -> {
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                val outpostName = reader.nextName()
                                                reader.beginArray()
                                                val terrId = if (reader.hasNext()) reader.nextInt() else -1
                                                var spawnX = if (reader.hasNext()) reader.nextDouble() else -1.0
                                                var spawnY = if (reader.hasNext()) reader.nextDouble() else -1.0
                                                var spawnZ = if (reader.hasNext()) reader.nextDouble() else -1.0
                                                val tier = if (reader.hasNext()) {
                                                    try {
                                                        PortTier.valueOf(reader.nextString().uppercase())
                                                    } catch (_: Exception) {
                                                        PortTier.TIER1
                                                    }
                                                } else {
                                                    PortTier.TIER1
                                                }
                                                while (reader.hasNext()) reader.skipValue()
                                                reader.endArray()
                                                if (terrId != -1) {
                                                    if (spawnX == -1.0 || spawnY == -1.0 || spawnZ == -1.0) {
                                                        val territory = Nodes.getTerritoryFromId(TerritoryId(terrId))
                                                        if (territory != null) {
                                                            val defaultSpawn = Nodes.getDefaultSpawnLocation(territory)
                                                            spawnX = defaultSpawn.x
                                                            spawnY = defaultSpawn.y
                                                            spawnZ = defaultSpawn.z
                                                        }
                                                    }
                                                    outposts[outpostName] = Triple(
                                                        terrId,
                                                        Location(Bukkit.getWorlds()[0], spawnX, spawnY, spawnZ),
                                                        tier,
                                                    )
                                                }
                                            }
                                            reader.endObject()
                                        }
                                        else -> reader.skipValue()
                                    }
                                }

                                if (homeId == null) {
                                    System.err.println("Cannot create $name: no home")
                                } else {
                                    val townObject = Nodes.loadTown(
                                        uuid, name, leader, homeId, spawn, color,
                                        residentsUUID, generalsUUID, officersUUID,
                                        territoryIds, capturedIds, annexedIds,
                                        claimsBonus, claimsAnnexed,
                                        claimsPenalty, claimsPenaltyTime,
                                        income, incomeSpawnEgg, incomeItems,
                                        permissions, isOpen, protectedBlocks,
                                        moveHomeCooldown, outpostCredits, townPoints,
                                        outposts,
                                    )
                                    if (townObject != null) {
                                        towns.add(townObject)
                                        townAllies.add(allies)
                                        townEnemies.add(enemies)
                                    }
                                }

                                reader.endObject()
                            } catch (e: Exception) {
                                throw RuntimeException("Invalid town JSON at $name", e)
                            }
                        }
                        reader.endObject()
                    }

                    // ===============================
                    // Nations
                    // ===============================
                    "nations" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            reader.beginObject()
                            try {
                                var uuid = UUID.randomUUID()
                                var color: Color? = null
                                val townNames = ArrayList<String>()
                                var capitalName: String? = null
                                val allies = ArrayList<String>()
                                val enemies = ArrayList<String>()

                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "uuid" -> uuid = UUID.fromString(reader.nextString())
                                        "color" -> {
                                            reader.beginArray()
                                            val rgb = IntArray(3)
                                            var i = 0
                                            while (reader.hasNext() && i < 3) rgb[i++] = reader.nextInt()
                                            while (reader.hasNext()) reader.skipValue()
                                            reader.endArray()
                                            if (i == 3) color = Color(rgb[0], rgb[1], rgb[2])
                                        }
                                        "towns" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) townNames.add(reader.nextString())
                                            reader.endArray()
                                        }
                                        "capital" -> capitalName = reader.nextString()
                                        "allies" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) allies.add(reader.nextString())
                                            reader.endArray()
                                        }
                                        "enemies" -> {
                                            reader.beginArray()
                                            while (reader.hasNext()) enemies.add(reader.nextString())
                                            reader.endArray()
                                        }
                                        else -> reader.skipValue()
                                    }
                                }

                                if (capitalName == null) {
                                    System.err.println("Capital for: $name not found, setting it to ${townNames[0]}")
                                    capitalName = townNames[0]
                                }

                                val nationObject = Nodes.loadNation(uuid, name, capitalName, color, townNames)
                                nations.add(nationObject)
                                nationAllies.add(allies)
                                nationEnemies.add(enemies)

                                reader.endObject()
                            } catch (e: Exception) {
                                throw RuntimeException("Invalid nation JSON at $name", e)
                            }
                        }
                        reader.endObject()
                    }

                    else -> reader.skipValue()
                }
            }

            reader.endObject()
        }

        Nodes.loadDiplomacy(towns, townAllies, townEnemies, nations, nationAllies, nationEnemies)
    }

    // =========================
    // PORTS — tree parsing (file is small)
    // =========================
    public fun portsFromJson(path: Path) {
        val json = com.google.gson.JsonParser.parseReader(
            InputStreamReader(FileInputStream(path.toFile()), StandardCharsets.UTF_8),
        )
        val jsonObj = json.getAsJsonObject()

        jsonObj.getAsJsonArray("groups")?.forEach { createPortGroup(it.asString) }

        jsonObj.get("ports")?.getAsJsonObject()?.keySet()?.forEach { name ->
            val port = jsonObj.getAsJsonObject("ports")[name].getAsJsonObject()
            val x = port.get("x").asInt
            val z = port.get("z").asInt
            val isPublic = port.get("isPublic")?.asBoolean ?: false
            val tierName = port.get("tier")?.asString?.uppercase()
            val tier = try {
                if (tierName != null) PortTier.valueOf(tierName) else PortTier.TIER1
            } catch (_: Exception) {
                PortTier.TIER1
            }
            val groups = hashSetOf<PortGroup>()
            port.get("groups")?.getAsJsonArray()?.forEach { el ->
                Nodes.getPortGroupFromName(el.asString)?.let { groups.add(it) }
            }
            Nodes.loadPort(name, x, z, groups, isPublic, tier)
        }
    }
}
