package pl.anarchiabot.config;

/** Zapisywana konfiguracja moda (config/anarchia_bot_manager.json). */
public final class BotConfig {
    public String botName = "AnarchiaBot";
    public String server = "anarchia.gg";
    /** Komendy wysyłane przez bota po wejściu, oddzielone średnikiem (np. "/login haslo"). UWAGA: zapisywane jawnym tekstem. */
    public String postLoginCommands = "";
    public int postLoginDelayMs = 3000;

    public String followPlayer = "";
    public double targetX = 0;
    public double targetY = 64;
    public double targetZ = 0;

    /** {player} zostanie zastąpione Twoim nickiem. */
    public String tpaCommand = "tpa {player}";
    public String acceptCommand = "tpaccept";
    public boolean autoAcceptTpa = false;
    public String autoAcceptRegex = "(?i)(tpa|teleport)";
}
