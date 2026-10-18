package pl.anarchiabot.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import pl.anarchiabot.bridge.BotProcess;
import pl.anarchiabot.config.BotConfig;
import pl.anarchiabot.config.ConfigManager;
import pl.anarchiabot.core.LogBuffer.Kind;

import java.io.IOException;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Logika sterowania botem. Nie zna GUI ani Minecrafta – rozmawia z procesem bota przez {@link BotProcess}.
 * Status CONNECTED ustawiany jest wyłącznie na podstawie zdarzeń od bota.
 */
public final class BotController implements BotProcess.Listener {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final BotConfig config;
    private final ConfigManager configManager;
    private final LogBuffer log = new LogBuffer();
    private final BotProcess process = new BotProcess(this);

    private volatile BotStatus status = BotStatus.INACTIVE;
    private volatile BotMode mode = BotMode.IDLE;
    private volatile boolean ready;
    private volatile double[] position;
    private volatile double[] home;
    private volatile String followTarget = "";
    private volatile String ownerName = "";
    private long lastMissingWarn;
    private int followTicks;

    public BotController(BotConfig config, ConfigManager configManager) {
        this.config = config;
        this.configManager = configManager;
    }

    // ------------------------------------------------------------ odczyt stanu

    public BotConfig config() { return config; }
    public LogBuffer log() { return log; }
    public BotStatus status() { return status; }
    public BotMode mode() { return mode; }
    public boolean isReady() { return ready; }
    public double[] position() { return position; }
    public double[] home() { return home; }
    public void setOwnerName(String name) { this.ownerName = name == null ? "" : name; }
    public String ownerName() { return ownerName; }

    // ------------------------------------------------------------ cykl życia bota

    public synchronized void createBot(String name) {
        name = name == null ? "" : name.trim();
        if (!NAME.matcher(name).matches()) {
            log.add(Kind.WARN, "Nazwa bota: 3–16 znaków (litery, cyfry, _).");
            return;
        }
        config.botName = name;
        if (process.isAlive()) {
            log.add(Kind.INFO, "Bot jest już utworzony.");
            return;
        }
        try {
            process.start();
            log.add(Kind.INFO, "Uruchamiam proces bota \"" + name + "\"…");
        } catch (IOException e) {
            log.add(Kind.ERROR, "Nie udało się uruchomić procesu bota: " + e.getMessage());
        }
    }

    public synchronized void removeBot() {
        if (!process.isAlive()) {
            log.add(Kind.INFO, "Brak bota do usunięcia.");
            return;
        }
        process.stop();
        ready = false;
        status = BotStatus.INACTIVE;
        mode = BotMode.IDLE;
        position = null;
        home = null;
        log.add(Kind.INFO, "Bot usunięty (proces zatrzymany).");
    }

    public void connect() {
        if (!ready) {
            log.add(Kind.WARN, "Najpierw utwórz bota.");
            return;
        }
        if (status == BotStatus.CONNECTING || status == BotStatus.CONNECTED) {
            log.add(Kind.INFO, "Bot już jest połączony lub się łączy.");
            return;
        }
        String s = config.server.trim();
        String host = s;
        int port = 25565;
        int i = s.lastIndexOf(':');
        if (i > 0 && s.indexOf(':') == i) {
            try {
                port = Integer.parseInt(s.substring(i + 1).trim());
                host = s.substring(0, i).trim();
            } catch (NumberFormatException e) {
                log.add(Kind.ERROR, "Nieprawidłowy port w adresie serwera: " + s);
                return;
            }
        }
        if (host.isEmpty()) {
            log.add(Kind.ERROR, "Podaj adres serwera.");
            return;
        }
        JsonObject c = cmd("connect");
        c.addProperty("host", host);
        c.addProperty("port", port);
        c.addProperty("name", config.botName);
        c.addProperty("delay", config.postLoginDelayMs);
        JsonArray post = new JsonArray();
        for (String part : config.postLoginCommands.split(";")) if (!part.isBlank()) post.add(part.trim());
        c.add("post", post);
        if (send(c)) {
            status = BotStatus.CONNECTING;
            log.add(Kind.INFO, "Łączenie z " + host + ":" + port + " jako " + config.botName + "…");
        }
    }

    public void disconnect() {
        if (status != BotStatus.CONNECTED && status != BotStatus.CONNECTING) {
            log.add(Kind.INFO, "Bot nie jest połączony.");
            return;
        }
        send(cmd("disconnect"));
    }

    // ------------------------------------------------------------ sterowanie

    public void follow(String player) {
        player = player == null ? "" : player.trim();
        if (player.isEmpty()) {
            log.add(Kind.WARN, "Podaj nick gracza do śledzenia.");
            return;
        }
        followTarget = player;
        config.followPlayer = player;
        JsonObject c = cmd("follow");
        c.addProperty("player", player);
        if (send(c)) log.add(Kind.INFO, "Podążanie za " + player + " (gracz musi być w zasięgu renderowania Twojego klienta).");
    }

    public void stop() {
        send(cmd("stop"));
    }

    public void goTo(double x, double y, double z) {
        JsonObject c = cmd("goto");
        c.addProperty("x", x);
        c.addProperty("y", y);
        c.addProperty("z", z);
        config.targetX = x;
        config.targetY = y;
        config.targetZ = z;
        send(c);
    }

    public void returnHome() {
        send(cmd("home"));
    }

    /** Wysyła wiadomość/komendę (zaczynającą się od /) jako bot. */
    public void sendBotChat(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        JsonObject c = cmd("chat");
        c.addProperty("text", t);
        if (send(c)) {
            String low = t.toLowerCase(Locale.ROOT);
            boolean secret = low.startsWith("/login") || low.startsWith("/l ") || low.startsWith("/register") || low.startsWith("/reg ");
            log.add(Kind.SENT, "» " + (secret ? t.split(" ")[0] + " ***" : t));
        }
    }

    /** Bot wysyła do Ciebie prośbę o teleportację (domyślnie "/tpa <twój nick>"). */
    public void tpaToMe() {
        if (ownerName.isEmpty()) {
            log.add(Kind.ERROR, "Nieznany nick gracza.");
            return;
        }
        String c = config.tpaCommand.replace("{player}", ownerName).trim();
        sendBotChat(c.startsWith("/") ? c : "/" + c);
    }

    // ------------------------------------------------------------ konfiguracja

    public void saveConfig(boolean announce) {
        try {
            configManager.save(config);
            if (announce) log.add(Kind.INFO, "Konfiguracja zapisana: " + configManager.path().getFileName());
        } catch (IOException e) {
            log.add(Kind.ERROR, "Nie udało się zapisać konfiguracji: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ tick klienta

    /** Wołane co tick klienta. Przekazuje botowi pozycję śledzonego gracza (widzi go tylko Twój klient). */
    public void followTick(Function<String, double[]> locator) {
        if (mode != BotMode.FOLLOW || status != BotStatus.CONNECTED) return;
        if (++followTicks % 5 != 0) return;
        double[] p = locator.apply(followTarget);
        if (p == null) {
            long now = System.currentTimeMillis();
            if (now - lastMissingWarn > 10_000) {
                lastMissingWarn = now;
                log.add(Kind.WARN, "Nie widzę gracza " + followTarget + " w Twoim zasięgu – bot czeka.");
            }
            return;
        }
        JsonObject c = cmd("target");
        c.addProperty("x", p[0]);
        c.addProperty("y", p[1]);
        c.addProperty("z", p[2]);
        process.send(c);
    }

    public void shutdown() {
        saveConfig(false);
        process.stop();
    }

    // ------------------------------------------------------------ zdarzenia od bota

    @Override
    public void onEvent(JsonObject ev) {
        String type = str(ev, "ev");
        switch (type) {
            case "ready" -> {
                ready = true;
                log.add(Kind.INFO, "Bot gotowy. Możesz się połączyć.");
            }
            case "status" -> {
                String reason = str(ev, "reason");
                switch (str(ev, "state")) {
                    case "CONNECTING" -> status = BotStatus.CONNECTING;
                    case "CONNECTED" -> {
                        status = BotStatus.CONNECTED;
                        log.add(Kind.INFO, "Bot połączony z serwerem (otrzymano logowanie do gry).");
                    }
                    case "DISCONNECTED" -> {
                        status = BotStatus.DISCONNECTED;
                        mode = BotMode.IDLE;
                        position = null;
                        log.add(Kind.WARN, "Bot rozłączony" + (reason.isEmpty() ? "." : ": " + reason));
                    }
                    default -> { }
                }
            }
            case "mode" -> mode = BotMode.parse(str(ev, "mode"));
            case "pos" -> position = new double[]{num(ev, "x"), num(ev, "y"), num(ev, "z")};
            case "home" -> home = new double[]{num(ev, "x"), num(ev, "y"), num(ev, "z")};
            case "log" -> {
                Kind k = switch (str(ev, "level")) {
                    case "error" -> Kind.ERROR;
                    case "warn" -> Kind.WARN;
                    default -> Kind.INFO;
                };
                log.add(k, str(ev, "text"));
            }
            case "chat" -> onChat(str(ev, "text"));
            default -> { }
        }
    }

    private void onChat(String text) {
        log.add(Kind.CHAT, text);
        if (!config.autoAcceptTpa || status != BotStatus.CONNECTED || ownerName.isEmpty()) return;
        try {
            if (text.toLowerCase(Locale.ROOT).contains(ownerName.toLowerCase(Locale.ROOT))
                    && Pattern.compile(config.autoAcceptRegex).matcher(text).find()) {
                sendBotChat("/" + config.acceptCommand);
            }
        } catch (Exception e) {
            log.add(Kind.ERROR, "Błędne wyrażenie autoAcceptRegex w konfiguracji: " + e.getMessage());
        }
    }

    @Override
    public void onStderr(String line) {
        if (!line.isBlank()) log.add(Kind.ERROR, "[bot] " + line);
    }

    @Override
    public void onExit(int code) {
        BotStatus before = status;
        ready = false;
        mode = BotMode.IDLE;
        position = null;
        status = (before == BotStatus.CONNECTED || before == BotStatus.CONNECTING) ? BotStatus.DISCONNECTED : BotStatus.INACTIVE;
        log.add(code == 0 ? Kind.INFO : Kind.ERROR, "Proces bota zakończony (kod " + code + ").");
    }

    // ------------------------------------------------------------ pomocnicze

    private boolean send(JsonObject c) {
        if (!ready || !process.send(c)) {
            log.add(Kind.ERROR, "Nie można wysłać polecenia – bot nie jest uruchomiony. Użyj „Utwórz bota”.");
            return false;
        }
        return true;
    }

    private static JsonObject cmd(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", name);
        return o;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsDouble() : 0;
    }
}
