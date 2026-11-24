package phonon.nodes.utils

import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

public object ItemStackCodec {
    public fun encode(item: ItemStack): String {
        val output = ByteArrayOutputStream()
        BukkitObjectOutputStream(output).use { stream ->
            stream.writeObject(item)
        }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    public fun decode(encoded: String): ItemStack? = try {
        val bytes = Base64.getDecoder().decode(encoded)
        BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { stream ->
            stream.readObject() as? ItemStack
        }
    } catch (_: Exception) {
        null
    }
}
