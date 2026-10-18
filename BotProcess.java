package pl.anarchiabot.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Komunikacja z botem: uruchamia osobny proces JVM (osadzony bot-runner) i wymienia z nim
 * linie JSON przez stdin/stdout. Dzięki temu biblioteka protokołu nie koliduje z klasami Minecrafta.
 */
public final class BotProcess {
    public interface Listener {
        void onEvent(JsonObject event);

        void onStderr(String line);

        void onExit(int code);
    }

    private final Listener listener;
    private volatile Process process;
    private BufferedWriter writer;

    public BotProcess(Listener listener) {
        this.listener = listener;
    }

    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public synchronized void start() throws IOException {
        if (isAlive()) return;
        Path jar = extractRunner();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-Xmx192m", "-Dfile.encoding=UTF-8", "-jar", jar.toString());
        pb.directory(jar.getParent().toFile());
        Process p = pb.start();
        process = p;
        writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));

        daemon("abm-stdout", () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        listener.onEvent(JsonParser.parseString(line).getAsJsonObject());
                    } catch (Exception e) {
                        listener.onStderr("[stdout] " + line);
                    }
                }
            } catch (IOException ignored) {
                // proces zakończony
            }
        });
        daemon("abm-stderr", () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) listener.onStderr(line);
            } catch (IOException ignored) {
                // proces zakończony
            }
        });
        daemon("abm-waiter", () -> {
            try {
                int code = p.waitFor();
                if (process == p) {
                    process = null;
                    listener.onExit(code);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public synchronized boolean send(JsonObject command) {
        if (!isAlive() || writer == null) return false;
        try {
            writer.write(command.toString());
            writer.newLine();
            writer.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void stop() {
        Process p = process;
        if (p == null) return;
        JsonObject c = new JsonObject();
        c.addProperty("cmd", "shutdown");
        send(c);
        daemon("abm-killer", () -> {
            try {
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) p.destroyForcibly();
            } catch (InterruptedException ignored) {
                p.destroyForcibly();
            }
        });
    }

    private static Path extractRunner() throws IOException {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("anarchia_bot_manager");
        Files.createDirectories(dir);
        Path jar = dir.resolve("anarchia-bot-runner.jar");
        try (InputStream in = BotProcess.class.getResourceAsStream("/bot/anarchia-bot-runner.jar")) {
            if (in == null) throw new IOException("Brak osadzonego pliku bota w modzie (/bot/anarchia-bot-runner.jar)");
            byte[] data = in.readAllBytes();
            if (!Files.exists(jar) || !Arrays.equals(Files.readAllBytes(jar), data)) Files.write(jar, data);
        }
        return jar;
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
