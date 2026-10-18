package pl.anarchiabot.runner;

import java.lang.reflect.Method;

/** Odporny na drobne zmiany API odczyt pol pakietow (gettery / rekordy). */
final class Reflect {
    private Reflect() {}

    static Object call(Object target, String... names) {
        if (target == null) return null;
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // sprobuj nastepnej nazwy
            }
        }
        return null;
    }

    static double num(Object target, double def, String... names) {
        Object r = call(target, names);
        return r instanceof Number n ? n.doubleValue() : def;
    }
}
