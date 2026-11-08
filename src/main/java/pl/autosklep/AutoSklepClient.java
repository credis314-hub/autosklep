package pl.autosklep;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * AutoSklep for Fabric 1.21.4.
 * K: start -> #mine (3 min) -> #stop -> /sklep sprzedaz -> #mine -> ...
 * Sloty liczone od zera: slot = (rzad - 1) * 9 + (kolumna - 1)
 * Kompas 21, czerwona welna 23, zelazo 14, diament 15, redstone 16.
 */
public class AutoSklepClient implements ClientModInitializer {
    private static final int MINE_TICKS = 20 * 60 * 3;
    private static final String MINE_COMMAND =
            "#mine minecraft:diamond_ore minecraft:iron_ore minecraft:redstone_ore";
    private static final String STOP_COMMAND = "#stop";

    private static final int COMPASS_SLOT = 21;
    private static final int WOOL_SLOT = 23;
    private static final int[] SELL_SLOTS = {14, 15, 16};

    private KeyBinding toggleKey;
    private boolean enabled = false;
    private int tickCounter = 0;
    private int waitTicks = 0;
    private int retries = 0;
    private Stage stage = Stage.IDLE;

    private enum Stage {
        IDLE, MINING, WAIT_FIRST_MENU, WAIT_SECOND_MENU, WAIT_SELL_MENU,
        SELL_IRON, SELL_DIAMOND, SELL_REDSTONE, FINISH
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autosklep.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "key.categories.misc"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    private void tick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            if (client.player == null) break;
            enabled = !enabled;
            tickCounter = 0;
            waitTicks = 0;
            if (enabled) {
                tell(client, "AutoSklep: WŁĄCZONY (K)");
                startMining(client);
            } else {
                stage = Stage.IDLE;
                sendChat(client, STOP_COMMAND);
                tell(client, "AutoSklep: WYŁĄCZONY (K)");
            }
        }

        if (!enabled || client.player == null || client.world == null) return;

        if (stage == Stage.MINING) {
            if (++tickCounter >= MINE_TICKS) {
                tickCounter = 0;
                sendChat(client, STOP_COMMAND);
                tell(client, "AutoSklep: koniec kopania, wpisuję /sklep");
                client.player.networkHandler.sendChatCommand("sklep");
                stage = Stage.WAIT_FIRST_MENU;
                retries = 0;
                waitTicks = 40;
            }
            return;
        }

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        if (client.currentScreen == null || client.player.currentScreenHandler == client.player.playerScreenHandler) {
            if (stage != Stage.WAIT_FIRST_MENU) abort(client, "Nie znaleziono oczekiwanego menu. Automat zatrzymany.");
            else if (++retries > 30) abort(client, "Menu /sklep nie otworzyło się. Automat zatrzymany.");
            else waitTicks = 10;
            return;
        }

        switch (stage) {
            case WAIT_FIRST_MENU -> {
                click(client, COMPASS_SLOT, 0, SlotActionType.PICKUP);
                stage = Stage.WAIT_SECOND_MENU;
                waitTicks = 12;
            }
            case WAIT_SECOND_MENU -> {
                click(client, WOOL_SLOT, 0, SlotActionType.PICKUP);
                stage = Stage.WAIT_SELL_MENU;
                waitTicks = 12;
            }
            case WAIT_SELL_MENU -> {
                stage = Stage.SELL_IRON;
                waitTicks = 4;
            }
            case SELL_IRON -> {
                shiftRightClick(client, SELL_SLOTS[0]);
                stage = Stage.SELL_DIAMOND;
                waitTicks = 4;
            }
            case SELL_DIAMOND -> {
                shiftRightClick(client, SELL_SLOTS[1]);
                stage = Stage.SELL_REDSTONE;
                waitTicks = 4;
            }
            case SELL_REDSTONE -> {
                shiftRightClick(client, SELL_SLOTS[2]);
                stage = Stage.FINISH;
                waitTicks = 10;
            }
            case FINISH -> {
                client.player.closeHandledScreen();
                tell(client, "AutoSklep: sprzedaż zakończona, wracam do kopania.");
                startMining(client);
            }
            default -> { }
        }
    }

    private void startMining(MinecraftClient client) {
        sendChat(client, MINE_COMMAND);
        stage = Stage.MINING;
        tickCounter = 0;
    }

    private void sendChat(MinecraftClient client, String message) {
        if (client.player != null) client.player.networkHandler.sendChatMessage(message);
    }

    private void click(MinecraftClient client, int slot, int button, SlotActionType action) {
        if (client.player == null || client.interactionManager == null) return;
        ScreenHandler h = client.player.currentScreenHandler;
        if (slot < 0 || slot >= h.slots.size()) {
            abort(client, "Slot poza zakresem menu: " + slot);
            return;
        }
        client.interactionManager.clickSlot(h.syncId, slot, button, action, client.player);
    }

    private void shiftRightClick(MinecraftClient client, int slot) {
        click(client, slot, 1, SlotActionType.QUICK_MOVE);
    }

    private void abort(MinecraftClient client, String message) {
        stage = Stage.IDLE;
        enabled = false;
        tell(client, "AutoSklep: " + message + " Wciśnij K, aby ponownie włączyć.");
    }

    private void tell(MinecraftClient client, String message) {
        if (client.player != null) client.player.sendMessage(Text.literal(message), false);
    }
}
