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
 *
 * Slot coordinates supplied by the user are 1-based (row, column):
 * Compass: row 3 col 4 -> GUI slot 22
 * Red wool: row 3 col 6 -> GUI slot 24
 * Raw iron: row 2 col 6 -> GUI slot 14
 * Diamond: row 2 col 7 -> GUI slot 15
 * Redstone: row 2 col 8 -> GUI slot 16
 *
 * The menu is assumed to be a 9x6 chest-like container.
 */
public class AutoSklepClient implements ClientModInitializer {
    private static final int INTERVAL_TICKS = 20 * 60 * 10;
    private static final int[] SELL_SLOTS = {14, 15, 16};

    private KeyBinding toggleKey;
    private boolean enabled = false;
    private int tickCounter = 0;
    private int waitTicks = 0;
    private Stage stage = Stage.IDLE;
    private int lastSyncId = -1;
    private int retries = 0;

    private enum Stage {
        IDLE, WAIT_FIRST_MENU, WAIT_SECOND_MENU, WAIT_SELL_MENU, SELL_IRON, SELL_DIAMOND, SELL_REDSTONE, FINISH
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
            enabled = !enabled;
            tickCounter = 0;
            stage = Stage.IDLE;
            tell(client, enabled ? "AutoSklep: WŁĄCZONY (K)" : "AutoSklep: WYŁĄCZONY (K)");
        }

        if (!enabled || client.player == null || client.world == null) return;

        if (stage == Stage.IDLE) {
            if (++tickCounter >= INTERVAL_TICKS) {
                tickCounter = 0;
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
            // Do not blindly click if the expected server GUI is not open.
            if (stage != Stage.WAIT_FIRST_MENU) abort(client, "Nie znaleziono oczekiwanego menu. Automat zatrzymany.");
            else if (++retries > 30) abort(client, "Menu /sklep nie otworzyło się. Automat zatrzymany.");
            else waitTicks = 10;
            return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler.syncId != lastSyncId) lastSyncId = handler.syncId;

        switch (stage) {
            case WAIT_FIRST_MENU -> {
                click(client, 22, 0, SlotActionType.PICKUP); // compass
                stage = Stage.WAIT_SECOND_MENU;
                waitTicks = 12;
            }
            case WAIT_SECOND_MENU -> {
                click(client, 24, 0, SlotActionType.PICKUP); // red wool
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
                stage = Stage.IDLE;
                tell(client, "AutoSklep: zakończono próbę sprzedaży. Następna za 10 minut.");
            }
            default -> { }
        }
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
        // QUICK_MOVE is the inventory protocol's shift-click action. Button 1 requests right-button variant.
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
