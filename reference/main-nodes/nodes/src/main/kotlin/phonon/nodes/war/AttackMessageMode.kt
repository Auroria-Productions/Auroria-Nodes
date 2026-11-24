package phonon.nodes.war

public enum class AttackMessageMode(val label: String) {
    ALL("All war activity"),
    TOWN("Your town only"),
    NATION("Your nation"),
    ALLY("Your town, nation, and allies"),
    WARZONE_ONLY("Warzones only"),
    TERRITORY_CAPTURES("Territory annexes and captures"),
    ;

    public companion object {
        public fun fromName(value: String?): AttackMessageMode = when (value?.trim()?.lowercase()) {
            "all", "global", "everyone" -> ALL
            "town", "townonly", "town-only", "local" -> TOWN
            "nation", "n" -> NATION
            "ally", "allies", "a" -> ALLY
            "warzone", "warzones", "warzoneonly", "warzone-only" -> WARZONE_ONLY
            "captures", "capture", "territory", "territories", "annex", "annexes" -> TERRITORY_CAPTURES
            else -> ALL
        }

        public fun fromLegacyTownOnly(enabled: Boolean): AttackMessageMode = if (enabled) TOWN else ALL
    }
}
