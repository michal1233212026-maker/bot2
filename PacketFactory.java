package pl.anarchiabot.runner;

import org.geysermc.mcprotocollib.network.packet.Packet;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * Tworzy pakiety serverbound po nazwie klasy. Dzieki temu drobne roznice miedzy
 * wersjami MCProtocolLib (np. dodatkowe pola w konstruktorze) nie lamia kompilacji -
 * dla kazdego pakietu podajemy liste wariantow argumentow.
 */
final class PacketFactory {
    private static final String BASE = "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.";
    private static final String[] SUB = {"", "level.", "player.", "inventory.", "entity.player.", "entity."};
    private static final Map<String, Class<?>> CACHE = new HashMap<>();

    private PacketFactory() {}

    static synchronized Packet create(String simpleName, Object[]... variants) throws ReflectiveOperationException {
        Class<?> cls = CACHE.get(simpleName);
        if (cls == null) {
            for (String sub : SUB) {
                try {
                    cls = Class.forName(BASE + sub + simpleName);
                    break;
                } catch (ClassNotFoundException ignored) {
                    // nastepny pakiet
                }
            }
            if (cls == null) throw new ClassNotFoundException("Nie znaleziono pakietu " + simpleName);
            CACHE.put(simpleName, cls);
        }
        for (Object[] args : variants) {
            for (Constructor<?> c : cls.getConstructors()) {
                if (matches(c.getParameterTypes(), args)) {
                    return (Packet) c.newInstance(args);
                }
            }
        }
        throw new NoSuchMethodException("Brak pasującego konstruktora dla " + simpleName);
    }

    static Packet respawn() throws ReflectiveOperationException {
        Class<?> en = Class.forName("org.geysermc.mcprotocollib.protocol.data.game.ClientCommand");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object respawn = Enum.valueOf((Class<Enum>) en, "RESPAWN");
        return create("ServerboundClientCommandPacket", new Object[]{respawn});
    }

    private static boolean matches(Class<?>[] params, Object[] args) {
        if (params.length != args.length) return false;
        for (int i = 0; i < params.length; i++) {
            if (args[i] == null) {
                if (params[i].isPrimitive()) return false;
            } else if (!wrap(params[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        return Character.class;
    }
}
