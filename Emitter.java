package pl.anarchiabot.runner;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.PrintStream;

/** Wysyla zdarzenia (jedna linia JSON) do moda przez stdout. */
final class Emitter {
    private static final Gson GSON = new Gson();
    private final PrintStream out;

    Emitter(PrintStream out) {
        this.out = out;
    }

    synchronized void event(String name, Object... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("ev", name);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            String k = String.valueOf(kv[i]);
            if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, v == null ? null : String.valueOf(v));
        }
        out.println(GSON.toJson(o));
        out.flush();
    }

    void log(String level, String text) {
        event("log", "level", level, "text", text);
    }

    void status(String state, String reason) {
        event("status", "state", state, "reason", reason == null ? "" : reason);
    }

    static String plain(Object component) {
        if (component == null) return "";
        if (component instanceof Component c) return PlainTextComponentSerializer.plainText().serialize(c);
        return String.valueOf(component);
    }
}
