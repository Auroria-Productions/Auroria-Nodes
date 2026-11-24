package phonon.nodes.objects

import org.bukkit.Location
import java.util.UUID

public data class OutpostApplication(
    val id: Int,
    val requesterUuid: UUID,
    val requesterName: String,
    val townName: String,
    val requestedOutpostName: String,
    val territoryId: TerritoryId,
    val applyLocation: Location,
)
