package pl.anarchiabot.runner;

import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

import java.time.Instant;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Prawdziwy klient Minecraft (protokol 1.21.5) uruchamiany jako osobny proces.
 * Status CONNECTED jest raportowany dopiero po otrzymaniu pakietu logowania do gry.
 */
final class BotClient {
    enum Mode { IDLE, FOLLOW, GOTO, HOME }

    private static final double WALK_STEP = 0.21;

    private final Emitter out;
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "bot-worker");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private volatile Session session;

    private boolean joined;
    private boolean havePos;
    private double x, y, z;
    private Double homeX, homeY, homeZ;
    private Mode mode = Mode.IDLE;
    private double gx, gy, gz;
    private double tx, ty, tz;
    private long targetStamp;
    private long tickCounter;

    BotClient(Emitter out) {
        this.out = out;
        exec.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Throwable t) {
                out.log("error", "Błąd pętli ruchu: " + t);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- połączenie

    void connect(String host, int port, String name, List<String> postLogin, long delayMs) {
        Session old = session;
        if (old != null && old.isConnected()) {
            out.log("warn", "Bot jest już połączony.");
            return;
        }
        synchronized (lock) {
            joined = false;
            havePos = false;
            homeX = homeY = homeZ = null;
            mode = Mode.IDLE;
        }
        out.status("CONNECTING", null);
        exec.execute(() -> {
            try {
                Session s = new TcpClientSession(host, port, new MinecraftProtocol(name));
                s.addListener(new Listener(s, postLogin, delayMs));
                session = s;
                s.connect();
            } catch (Throwable t) {
                out.status("DISCONNECTED", "Błąd połączenia: " + t);
            }
        });
    }

    void disconnect() {
        Session s = session;
        if (s == null || !s.isConnected()) {
            out.log("warn", "Bot nie jest połączony.");
            return;
        }
        s.disconnect(Component.text("Rozłączono przez użytkownika"));
    }

    void shutdown() {
        try {
            Session s = session;
            if (s != null && s.isConnected()) s.disconnect(Component.text("Zamykanie"));
        } catch (Throwable ignored) {
            // zamykamy proces
        }
        exec.shutdownNow();
    }

    // ---------------------------------------------------------------- czat

    void chat(String text) {
        Session s = session;
        boolean ok;
        synchronized (lock) {
            ok = joined;
        }
        if (s == null || !s.isConnected() || !ok) {
            out.log("warn", "Nie można wysłać wiadomości: bot nie jest połączony.");
            return;
        }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        try {
            if (t.startsWith("/")) {
                s.send(PacketFactory.create("ServerboundChatCommandPacket", new Object[]{t.substring(1)}));
            } else {
                s.send(PacketFactory.create("ServerboundChatPacket",
                        new Object[]{t, Instant.now(), 0L, null, 0, new BitSet(), (byte) 0},
                        new Object[]{t, Instant.now(), 0L, null, 0, new BitSet()}));
            }
        } catch (Throwable e) {
            out.log("error", "Wysyłanie czatu nie powiodło się: " + e);
        }
    }

    // ---------------------------------------------------------------- tryby ruchu

    void follow(String player) {
        synchronized (lock) {
            targetStamp = 0;
            setMode(Mode.FOLLOW);
        }
        out.log("info", "Tryb: podążanie za graczem " + player);
    }

    void target(double nx, double ny, double nz) {
        synchronized (lock) {
            tx = nx;
            ty = ny;
            tz = nz;
            targetStamp = System.currentTimeMillis();
        }
    }

    void goTo(double nx, double ny, double nz) {
        synchronized (lock) {
            gx = nx;
            gy = ny;
            gz = nz;
            setMode(Mode.GOTO);
        }
        out.log("info", String.format("Tryb: idę do %.1f %.1f %.1f", nx, ny, nz));
    }

    void goHome() {
        synchronized (lock) {
            if (homeX == null) {
                out.log("warn", "Pozycja startowa nie jest jeszcze znana (bot nie dostał pozycji od serwera).");
                return;
            }
            setMode(Mode.HOME);
        }
        out.log("info", "Tryb: powrót do pozycji startowej");
    }

    void stopMoving() {
        synchronized (lock) {
            setMode(Mode.IDLE);
        }
        out.log("info", "Zatrzymano wykonywanie poleceń ruchu.");
    }

    private void setMode(Mode m) {
        mode = m;
        out.event("mode", "mode", m.name());
    }

    // ---------------------------------------------------------------- pakiety

    private final class Listener extends SessionAdapter {
        private final Session s;
        private final List<String> post;
        private final long delay;

        Listener(Session s, List<String> post, long delay) {
            this.s = s;
            this.post = post;
            this.delay = delay;
        }

        @Override
        public void packetReceived(Session session, Packet packet) {
            try {
                onPacket(s, packet, post, delay);
            } catch (Throwable t) {
                out.log("error", "Błąd obsługi pakietu " + packet.getClass().getSimpleName() + ": " + t);
            }
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            if (BotClient.this.session != s) return;
            String reason = Emitter.plain(Reflect.call(event, "getReason"));
            Object cause = Reflect.call(event, "getCause");
            synchronized (lock) {
                joined = false;
                havePos = false;
                mode = Mode.IDLE;
            }
            StringBuilder msg = new StringBuilder(reason.isBlank() ? "Połączenie zakończone" : reason);
            if (cause instanceof Throwable t) msg.append(" (").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(')');
            out.event("mode", "mode", Mode.IDLE.name());
            out.status("DISCONNECTED", msg.toString());
        }
    }

    private void onPacket(Session s, Packet packet, List<String> post, long delay) {
        switch (packet.getClass().getSimpleName()) {
            case "ClientboundLoginPacket" -> {
                synchronized (lock) {
                    joined = true;
                }
                out.status("CONNECTED", null);
                long d = delay;
                int total = (int) post.stream().filter(c -> !c.isBlank()).count();
                int n = 0;
                for (String c : post) {
                    String cmd = c.trim();
                    if (cmd.isEmpty()) continue;
                    int idx = ++n;
                    exec.schedule(() -> {
                        out.log("info", "Wysyłam komendę po wejściu (" + idx + "/" + total + ")");
                        chat(cmd);
                    }, d, TimeUnit.MILLISECONDS);
                    d += 1500;
                }
            }
            case "ClientboundPlayerPositionPacket" -> onPosition(s, packet);
            case "ClientboundSystemChatPacket" -> {
                Object overlay = Reflect.call(packet, "isOverlay", "getOverlay", "overlay");
                if (Boolean.TRUE.equals(overlay)) return;
                String text = Emitter.plain(Reflect.call(packet, "getContent", "content"));
                if (!text.isBlank()) out.event("chat", "text", text);
            }
            case "ClientboundPlayerChatPacket" -> {
                Object unsigned = Reflect.call(packet, "getUnsignedContent", "unsignedContent");
                String content = unsigned != null ? Emitter.plain(unsigned) : String.valueOf(Reflect.call(packet, "getContent", "content"));
                String name = Emitter.plain(Reflect.call(packet, "getName", "name"));
                out.event("chat", "text", name.isBlank() ? content : "<" + name + "> " + content);
            }
            case "ClientboundDisguisedChatPacket" -> {
                String content = Emitter.plain(Reflect.call(packet, "getMessage", "message"));
                String name = Emitter.plain(Reflect.call(packet, "getName", "name"));
                out.event("chat", "text", name.isBlank() ? content : "<" + name + "> " + content);
            }
            case "ClientboundPlayerCombatKillPacket" -> {
                out.log("warn", "Bot zginął – wysyłam żądanie odrodzenia.");
                try {
                    s.send(PacketFactory.respawn());
                } catch (Throwable t) {
                    out.log("error", "Nie udało się odrodzić bota: " + t);
                }
            }
            default -> { }
        }
    }

    private void onPosition(Session s, Packet p) {
        Object pos = Reflect.call(p, "getPosition", "position");
        Object src = pos != null ? pos : p;
        double nx = Reflect.num(src, Double.NaN, "getX", "x");
        double ny = Reflect.num(src, Double.NaN, "getY", "y");
        double nz = Reflect.num(src, Double.NaN, "getZ", "z");
        if (Double.isNaN(nx) || Double.isNaN(ny) || Double.isNaN(nz)) {
            out.log("error", "Nie udało się odczytać pozycji z pakietu serwera (zmiana API MCProtocolLib?).");
            return;
        }
        Set<String> rel = new HashSet<>();
        Object r = Reflect.call(p, "getRelatives", "relatives");
        if (r instanceof Collection<?> c) for (Object o : c) rel.add(String.valueOf(o).toUpperCase());
        int id = (int) Reflect.num(p, 0, "getTeleportId", "teleportId", "getId", "id");
        boolean sendHome = false;
        synchronized (lock) {
            x = rel.contains("X") ? x + nx : nx;
            y = rel.contains("Y") ? y + ny : ny;
            z = rel.contains("Z") ? z + nz : nz;
            havePos = true;
            if (homeX == null) {
                homeX = x;
                homeY = y;
                homeZ = z;
                sendHome = true;
            }
        }
        try {
            s.send(PacketFactory.create("ServerboundAcceptTeleportationPacket", new Object[]{id}));
        } catch (Throwable t) {
            out.log("error", "Nie udało się potwierdzić teleportacji: " + t);
        }
        sendPos(s, true);
        if (sendHome) {
            out.event("home", "x", homeX, "y", homeY, "z", homeZ);
            out.log("info", String.format("Pozycja startowa: %.1f %.1f %.1f", homeX, homeY, homeZ));
        }
    }

    private void sendPos(Session s, boolean onGround) {
        try {
            s.send(PacketFactory.create("ServerboundMovePlayerPosPacket",
                    new Object[]{onGround, false, x, y, z},
                    new Object[]{onGround, x, y, z}));
        } catch (Throwable t) {
            out.log("error", "Wysyłanie pozycji nie powiodło się: " + t);
        }
    }

    // ---------------------------------------------------------------- pętla ruchu (co 50 ms)

    private void tick() {
        Session s = session;
        if (s == null || !s.isConnected()) return;
        synchronized (lock) {
            if (!joined || !havePos) return;
            tickCounter++;
            boolean moved = false;
            switch (mode) {
                case FOLLOW -> {
                    if (System.currentTimeMillis() - targetStamp < 3000) moved = step(s, tx, ty, tz, 2.5);
                }
                case GOTO -> {
                    moved = step(s, gx, gy, gz, 0.4);
                    if (!moved) finish("Bot dotarł do wskazanych współrzędnych.");
                }
                case HOME -> {
                    moved = step(s, homeX, homeY, homeZ, 0.4);
                    if (!moved) finish("Bot wrócił na pozycję startową.");
                }
                default -> { }
            }
            if (!moved && tickCounter % 20 == 0) sendPos(s, true);
            if (tickCounter % 20 == 0) out.event("pos", "x", x, "y", y, "z", z);
        }
    }

    private void finish(String message) {
        setMode(Mode.IDLE);
        out.log("info", message);
    }

    /** @return true gdy bot się poruszył, false gdy jest już w celu. */
    private boolean step(Session s, double ax, double ay, double az, double stop) {
        double dx = ax - x;
        double dz = az - z;
        double dy = ay - y;
        double dist = Math.hypot(dx, dz);
        boolean horizDone = dist <= stop;
        boolean vertDone = Math.abs(dy) <= 0.3;
        if (horizDone && vertDone) return false;
        if (!horizDone) {
            double len = Math.min(WALK_STEP, dist - stop);
            x += dx / dist * len;
            z += dz / dist * len;
        }
        if (!vertDone) y += Math.max(-0.4, Math.min(0.4, dy));
        sendPos(s, vertDone);
        return true;
    }
}
