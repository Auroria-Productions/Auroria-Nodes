package phonon.nodes.miningtower

import org.bukkit.Location
import phonon.nodes.objects.TerritoryId
import java.util.UUID

/**
 * Represents a mining tower submission.
 *
 * Changed from data class to plain class: mutable fields (status, ratedTier, etc.)
 * on a data class break equals/hashCode contracts and are dangerous if instances
 * are ever used as collection keys.
 */

class MiningTowerSubmission(
    val id: UUID,
    val townName: String,
    val submitterUuid: UUID,
    val submitterName: String,
    val location: Location,
    val territoryId: TerritoryId?,
    val submittedAt: Long,
    var status: SubmissionStatus = SubmissionStatus.PENDING,
    var ratedTier: String? = null,
    var ratedBy: UUID? = null,
    var ratedAt: Long? = null,
)

enum class SubmissionStatus {
    PENDING,
    APPROVED,
    REJECTED,
}
