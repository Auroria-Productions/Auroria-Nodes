package phonon.nodes.objects

import org.bukkit.Location
import java.util.UUID

public data class PortApplication(
    val id: Int,
    val requesterUuid: UUID,
    val requesterName: String,
    val requestedPortName: String,
    val applyLocation: Location,
)
