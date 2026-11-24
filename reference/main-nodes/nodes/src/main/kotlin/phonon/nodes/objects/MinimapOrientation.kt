package phonon.nodes.objects

public enum class MinimapOrientation(val label: String) {
    NORTH_UP("North is up"),
    FORWARD_UP("Facing direction is up"),
    ;

    public companion object {
        public fun fromName(value: String?): MinimapOrientation {
            if (value == null) {
                return NORTH_UP
            }

            return try {
                valueOf(value.uppercase())
            } catch (_: IllegalArgumentException) {
                NORTH_UP
            }
        }
    }
}
