package phonon.nodes

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object ConfigVersioning {
    private const val VERSION_PATH = "configVersion"
    private const val DEFAULT_MAIN_PERIODIC_TICK = 1200L

    private val backupTimestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val rallyOverviewLegacyKeys: List<Pair<String, String>> = listOf(
        "rallyOverviewEnabled" to "enabled",
        "rallyOverviewBindAddress" to "bindAddress",
        "rallyOverviewWebPort" to "webPort",
        "rallyOverviewSamplePeriodMs" to "samplePeriodMs",
        "rallyOverviewHistoryDays" to "historyDays",
        "rallyOverviewPageTitle" to "pageTitle",
        "rallyOverviewDefaultLastHours" to "defaultLastHours",
        "rallyOverviewMaxRows" to "maxRows",
        "rallyOverviewAutoRefreshSeconds" to "autoRefreshSeconds",
        "rallyOverviewSortBy" to "sortBy",
        "rallyOverviewResponseCacheMs" to "responseCacheMs",
    )

    private val marketOverviewLegacyKeys: List<Pair<String, String>> = listOf(
        "marketOverviewEnabled" to "enabled",
        "marketOverviewBindAddress" to "bindAddress",
        "marketOverviewWebPort" to "webPort",
        "marketOverviewHistoryDays" to "historyDays",
        "marketOverviewPageTitle" to "pageTitle",
        "marketOverviewDefaultLastHours" to "defaultLastHours",
        "marketOverviewMaxRows" to "maxRows",
        "marketOverviewAutoRefreshSeconds" to "autoRefreshSeconds",
        "marketOverviewSortBy" to "sortBy",
        "marketOverviewResponseCacheMs" to "responseCacheMs",
    )

    fun loadMainConfig(plugin: Plugin, configFile: File): YamlConfiguration {
        if (!configFile.exists()) {
            plugin.saveDefaultConfig()
        }

        val currentConfig = YamlConfiguration.loadConfiguration(configFile)
        val defaultConfig = loadBundledConfig(plugin, "config.yml")
        val currentVersion = bundledConfigVersion(defaultConfig)
        val existingVersion = currentConfig.getInt(VERSION_PATH, 0)

        if (existingVersion < currentVersion) {
            applyMigrations(currentConfig, existingVersion, currentVersion)
        }

        val missingDefaults = hasMissingDefaults(defaultConfig, currentConfig)
        if (existingVersion >= currentVersion && !missingDefaults) {
            return currentConfig
        }

        val mergedConfig = mergeIntoDefaults(defaultConfig, currentConfig)
        mergedConfig.set(VERSION_PATH, currentVersion)

        val updateReasons = buildList {
            if (existingVersion < currentVersion) {
                add("version $existingVersion -> $currentVersion")
            }
            if (missingDefaults) {
                add("missing defaults restored")
            }
        }.joinToString(", ")

        try {
            backupConfig(configFile, existingVersion)
            mergedConfig.save(configFile)
            plugin.logger.info("Updated config.yml automatically ($updateReasons)")
        } catch (err: Exception) {
            plugin.logger.severe("Failed to write updated config.yml: ${err.message}")
        }

        return mergedConfig
    }

    private fun loadBundledConfig(plugin: Plugin, resourcePath: String): YamlConfiguration {
        val input = plugin.getResource(resourcePath)
            ?: error("Missing bundled resource: $resourcePath")

        input.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                return YamlConfiguration.loadConfiguration(reader)
            }
        }
    }

    private fun bundledConfigVersion(defaultConfig: YamlConfiguration): Int {
        val bundledVersion = defaultConfig.getInt(VERSION_PATH, 0)
        require(bundledVersion > 0) { "Bundled config.yml is missing a valid $VERSION_PATH" }
        return bundledVersion
    }

    private fun applyMigrations(config: YamlConfiguration, fromVersion: Int, currentVersion: Int) {
        for (version in (fromVersion + 1)..currentVersion) {
            when (version) {
                1 -> migrateToV1(config)
                2 -> migrateToV2(config)
                3 -> migrateToV3(config)
                4 -> migrateToV4(config)
                5 -> migrateToV5(config)
                6 -> migrateToV6(config)
            }
        }
    }

    private fun migrateToV1(config: YamlConfiguration) {
        migrateLegacyValue(config, "townCooldownUpdateTick", "mainPeriodicTick", DEFAULT_MAIN_PERIODIC_TICK)
        migrateSection(config, "rallyOverwie", "rallyOverview")
        migrateFlatKeysToSection(config, "rallyOverview", rallyOverviewLegacyKeys)
        migrateFlatKeysToSection(config, "marketOverview", marketOverviewLegacyKeys)
        migrateLegacyMiningOutpostText(config)
    }

    private fun migrateToV2(config: YamlConfiguration) {
        // Version bump only. Keep a dedicated migration step so future config changes
        // stay paired with an explicit schema increment.
    }

    private fun migrateToV3(config: YamlConfiguration) {
        // Version bump only. Bundled config.yml is the schema version source of truth.
    }

    private fun migrateToV4(config: YamlConfiguration) {
        repairMalformedMiningOutpostText(config)
    }

    private fun migrateToV5(config: YamlConfiguration) {
        // Version bump only. Adds territory capture notification mode to config.yml.
    }

    private fun migrateToV6(config: YamlConfiguration) {
        config.set("territoryCaptureNotificationMode", null)
    }

    private fun migrateLegacyValue(
        config: YamlConfiguration,
        legacyPath: String,
        currentPath: String,
        currentDefaultValue: Any,
    ) {
        if (!config.contains(legacyPath)) {
            return
        }

        val currentValue = config.get(currentPath)
        val legacyValue = config.get(legacyPath)

        if (currentValue == null || currentValue == currentDefaultValue) {
            config.set(currentPath, legacyValue)
        }

        config.set(legacyPath, null)
    }

    private fun migrateFlatKeysToSection(
        config: YamlConfiguration,
        sectionPath: String,
        mappings: List<Pair<String, String>>,
    ) {
        for ((legacyPath, currentKey) in mappings) {
            if (!config.contains(legacyPath)) {
                continue
            }

            val targetPath = "$sectionPath.$currentKey"
            config.set(targetPath, config.get(legacyPath))
            config.set(legacyPath, null)
        }
    }

    private fun migrateSection(config: YamlConfiguration, oldPath: String, newPath: String) {
        val oldSection = config.getConfigurationSection(oldPath) ?: return

        config.set(newPath, null)
        val targetSection = config.createSection(newPath)
        copySection(oldSection, targetSection)
        config.set(oldPath, null)
    }

    private fun migrateLegacyMiningOutpostText(config: YamlConfiguration) {
        if (!config.isString("miningoutpostText")) {
            return
        }

        val legacyText = config.getString("miningoutpostText")?.trim()
        if (legacyText.isNullOrEmpty()) {
            return
        }

        config.set("miningoutpostText", null)
        config.createSection("miningoutpostText").set("line1", legacyText)
    }

    private fun repairMalformedMiningOutpostText(config: YamlConfiguration) {
        val section = config.getConfigurationSection("miningoutpostText") ?: return
        val line1 = section.getString("line1")?.trim() ?: return

        if (!line1.startsWith("MemorySection[path='miningoutpostText'")) {
            return
        }

        config.set("miningoutpostText", null)
    }

    private fun hasMissingDefaults(defaultSection: ConfigurationSection, currentSection: ConfigurationSection): Boolean {
        for (key in defaultSection.getKeys(false)) {
            val defaultChild = defaultSection.getConfigurationSection(key)
            if (defaultChild != null) {
                val currentChild = currentSection.getConfigurationSection(key) ?: return true
                if (hasMissingDefaults(defaultChild, currentChild)) {
                    return true
                }
            } else if (!currentSection.contains(key)) {
                return true
            }
        }

        return false
    }

    private fun mergeIntoDefaults(defaultConfig: YamlConfiguration, currentConfig: YamlConfiguration): YamlConfiguration {
        copySection(currentConfig, defaultConfig)
        return defaultConfig
    }

    private fun copySection(source: ConfigurationSection, target: ConfigurationSection) {
        for (key in source.getKeys(false)) {
            val sourceChild = source.getConfigurationSection(key)
            if (sourceChild != null) {
                val targetChild = target.getConfigurationSection(key) ?: run {
                    target.set(key, null)
                    target.createSection(key)
                }
                copySection(sourceChild, targetChild)
            } else {
                target.set(key, source.get(key))
            }
        }
    }

    private fun backupConfig(configFile: File, existingVersion: Int) {
        if (!configFile.exists()) {
            return
        }

        val backupDir = File(configFile.parentFile, "config-backups")
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }

        val timestamp = LocalDateTime.now().format(backupTimestampFormat)
        val backupName = "config.v$existingVersion.$timestamp.yml"
        Files.copy(
            configFile.toPath(),
            File(backupDir, backupName).toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
