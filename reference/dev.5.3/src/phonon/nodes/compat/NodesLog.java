package phonon.nodes.compat;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

/** Adapts the old core's println calls without writing to the server's process streams. */
public final class NodesLog {
    public static final PrintStream OUT = new LoggerPrintStream(Level.INFO);
    public static final PrintStream ERR = new LoggerPrintStream(Level.WARNING);

    private NodesLog() {}

    private static Logger logger() {
        try {
            return JavaPlugin.getProvidingPlugin(NodesLog.class).getLogger();
        } catch (IllegalArgumentException | IllegalStateException e) {
            // The plugin loader can call initialization code before it installs the JavaPlugin instance.
            return Logger.getLogger("nodes");
        }
    }

    public static void exception(Throwable error) {
        logger().log(Level.WARNING, "Nodes operation failed: " + error, error);
    }

    private static final class LoggerPrintStream extends PrintStream {
        private final Level level;

        LoggerPrintStream(Level level) {
            super(OutputStream.nullOutputStream());
            this.level = level;
        }

        @Override
        public void println(String text) {
            logger().log(level, String.valueOf(text));
        }

        @Override
        public void println(Object value) {
            println(String.valueOf(value));
        }
    }
}
