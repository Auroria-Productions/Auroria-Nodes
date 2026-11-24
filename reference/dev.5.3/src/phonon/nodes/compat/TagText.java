package phonon.nodes.compat;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.ChatColor;

public interface TagText {
    static TagText empty() {
        return literal("");
    }

    static TagText literal(String text) {
        return new StyledTagText(text, ChatColor.RESET);
    }

    String getString();
    TagText withStyle(ChatColor style);
    Component adventure();

    record StyledTagText(String getString, ChatColor style) implements TagText {
        @Override
        public TagText withStyle(ChatColor color) {
            return new StyledTagText(getString, color);
        }

        @Override
        public Component adventure() {
            var text = Component.text(getString);
            if (style.isColor()) {
                return text.color(NamedTextColor.NAMES.value(style.name().toLowerCase(Locale.ROOT)));
            }
            return switch (style) {
                case BOLD -> text.decorate(TextDecoration.BOLD);
                case ITALIC -> text.decorate(TextDecoration.ITALIC);
                case UNDERLINE -> text.decorate(TextDecoration.UNDERLINED);
                case STRIKETHROUGH -> text.decorate(TextDecoration.STRIKETHROUGH);
                case MAGIC -> text.decorate(TextDecoration.OBFUSCATED);
                default -> text;
            };
        }
    }
}
