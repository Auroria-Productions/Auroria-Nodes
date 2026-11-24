import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import phonon.nodes.Nodes
import phonon.nodes.NodesExtensions
import phonon.nodes.miningtower.MiningTowerConfig
import phonon.nodes.miningtower.MiningTowerSubmission
import phonon.nodes.miningtower.SubmissionStatus
import phonon.nodes.objects.TerritoryId
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Manages mining tower submissions and cooldowns.
 *
 * Key fixes vs original:
 *  - save() is now async to avoid main-thread lag spikes.
 *  - approveSubmission() rejects any previously approved tower for the same town,
 *    ensuring exactly one active tower per town at all times.
 *  - data class → plain class for MiningTowerSubmission (see above).
 */
object MiningTowerManager {

    var enabled: Boolean = true

    private val submissions = mutableMapOf<UUID, MiningTowerSubmission>()

    // Cooldown tracking: townName -> last submission timestamp
    // (was territoryId-keyed, but a town can submit from any territory so
    //  keying by town is the correct guard against repeated submissions)
    private val townLastSubmission = mutableMapOf<String, Long>()

    private val dataFile: File
        get() = File(NodesExtensions.instance.dataFolder, "miningtowers.yml")

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    fun load() {
        if (!dataFile.exists()) {
            NodesExtensions.instance.logger.info("No mining tower data found, starting fresh")
            return
        }

        try {
            val config = YamlConfiguration.loadConfiguration(dataFile)
            val submissionsSection = config.getConfigurationSection("submissions") ?: return

            for (key in submissionsSection.getKeys(false)) {
                val id = UUID.fromString(key)
                val path = "submissions.$key"

                val townName = config.getString("$path.town") ?: continue
                val submitterUuid = UUID.fromString(config.getString("$path.submitter-uuid") ?: continue)
                val submitterName = config.getString("$path.submitter-name") ?: "Unknown"
                val worldName = config.getString("$path.world") ?: continue
                val x = config.getDouble("$path.x")
                val y = config.getDouble("$path.y")
                val z = config.getDouble("$path.z")
                val yaw = config.getDouble("$path.yaw").toFloat()
                val pitch = config.getDouble("$path.pitch").toFloat()
                val submittedAt = config.getLong("$path.submitted-at")
                val status = SubmissionStatus.valueOf(config.getString("$path.status", "PENDING")!!)
                val ratedTier = config.getString("$path.rated-tier")
                val ratedByStr = config.getString("$path.rated-by")
                val ratedBy = if (ratedByStr != null) UUID.fromString(ratedByStr) else null
                val ratedAt = if (config.contains("$path.rated-at")) config.getLong("$path.rated-at") else null

                val territoryIdStr = config.getString("$path.territory-id")
                val territoryId = territoryIdStr?.toIntOrNull()?.let { TerritoryId(it) }

                val world = NodesExtensions.instance.server.getWorld(worldName)
                if (world == null) {
                    NodesExtensions.instance.logger.warning("World $worldName not found for submission $id, skipping")
                    continue
                }

                val location = Location(world, x, y, z, yaw, pitch)
                val submission = MiningTowerSubmission(
                    id, townName, submitterUuid, submitterName, location, territoryId,
                    submittedAt, status, ratedTier, ratedBy, ratedAt,
                )
                submissions[id] = submission

                // Seed cooldown tracking
                if (status == SubmissionStatus.PENDING || status == SubmissionStatus.APPROVED) {
                    val existing = townLastSubmission[townName]
                    if (existing == null || submittedAt > existing) {
                        townLastSubmission[townName] = submittedAt
                    }
                }
            }

            NodesExtensions.instance.logger.info("Loaded ${submissions.size} mining tower submissions")
        } catch (e: Exception) {
            NodesExtensions.instance.logger.severe("Failed to load mining tower data: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Async save — never blocks the main thread.
     */
    fun save() {
        // Snapshot the data on the main thread, then write off-thread
        val snapshot = submissions.values.toList()

        // If the plugin is disabling (e.g. PlugMan unload), Bukkit will reject new tasks.
        // In that case, fall back to a synchronous write.
        val plugin = NodesExtensions.instance
        if (!plugin.isEnabled) {
            saveSnapshot(snapshot)
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { saveSnapshot(snapshot) })
    }

    private fun saveSnapshot(snapshot: List<MiningTowerSubmission>) {
        try {
            val config = YamlConfiguration()
            for (submission in snapshot) {
                val path = "submissions.${submission.id}"
                config.set("$path.town", submission.townName)
                config.set("$path.submitter-uuid", submission.submitterUuid.toString())
                config.set("$path.submitter-name", submission.submitterName)
                config.set("$path.world", submission.location.world?.name)
                config.set("$path.x", submission.location.x)
                config.set("$path.y", submission.location.y)
                config.set("$path.z", submission.location.z)
                config.set("$path.yaw", submission.location.yaw.toDouble())
                config.set("$path.pitch", submission.location.pitch.toDouble())
                config.set("$path.submitted-at", submission.submittedAt)
                config.set("$path.status", submission.status.name)
                config.set("$path.rated-tier", submission.ratedTier)
                config.set("$path.rated-by", submission.ratedBy?.toString())
                config.set("$path.rated-at", submission.ratedAt)
                config.set("$path.territory-id", submission.territoryId?.toInt())
            }

            dataFile.parentFile?.mkdirs()
            config.save(dataFile)
        } catch (e: Exception) {
            NodesExtensions.instance.logger.severe("Failed to save mining tower data: ${e.message}")
            e.printStackTrace()
        }
    }

    // -------------------------------------------------------------------------
    // Cooldown — keyed by town name
    // -------------------------------------------------------------------------

    fun canSubmit(townName: String): Boolean {
        val lastSubmit = townLastSubmission[townName] ?: return true
        val cooldownMillis = TimeUnit.HOURS.toMillis(MiningTowerConfig.submissionCooldownHours.toLong())
        return (System.currentTimeMillis() - lastSubmit) >= cooldownMillis
    }

    /** Returns true if <townName> currently has an active (unexpired) cooldown. */
    fun hasCooldown(townName: String): Boolean = !canSubmit(townName)

    /**
     * Clears the cooldown for [townName] entirely so they can submit immediately.
     * Staff command: /miningtower cooldown remove <town>
     */
    fun clearCooldown(townName: String) {
        townLastSubmission.remove(townName)
    }

    /**
     * Artificially sets a cooldown of [hours] hours for [townName].
     * A value of e.g. 24.0 means the town cannot submit for 24 hours from now.
     * Staff command: /miningtower cooldown add <town> <hours>
     */
    fun setCooldown(townName: String, hours: Double) {
        val cooldownMillis = (hours * 3_600_000L).toLong()
        // We store the "last submission" timestamp such that the cooldown expires in `hours` hours.
        // lastSubmit + configCooldown = now + desiredRemaining
        // => lastSubmit = now + desiredRemaining - configCooldown
        val configCooldownMillis = TimeUnit.HOURS.toMillis(MiningTowerConfig.submissionCooldownHours.toLong())
        townLastSubmission[townName] = System.currentTimeMillis() + cooldownMillis - configCooldownMillis
    }

    /** Overload for callers that have a TerritoryId — looks up the town first. */
    fun canSubmit(territoryId: TerritoryId): Boolean {
        val town = phonon.nodes.Nodes.getTerritoryFromBlock(0, 0)?.town?.name ?: return true
        // Fall back: search submissions for any with this territory
        val townName = submissions.values
            .firstOrNull { it.territoryId?.toInt() == territoryId.toInt() }
            ?.townName ?: return true
        return canSubmit(townName)
    }

    fun getRemainingCooldown(townName: String): Long {
        val lastSubmit = townLastSubmission[townName] ?: return 0L
        val cooldownMillis = TimeUnit.HOURS.toMillis(MiningTowerConfig.submissionCooldownHours.toLong())
        return maxOf(0L, cooldownMillis - (System.currentTimeMillis() - lastSubmit))
    }

    fun formatRemainingCooldown(townName: String): String {
        val remaining = getRemainingCooldown(townName)
        val hours = TimeUnit.MILLISECONDS.toHours(remaining)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /** Overload for callers that have a TerritoryId. */
    fun formatRemainingCooldown(territoryId: TerritoryId): String {
        val townName = submissions.values
            .firstOrNull { it.territoryId?.toInt() == territoryId.toInt() }
            ?.townName ?: return "0m"
        return formatRemainingCooldown(townName)
    }

    // -------------------------------------------------------------------------
    // Submission lifecycle
    // -------------------------------------------------------------------------

    fun submitTower(
        townName: String,
        submitterUuid: UUID,
        submitterName: String,
        location: Location,
    ): MiningTowerSubmission {
        val territory = Nodes.getTerritoryFromBlock(location.blockX, location.blockZ)
        val territoryId = territory?.id

        val submission = MiningTowerSubmission(
            id = UUID.randomUUID(),
            townName = townName,
            submitterUuid = submitterUuid,
            submitterName = submitterName,
            location = location,
            territoryId = territoryId,
            submittedAt = System.currentTimeMillis(),
        )

        submissions[submission.id] = submission
        townLastSubmission[townName] = submission.submittedAt
        save()

        return submission
    }

    fun getPendingSubmissions(): List<MiningTowerSubmission> = submissions.values.filter { it.status == SubmissionStatus.PENDING }

    fun getSubmission(id: UUID): MiningTowerSubmission? = submissions[id]

    /**
     * Approve a submission.
     * Any previously APPROVED tower for the same town is auto-rejected so there
     * is always exactly one active tower per town.
     */
    fun approveSubmission(id: UUID, tierId: String, raterUuid: UUID) {
        val submission = submissions[id] ?: return

        // Revoke any existing approved tower for this town
        submissions.values
            .filter { it.townName == submission.townName && it.status == SubmissionStatus.APPROVED && it.id != id }
            .forEach { old ->
                old.status = SubmissionStatus.REJECTED
                old.ratedBy = raterUuid
                old.ratedAt = System.currentTimeMillis()
            }

        submission.status = SubmissionStatus.APPROVED
        submission.ratedTier = tierId
        submission.ratedBy = raterUuid
        submission.ratedAt = System.currentTimeMillis()
        save()
    }

    fun rejectSubmission(id: UUID, raterUuid: UUID) {
        val submission = submissions[id] ?: return
        submission.status = SubmissionStatus.REJECTED
        submission.ratedBy = raterUuid
        submission.ratedAt = System.currentTimeMillis()
        save()
    }

    /**
     * Returns the single approved tower for a town (there should only ever be one
     * after approveSubmission enforces the invariant, but maxByOrNull is kept as
     * a safe fallback for data loaded from disk).
     */
    fun getApprovedTower(townName: String): MiningTowerSubmission? = submissions.values
        .filter { it.townName == townName && it.status == SubmissionStatus.APPROVED }
        .maxByOrNull { it.submittedAt }

    fun getIncomeBonus(townName: String): Double {
        val tower = getApprovedTower(townName) ?: return 0.0
        val tier = tower.ratedTier ?: return 0.0
        return MiningTowerConfig.tiers[tier]?.incomeBonus ?: 0.0
    }

    fun deleteTower(id: UUID): Boolean {
        val submission = submissions.remove(id) ?: return false

        // Recalculate cooldown for this town
        val nextRecent = submissions.values
            .filter { it.townName == submission.townName && (it.status == SubmissionStatus.PENDING || it.status == SubmissionStatus.APPROVED) }
            .maxByOrNull { it.submittedAt }

        if (nextRecent != null) {
            townLastSubmission[submission.townName] = nextRecent.submittedAt
        } else {
            townLastSubmission.remove(submission.townName)
        }

        save()
        return true
    }

    fun getAllSubmissions(): List<MiningTowerSubmission> = submissions.values.toList()

    fun getAllApprovedTowns(): List<String> = submissions.values
        .filter { it.status == SubmissionStatus.APPROVED }
        .map { it.townName }
        .distinct()
        .sorted()
}
