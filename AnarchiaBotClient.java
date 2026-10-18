package pl.anarchiabot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.lwjgl.glfw.GLFW;
import pl.anarchiabot.config.BotConfig;
import pl.anarchiabot.config.ConfigManager;
import pl.anarchiabot.core.BotController;
import pl.anarchiabot.gui.BotManagerScreen;

public final class AnarchiaBotClient implements ClientModInitializer {
    public static final String MOD_ID = "anarchiabot";

    private static KeyBinding openKey;
    private static BotController controller;

    @Override
    public void onInitializeClient() {
        ConfigManager configManager = new ConfigManager();
        BotConfig config = configManager.load();
        controller = new BotController(config, configManager);

        // Klawisz X (zmienialny w Opcje -> Sterowanie -> Anarchia Bot Manager)
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.anarchiabot.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                "key.categories.anarchiabot"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            controller.setOwnerName(client.getSession().getUsername());
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new BotManagerScreen(controller, openKey));
                }
            }
            controller.followTick(AnarchiaBotClient::locatePlayer);
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> controller.shutdown());
    }

    /** Pozycja gracza widocznego w Twoim świecie klienta (lub null, jeśli poza zasięgiem). */
    private static double[] locatePlayer(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return null;
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(name)) {
                return new double[]{p.getX(), p.getY(), p.getZ()};
            }
        }
        return null;
    }
}
