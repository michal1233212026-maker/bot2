package pl.anarchiabot.core;

public enum BotStatus {
    INACTIVE("Nieaktywny", 0x8A93A6),
    CONNECTING("Łączenie", 0xE5B83A),
    CONNECTED("Połączony", 0x3FCF72),
    DISCONNECTED("Rozłączony", 0xE5534B);

    private final String label;
    private final int rgb;

    BotStatus(String label, int rgb) {
        this.label = label;
        this.rgb = rgb;
    }

    public String label() {
        return label;
    }

    public int rgb() {
        return rgb;
    }
}
