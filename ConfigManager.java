package pl.anarchiabot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("anarchia_bot_manager.json");

    public Path path() {
        return file;
    }

    public BotConfig load() {
        if (Files.exists(file)) {
            try {
                BotConfig cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BotConfig.class);
                if (cfg != null) return cfg;
            } catch (Exception ignored) {
                // uszkodzony plik – użyj domyślnych
            }
        }
        return new BotConfig();
    }

    public void save(BotConfig cfg) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
    }
}
