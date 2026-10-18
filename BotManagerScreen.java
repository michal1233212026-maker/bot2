package pl.anarchiabot.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import pl.anarchiabot.config.BotConfig;
import pl.anarchiabot.core.BotController;
import pl.anarchiabot.core.BotStatus;
import pl.anarchiabot.core.LogBuffer;
import pl.anarchiabot.core.LogBuffer.Kind;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Ciemny panel zarządzania botem: zakładki Bot / Ruch / Czat / Logi. Nie pauzuje gry. */
public final class BotManagerScreen extends Screen {
    private enum Tab {
        BOT("Bot"), MOVE("Ruch"), CHAT("Czat"), LOGS("Logi");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private static final int C_OVERLAY = 0xB0070910;
    private static final int C_PANEL = 0xF0151922;
    private static final int C_BORDER = 0xFF2B3342;
    private static final int C_HEADER = 0xFF1B2230;
    private static final int C_LOGBG = 0xFF0D1017;
    private static final int C_ACCENT = 0xFF4C8DFF;
    private static final int C_TEXT = 0xFFE6E9EF;
    private static final int C_MUTED = 0xFF8A93A6;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static Tab currentTab = Tab.BOT;

    private record Label(String text, int x, int y) {}

    private final BotController controller;
    private final BotConfig cfg;
    private final KeyBinding openKey;

    private String nameVal, serverVal, loginVal, followVal, xVal, yVal, zVal, chatVal = "";

    private int panelX, panelY, panelW, panelH, cx, cw, contentY;
    private int tabY, tabW;
    private boolean compact;
    private int infoY = -1;
    private final List<Label> labels = new ArrayList<>();
    private int logX, logY, logW, logH;
    private boolean showLog;
    private int logScroll;

    private List<OrderedText> cachedLines = List.of();
    private int cachedVersion = -1, cachedWidth = -1;
    private Tab cachedTab;

    private ButtonWidget btnCreate, btnRemove, btnConnect, btnDisconnect, btnFollow, btnStop, btnGo, btnHome,
            btnTpa, btnAccept, btnAuto, btnSend;
    private TextFieldWidget fName, fServer, fLogin, fFollow, fX, fY, fZ, fChat;

    public BotManagerScreen(BotController controller, KeyBinding openKey) {
        super(Text.literal("Anarchia Bot Manager"));
        this.controller = controller;
        this.cfg = controller.config();
        this.openKey = openKey;
        nameVal = cfg.botName;
        serverVal = cfg.server;
        loginVal = cfg.postLoginCommands;
        followVal = cfg.followPlayer;
        xVal = fmt(cfg.targetX);
        yVal = fmt(cfg.targetY);
        zVal = fmt(cfg.targetZ);
    }

    // ------------------------------------------------------------------ układ

    @Override
    protected void init() {
        labels.clear();
        btnCreate = btnRemove = btnConnect = btnDisconnect = btnFollow = btnStop = btnGo = btnHome = null;
        btnTpa = btnAccept = btnAuto = btnSend = null;
        fName = fServer = fLogin = fFollow = fX = fY = fZ = fChat = null;
        infoY = -1;
        showLog = false;

        compact = height < 230;
        panelW = Math.min(width - 8, 460);
        panelH = Math.min(height - 8, 250);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        cx = panelX + 10;
        cw = panelW - 20;
        tabY = panelY + 26;
        contentY = panelY + 50;

        tabW = (cw - 12) / 4;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            final Tab t = tabs[i];
            ButtonWidget b = button(t.label, cx + i * (tabW + 4), tabY, tabW, 18, () -> {
                currentTab = t;
                logScroll = 0;
                clearAndInit();
            });
            b.active = t != currentTab;
        }

        int L = compact ? 0 : 10;
        int y = contentY;
        switch (currentTab) {
            case BOT -> {
                label("Nazwa bota", cx, y);
                y += L;
                int fw = (int) (cw * 0.55);
                fName = field(cx, y, fw, nameVal, "Nazwa bota", 16, s -> nameVal = s);
                btnCreate = button("✚ Utwórz bota", cx + fw + 4, y, cw - fw - 4, 18, () -> {
                    controller.createBot(nameVal);
                });
                y += 22;

                label("Serwer (host:port)", cx, y);
                y += L;
                int sw = (int) (cw * 0.68);
                fServer = field(cx, y, sw, serverVal, "anarchia.gg", 100, s -> serverVal = s);
                button("Bieżący", cx + sw + 4, y, cw - sw - 4, 18, this::useCurrentServer);
                y += 22;

                int half = (cw - 4) / 2;
                btnConnect = button("▶ Połącz", cx, y, half, 18, () -> {
                    applyToConfig();
                    controller.connect();
                });
                btnDisconnect = button("■ Rozłącz", cx + half + 4, y, cw - half - 4, 18, controller::disconnect);
                y += 22;

                label("Komendy po wejściu (oddziel ;)", cx, y);
                y += L;
                fLogin = field(cx, y, cw, loginVal, "np. /login haslo", 200, s -> loginVal = s);
                y += 22;

                btnRemove = button("✖ Usuń bota", cx, y, half, 18, controller::removeBot);
                button("✔ Zapisz konfigurację", cx + half + 4, y, cw - half - 4, 18, () -> {
                    applyToConfig();
                    controller.saveConfig(true);
                });
            }
            case MOVE -> {
                label("Gracz do śledzenia (w Twoim zasięgu)", cx, y);
                y += L;
                int fw = (int) (cw * 0.5);
                fFollow = field(cx, y, fw, followVal, "Nick gracza", 16, s -> followVal = s);
                btnFollow = button("Podążaj za graczem", cx + fw + 4, y, cw - fw - 4, 18, () -> controller.follow(followVal));
                y += 22;

                int half = (cw - 4) / 2;
                btnStop = button("■ Zatrzymaj", cx, y, half, 18, controller::stop);
                btnHome = button("↺ Wróć do startu", cx + half + 4, y, cw - half - 4, 18, controller::returnHome);
                y += 22;

                label("Cel: X / Y / Z", cx, y);
                y += L;
                int tw = (cw - 8) / 3;
                fX = field(cx, y, tw, xVal, "X", 12, s -> xVal = s);
                fY = field(cx + tw + 4, y, tw, yVal, "Y", 12, s -> yVal = s);
                fZ = field(cx + 2 * (tw + 4), y, cw - 2 * (tw + 4), zVal, "Z", 12, s -> zVal = s);
                for (TextFieldWidget f : new TextFieldWidget[]{fX, fY, fZ}) f.setTextPredicate(s -> s.matches("-?\\d*\\.?\\d*"));
                y += 22;

                int gw = (int) (cw * 0.66);
                btnGo = button("Idź do wskazanych współrzędnych", cx, y, gw, 18, this::goToCoords);
                button("Moje XYZ", cx + gw + 4, y, cw - gw - 4, 18, this::fillMyCoords);
                y += 26;
                infoY = y;
            }
            case CHAT -> {
                int third = (cw - 8) / 3;
                btnTpa = button("TP bota do mnie", cx, y, third, 18, controller::tpaToMe);
                btnAccept = button("Akceptuj TPA (ja)", cx + third + 4, y, third, 18, this::acceptTpaSelf);
                btnAuto = button(autoLabel(), cx + 2 * (third + 4), y, cw - 2 * (third + 4), 18, () -> {
                    cfg.autoAcceptTpa = !cfg.autoAcceptTpa;
                    btnAuto.setMessage(Text.literal(autoLabel()));
                });
                int inputY = panelY + panelH - 28;
                logX = cx;
                logY = y + 22;
                logW = cw;
                logH = inputY - 4 - logY;
                showLog = true;
                int sendW = 56;
                fChat = field(cx, inputY, cw - sendW - 4, chatVal, "Napisz jako bot (/komenda też działa)", 256, s -> chatVal = s);
                btnSend = button("Wyślij", cx + cw - sendW, inputY, sendW, 18, this::sendChat);
            }
            case LOGS -> {
                int bottomY = panelY + panelH - 28;
                logX = cx;
                logY = contentY;
                logW = cw;
                logH = bottomY - 4 - logY;
                showLog = true;
                int half = (cw - 4) / 2;
                button("Wyczyść logi", cx, bottomY, half, 18, () -> controller.log().clear());
                button("✔ Zapisz konfigurację", cx + half + 4, bottomY, cw - half - 4, 18, () -> {
                    applyToConfig();
                    controller.saveConfig(true);
                });
            }
        }
        refreshStates();
    }

    private ButtonWidget button(String text, int x, int y, int w, int h, Runnable action) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(text), btn -> action.run()).dimensions(x, y, w, h).build();
        addDrawableChild(b);
        return b;
    }

    private TextFieldWidget field(int x, int y, int w, String value, String hint, int max, Consumer<String> onChange) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.empty());
        f.setMaxLength(max);
        f.setText(value);
        f.setPlaceholder(Text.literal(hint).styled(s -> s.withColor(0x6B7385)));
        f.setChangedListener(onChange);
        addDrawableChild(f);
        return f;
    }

    private void label(String text, int x, int y) {
        if (!compact) labels.add(new Label(text, x, y));
    }

    // ------------------------------------------------------------------ akcje

    private void applyToConfig() {
        cfg.botName = nameVal.trim();
        cfg.server = serverVal.trim();
        cfg.postLoginCommands = loginVal;
        cfg.followPlayer = followVal.trim();
        Double x = parse(xVal), y = parse(yVal), z = parse(zVal);
        if (x != null) cfg.targetX = x;
        if (y != null) cfg.targetY = y;
        if (z != null) cfg.targetZ = z;
    }

    private void useCurrentServer() {
        ServerInfo info = MinecraftClient.getInstance().getCurrentServerEntry();
        if (info == null) {
            controller.log().add(Kind.WARN, "Nie jesteś połączony z serwerem multiplayer – wpisz adres ręcznie.");
            return;
        }
        serverVal = info.address;
        if (fServer != null) fServer.setText(serverVal);
    }

    private void goToCoords() {
        Double x = parse(xVal), y = parse(yVal), z = parse(zVal);
        if (x == null || y == null || z == null) {
            controller.log().add(Kind.WARN, "Podaj poprawne współrzędne X, Y i Z.");
            return;
        }
        controller.goTo(x, y, z);
    }

    private void fillMyCoords() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        xVal = fmt(mc.player.getX());
        yVal = fmt(mc.player.getY());
        zVal = fmt(mc.player.getZ());
        fX.setText(xVal);
        fY.setText(yVal);
        fZ.setText(zVal);
    }

    private void acceptTpaSelf() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.player.networkHandler == null) return;
        String c = cfg.acceptCommand.startsWith("/") ? cfg.acceptCommand.substring(1) : cfg.acceptCommand;
        mc.player.networkHandler.sendChatCommand(c);
        controller.log().add(Kind.INFO, "Wysłano /" + c + " z Twojego konta.");
    }

    private void sendChat() {
        String t = chatVal.trim();
        if (t.isEmpty()) return;
        controller.sendBotChat(t);
        chatVal = "";
        if (fChat != null) {
            fChat.setText("");
            setFocused(fChat);
        }
    }

    private String autoLabel() {
        return "Auto-TPA: " + (cfg.autoAcceptTpa ? "wł." : "wył.");
    }

    private static Double parse(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ------------------------------------------------------------------ stan przycisków

    @Override
    public void tick() {
        refreshStates();
    }

    private void refreshStates() {
        BotStatus st = controller.status();
        boolean ready = controller.isReady();
        boolean on = st == BotStatus.CONNECTED;
        if (btnCreate != null) btnCreate.active = !ready;
        if (btnRemove != null) btnRemove.active = ready;
        if (btnConnect != null) btnConnect.active = ready && (st == BotStatus.INACTIVE || st == BotStatus.DISCONNECTED);
        if (btnDisconnect != null) btnDisconnect.active = st == BotStatus.CONNECTING || on;
        if (btnFollow != null) btnFollow.active = on;
        if (btnStop != null) btnStop.active = on;
        if (btnGo != null) btnGo.active = on;
        if (btnHome != null) btnHome.active = on;
        if (btnTpa != null) btnTpa.active = on;
        if (btnSend != null) btnSend.active = on;
    }

    // ------------------------------------------------------------------ rysowanie

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, C_OVERLAY);
        box(ctx, panelX, panelY, panelW, panelH, C_PANEL, C_BORDER);
        ctx.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 22, C_HEADER);
        ctx.fill(panelX + 1, panelY + 22, panelX + panelW - 1, panelY + 23, C_BORDER);

        // podkreślenie aktywnej zakładki
        int idx = currentTab.ordinal();
        int ux = cx + idx * (tabW + 4);
        ctx.fill(ux, tabY + 19, ux + tabW, tabY + 21, C_ACCENT);

        if (showLog) box(ctx, logX, logY, logW, logH, C_LOGBG, C_BORDER);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        // nagłówek: tytuł + status
        ctx.drawText(textRenderer, "Anarchia Bot Manager", panelX + 10, panelY + 7, C_TEXT, true);
        BotStatus st = controller.status();
        String stText = st.label();
        int tw = textRenderer.getWidth(stText);
        int sx = panelX + panelW - 10 - tw;
        ctx.drawText(textRenderer, stText, sx, panelY + 7, 0xFF000000 | st.rgb(), true);
        ctx.fill(sx - 12, panelY + 8, sx - 5, panelY + 15, 0xFF000000 | st.rgb());
        if (st == BotStatus.CONNECTED) {
            String m = controller.mode().label();
            ctx.drawText(textRenderer, m, sx - 20 - textRenderer.getWidth(m), panelY + 7, C_MUTED, false);
        }

        for (Label l : labels) ctx.drawText(textRenderer, l.text(), l.x(), l.y(), C_MUTED, false);

        if (currentTab == Tab.MOVE && infoY >= 0) {
            double[] p = controller.position();
            double[] h = controller.home();
            ctx.drawText(textRenderer, "Pozycja bota: " + coords(p), cx, infoY, C_TEXT, false);
            ctx.drawText(textRenderer, "Pozycja startowa: " + coords(h), cx, infoY + 11, C_TEXT, false);
        }
        if (showLog) drawLog(ctx);
    }

    private static String coords(double[] p) {
        return p == null ? "—" : String.format(Locale.ROOT, "%.1f / %.1f / %.1f", p[0], p[1], p[2]);
    }

    private void drawLog(DrawContext ctx) {
        int version = controller.log().version();
        if (version != cachedVersion || logW != cachedWidth || currentTab != cachedTab) {
            boolean chatOnly = currentTab == Tab.CHAT;
            List<LogBuffer.Entry> entries = controller.log().snapshot(
                    e -> !chatOnly || e.kind() == Kind.CHAT || e.kind() == Kind.SENT);
            List<OrderedText> lines = new ArrayList<>();
            for (LogBuffer.Entry e : entries) {
                int color = switch (e.kind()) {
                    case ERROR -> 0xFF6B6B;
                    case WARN -> 0xE5B83A;
                    case CHAT -> 0xE6E9EF;
                    case SENT -> 0x7FB4FF;
                    default -> 0x9FB0C8;
                };
                String time = Instant.ofEpochMilli(e.time()).atZone(ZoneId.systemDefault()).toLocalTime().format(TIME);
                Text t = Text.literal("[" + time + "] " + e.text()).styled(s -> s.withColor(color));
                lines.addAll(textRenderer.wrapLines(t, logW - 10));
            }
            cachedLines = lines;
            cachedVersion = version;
            cachedWidth = logW;
            cachedTab = currentTab;
        }
        int fh = textRenderer.fontHeight + 1;
        int visible = Math.max(1, (logH - 6) / fh);
        int maxScroll = Math.max(0, cachedLines.size() - visible);
        logScroll = Math.max(0, Math.min(logScroll, maxScroll));
        int end = cachedLines.size() - logScroll;
        int start = Math.max(0, end - visible);

        ctx.enableScissor(logX + 1, logY + 1, logX + logW - 1, logY + logH - 1);
        if (cachedLines.isEmpty()) {
            ctx.drawText(textRenderer, "Brak wpisów.", logX + 5, logY + 4, C_MUTED, false);
        }
        int ly = logY + 4;
        for (int i = start; i < end; i++) {
            ctx.drawText(textRenderer, cachedLines.get(i), logX + 5, ly, 0xFFFFFFFF, false);
            ly += fh;
        }
        ctx.disableScissor();

        if (maxScroll > 0) { // wskaźnik przewijania
            int barH = Math.max(10, logH * visible / cachedLines.size());
            int barY = logY + (logH - barH) * (maxScroll - logScroll) / maxScroll;
            ctx.fill(logX + logW - 3, barY, logX + logW - 1, barY + barH, C_ACCENT);
        }
    }

    private static void box(DrawContext ctx, int x, int y, int w, int h, int fill, int border) {
        ctx.fill(x, y, x + w, y + h, border);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    // ------------------------------------------------------------------ wejście

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        boolean typing = anyFieldFocused();
        if (!typing && openKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (currentTab == Tab.CHAT && fChat != null && fChat.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            sendChat();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean anyFieldFocused() {
        for (TextFieldWidget f : new TextFieldWidget[]{fName, fServer, fLogin, fFollow, fX, fY, fZ, fChat}) {
            if (f != null && f.isFocused()) return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (showLog && mouseX >= logX && mouseX <= logX + logW && mouseY >= logY && mouseY <= logY + logH) {
            logScroll += (int) Math.signum(verticalAmount) * 3;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() {
        return false; // gra działa dalej
    }

    @Override
    public void removed() {
        applyToConfig();
        controller.saveConfig(false);
        super.removed();
    }
}
