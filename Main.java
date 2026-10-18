package pl.anarchiabot.runner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Proces bota. Protokol z modem: jedna linia JSON na komendę (stdin) i jedna na zdarzenie (stdout).
 * Gdy mod się zamknie (EOF na stdin), bot kończy działanie.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err); // nic poza protokołem nie może trafić na stdout

        Emitter emitter = new Emitter(protocolOut);
        BotClient bot = new BotClient(emitter);
        emitter.event("ready");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                JsonObject cmd = JsonParser.parseString(line).getAsJsonObject();
                if (!handle(bot, cmd)) break;
            } catch (Exception e) {
                emitter.log("error", "Nieprawidłowe polecenie: " + e);
            }
        }
        bot.shutdown();
        System.exit(0);
    }

    private static boolean handle(BotClient bot, JsonObject c) {
        switch (c.get("cmd").getAsString()) {
            case "connect" -> {
                List<String> post = new ArrayList<>();
                JsonArray arr = c.has("post") ? c.getAsJsonArray("post") : new JsonArray();
                for (JsonElement e : arr) post.add(e.getAsString());
                bot.connect(c.get("host").getAsString(), c.get("port").getAsInt(), c.get("name").getAsString(),
                        post, c.has("delay") ? c.get("delay").getAsLong() : 3000L);
            }
            case "disconnect" -> bot.disconnect();
            case "chat" -> bot.chat(c.get("text").getAsString());
            case "follow" -> bot.follow(c.get("player").getAsString());
            case "target" -> bot.target(c.get("x").getAsDouble(), c.get("y").getAsDouble(), c.get("z").getAsDouble());
            case "goto" -> bot.goTo(c.get("x").getAsDouble(), c.get("y").getAsDouble(), c.get("z").getAsDouble());
            case "home" -> bot.goHome();
            case "stop" -> bot.stopMoving();
            case "shutdown" -> {
                return false;
            }
            default -> { }
        }
        return true;
    }
}
