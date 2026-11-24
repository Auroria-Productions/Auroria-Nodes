package phonon.nodes.language

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

public enum class PlayerLanguage(
    val code: String,
) {
    EN("en"),
    DE("de"),
    EL("el"), // requested as Spanish in command spec
    TR("tr"),
    ;

    public companion object {
        public fun fromCode(raw: String?): PlayerLanguage? {
            if (raw == null) return null
            val normalized = raw.lowercase()
            return entries.firstOrNull { it.code == normalized }
        }
    }
}

public object Language {
    private const val YAML_DOT_ESCAPE = "﹒"
    private val COLOR_CODE_REGEX = Regex("(?i)\\u00A7[0-9A-FK-OR]")
    private val PREFIX_COLOR_REGEX = Regex("^(?i)(?:\\u00A7[0-9A-FK-OR])+")
    private const val MISSING_FLUSH_THRESHOLD = 128
    private const val MISSING_FLUSH_INTERVAL_MS = 15_000L

    private fun encodeYamlKey(raw: String): String = raw.replace(".", YAML_DOT_ESCAPE)

    private fun decodeYamlKey(encoded: String): String = encoded.replace(YAML_DOT_ESCAPE, ".")

    private val defaultTranslations: Map<PlayerLanguage, Map<String, String>> = mapOf(
        PlayerLanguage.DE to mapOf(
            "You moved, teleport cancelled" to "Du hast dich bewegt, Teleport abgebrochen",
            "Only players in game can use" to "Nur Spieler im Spiel koennen das benutzen",
            "Only players can use this command" to "Nur Spieler koennen diesen Befehl nutzen",
            "Invalid command, use /town help" to "Ungueltiger Befehl, nutze /town help",
            "Invalid command, use \"/nodesadmin help\"" to "Ungueltiger Befehl, nutze /nodesadmin help",
        ),
        PlayerLanguage.EL to mapOf(
            "You moved, teleport cancelled" to "Te moviste, teletransporte cancelado",
            "Only players in game can use" to "Solo los jugadores en el juego pueden usar esto",
            "Only players can use this command" to "Solo los jugadores pueden usar este comando",
            "Invalid command, use /town help" to "Comando invalido, usa /town help",
            "Invalid command, use \"/nodesadmin help\"" to "Comando invalido, usa /nodesadmin help",
        ),
        PlayerLanguage.TR to mapOf(
            "You moved, teleport cancelled" to "Hareket ettin, isinlanma iptal edildi",
            "Only players in game can use" to "Bunu sadece oyundaki oyuncular kullanabilir",
            "Only players can use this command" to "Bu komutu sadece oyuncular kullanabilir",
            "Invalid command, use /town help" to "Gecersiz komut, /town help kullan",
            "Invalid command, use \"/nodesadmin help\"" to "Gecersiz komut, /nodesadmin help kullan",
        ),
    )

    private val translations: MutableMap<PlayerLanguage, MutableMap<String, String>> = mutableMapOf()
    private val languageFiles: MutableMap<PlayerLanguage, File> = mutableMapOf()
    private val pendingMissingKeys: MutableMap<PlayerLanguage, MutableSet<String>> = mutableMapOf()
    private var lastFlushMs: Long = 0L

    public fun load(pathPlugin: String) {
        flushPending(force = true)
        translations.clear()
        languageFiles.clear()
        pendingMissingKeys.clear()

        for (lang in PlayerLanguage.entries) {
            if (lang == PlayerLanguage.EN) continue

            val languageDir = File(pathPlugin, "language")
            val file = File(languageDir, "lang_${lang.code}.yml")
            val legacyFile = File(pathPlugin, "lang_${lang.code}.yml")
            if (!file.exists() && legacyFile.exists()) {
                languageDir.mkdirs()
                runCatching {
                    legacyFile.copyTo(file, overwrite = true)
                    legacyFile.delete()
                }
            }
            languageFiles[lang] = file
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                val yaml = YamlConfiguration()
                yaml.set("meta.language", lang.code)
                yaml.set("translations", linkedMapOf<String, String>())
                for ((key, value) in (defaultTranslations[lang] ?: emptyMap())) {
                    yaml.set("translations.${encodeYamlKey(key)}", value)
                }
                yaml.save(file)
            }

            val yaml = YamlConfiguration.loadConfiguration(file)
            val section = yaml.getConfigurationSection("translations")
            val map = mutableMapOf<String, String>()
            if (section != null) {
                for (key in section.getKeys(false)) {
                    val rawKey = decodeYamlKey(key)
                    map[rawKey] = section.getString(key, rawKey) ?: rawKey
                }
            }

            for ((key, value) in (defaultTranslations[lang] ?: emptyMap())) {
                if (!map.containsKey(key)) {
                    map[key] = value
                    yaml.set("translations.${encodeYamlKey(key)}", value)
                }
            }

            yaml.save(file)
            translations[lang] = map
            pendingMissingKeys[lang] = hashSetOf()
        }

        lastFlushMs = System.currentTimeMillis()
    }

    public fun translateRaw(text: String, lang: PlayerLanguage?): String {
        if (lang == null || lang == PlayerLanguage.EN) {
            return text
        }

        translations[lang]?.get(text)?.let { return it }

        val plain = text.replace(COLOR_CODE_REGEX, "").trim()
        val prefix = PREFIX_COLOR_REGEX.find(text)?.value ?: ""
        translations[lang]?.get(plain)?.let { return "$prefix$it" }

        if (plain.isBlank()) {
            return text
        }

        val langMap = translations[lang]
        if (langMap != null && !langMap.containsKey(plain)) {
            langMap[plain] = plain
            pendingMissingKeys.getOrPut(lang) { hashSetOf() }.add(plain)
            flushPending(force = false)
        }

        return text
    }

    public fun shutdown() {
        flushPending(force = true)
    }

    private fun flushPending(force: Boolean) {
        val now = System.currentTimeMillis()
        val pendingCount = pendingMissingKeys.values.sumOf { it.size }
        if (!force) {
            if (pendingCount < MISSING_FLUSH_THRESHOLD && (now - lastFlushMs) < MISSING_FLUSH_INTERVAL_MS) {
                return
            }
        }

        for ((lang, pending) in pendingMissingKeys) {
            if (pending.isEmpty()) continue
            val file = languageFiles[lang] ?: continue
            val yaml = YamlConfiguration.loadConfiguration(file)
            var changed = false
            for (key in pending) {
                val safeKey = encodeYamlKey(key)
                val path = "translations.$safeKey"
                if (!yaml.contains(path)) {
                    yaml.set(path, key)
                    changed = true
                }
            }
            if (changed) {
                yaml.save(file)
            }
            pending.clear()
        }

        lastFlushMs = now
    }
}
