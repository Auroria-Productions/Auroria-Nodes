package phonon.nodes.utils

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort compatibility with SuperVanish / PremiumVanish.
 *
 * Uses reflection so Nodes can compile/run without the dependency present.
 */
object VanishCompat {
    private val initialized = AtomicBoolean(false)

    private var canSeePlayerPlayer: Method? = null
    private var canSeePlayerUuid: Method? = null
    private var isInvisiblePlayer: Method? = null
    private var isInvisibleUuid: Method? = null

    private fun initIfNeeded() {
        if (initialized.getAndSet(true)) return
        try {
            val clazz = try {
                Class.forName("de.myzelyam.api.vanish.VanishAPI")
            } catch (_: Throwable) {
                val plugin = Bukkit.getPluginManager().getPlugin("SuperVanish")
                    ?: Bukkit.getPluginManager().getPlugin("PremiumVanish")
                    ?: return
                Class.forName("de.myzelyam.api.vanish.VanishAPI", false, plugin.javaClass.classLoader)
            }
            clazz.methods.forEach { method ->
                when (method.name) {
                    "canSee" -> {
                        if (method.parameterTypes.contentEquals(arrayOf(Player::class.java, Player::class.java))) {
                            canSeePlayerPlayer = method
                        } else if (method.parameterTypes.contentEquals(arrayOf(Player::class.java, UUID::class.java))) {
                            canSeePlayerUuid = method
                        }
                    }

                    "isInvisible" -> {
                        if (method.parameterTypes.contentEquals(arrayOf(Player::class.java))) {
                            isInvisiblePlayer = method
                        } else if (method.parameterTypes.contentEquals(arrayOf(UUID::class.java))) {
                            isInvisibleUuid = method
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Not installed or API not available.
        }
    }

    /**
     * Returns true if [viewer] should be able to see [target] in tab-related lists.
     *
     * Preference order:
     * 1) SuperVanish/PremiumVanish VanishAPI.canSee (if available)
     * 2) VanishAPI.isInvisible + Bukkit visibility
     * 3) Bukkit visibility (Player#canSee)
     */
    fun canSee(viewer: Player, target: Player): Boolean {
        if (viewer.uniqueId == target.uniqueId) return true
        initIfNeeded()

        try {
            canSeePlayerPlayer?.let { method ->
                return (method.invoke(null, viewer, target) as? Boolean) ?: viewer.canSee(target)
            }
            canSeePlayerUuid?.let { method ->
                return (method.invoke(null, viewer, target.uniqueId) as? Boolean) ?: viewer.canSee(target)
            }
        } catch (_: Throwable) {
            // fall through
        }

        val invisible = try {
            when {
                isInvisiblePlayer != null -> (isInvisiblePlayer!!.invoke(null, target) as? Boolean)
                isInvisibleUuid != null -> (isInvisibleUuid!!.invoke(null, target.uniqueId) as? Boolean)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }

        if (invisible == true) {
            // If the vanish plugin properly hides players, this will be false for regular viewers and
            // true for those allowed to see vanished players.
            return viewer.canSee(target)
        }

        return viewer.canSee(target)
    }

    fun isVanished(target: Player): Boolean {
        initIfNeeded()

        val invisible = try {
            when {
                isInvisiblePlayer != null -> (isInvisiblePlayer!!.invoke(null, target) as? Boolean)
                isInvisibleUuid != null -> (isInvisibleUuid!!.invoke(null, target.uniqueId) as? Boolean)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }

        return invisible == true
    }
}
