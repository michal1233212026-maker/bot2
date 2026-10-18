package pl.anarchiabot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/** Wątkowo-bezpieczny bufor logów (działania, błędy, czat bota). */
public final class LogBuffer {
    public enum Kind { INFO, WARN, ERROR, CHAT, SENT }

    public record Entry(long time, Kind kind, String text) {}

    private static final int MAX = 500;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private volatile int version;

    public synchronized void add(Kind kind, String text) {
        entries.addLast(new Entry(System.currentTimeMillis(), kind, text));
        while (entries.size() > MAX) entries.removeFirst();
        version++;
    }

    public synchronized List<Entry> snapshot(Predicate<Entry> filter) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (filter.test(e)) out.add(e);
        return out;
    }

    public synchronized void clear() {
        entries.clear();
        version++;
    }

    public int version() {
        return version;
    }
}
