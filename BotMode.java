package pl.anarchiabot.core;

public enum BotMode {
    IDLE("Stoi w miejscu"),
    FOLLOW("Podąża za graczem"),
    GOTO("Idzie do współrzędnych"),
    HOME("Wraca do startu");

    private final String label;

    BotMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static BotMode parse(String s) {
        try {
            return valueOf(s);
        } catch (Exception e) {
            return IDLE;
        }
    }
}
