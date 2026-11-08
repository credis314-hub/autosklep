package pl.autosklep;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/**
 * AutoSklep for Fabric 1.21.4.
 * K otwiera okno ustawien.
 * Cykl: #mine (X sekund) -> #stop -> 5 s przerwy -> /sklep sprzedaz -> wyrzucenie smieci -> #mine -> ...
 * Sloty liczone od zera: slot = (rzad - 1) * 9 + (kolumna - 1)
 * Kompas 21, czerwona welna 23, zelazo 14, diament 15, redstone 16.
 */
public class AutoSklepClient implements ClientModInitializer {
    private static final String STOP_COMMAND = "#stop";
    private static final int COOLDOWN_TICKS = 20 * 5;

    private static final int COMPASS_SLOT = 21;
    private static final int WOOL_SLOT = 23;
    private static final int IRON_SLOT = 14;
    private static final int DIAMOND_SLOT = 15;
    private static final int REDSTONE_SLOT = 16;

    // Sloty ekwipunku gracza w PlayerScreenHandler: 9-35 plecak, 36-44 pasek
    private static final int INV_FIRST = 9;
    private static final int INV_LAST = 44;

    /** Przedmioty, ktorych NIE wyrzucamy (poza kilofami). */
    private static final Set<String> KEEP = Set.of(
            "diamond", "diamond_ore", "deepslate_diamond_ore",
            "raw_iron", "iron_ingot", "iron_ore", "deepslate_iron_ore",
            "redstone", "redstone_ore", "deepslate_redstone_ore",
            "beef", "cooked_beef", "porkchop", "cooked_porkchop",
            "mutton", "cooked_mutton", "chicken", "cooked_chicken",
            "rabbit", "cooked_rabbit"
    );

    private KeyBinding openKey;
    private boolean enabled = false;
    private int tickCounter = 0;
    private int waitTicks = 0;
    private int retries = 0;
    private int dropIndex = INV_FIRST;
    private Stage stage = Stage.IDLE;

    private enum Stage {
        IDLE, MINING, PRE_SHOP, WAIT_FIRST_MENU, WAIT_SECOND_MENU, WAIT_SELL_MENU,
        SELL_IRON, SELL_DIAMOND, SELL_REDSTONE, FINISH, DROP_JUNK
    }

    /** Ustawienia zapisywane w config/autosklep.properties */
    static final class Config {
        static String mineCommand = "#mine minecraft:diamond_ore minecraft:iron_ore minecraft:redstone_ore";
        static int mineSeconds = 60;
        static boolean sellIron = true;
        static boolean sellDiamond = true;
        static boolean sellRedstone = true;
        static boolean dropJunk = true;

        private static Path file() {
            return FabricLoader.getInstance().getConfigDir().resolve("autosklep.properties");
        }

        static void load() {
            Path f = file();
            if (!Files.exists(f)) return;
            try (InputStream in = Files.newInputStream(f)) {
                Properties p = new Properties();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                mineCommand = p.getProperty("mineCommand", mineCommand);
                try {
                    mineSeconds = Math.max(10, Integer.parseInt(p.getProperty("mineSeconds", String.valueOf(mineSeconds))));
                } catch (NumberFormatException ignored) { }
                sellIron = Boolean.parseBoolean(p.getProperty("sellIron", "true"));
                sellDiamond = Boolean.parseBoolean(p.getProperty("sellDiamond", "true"));
                sellRedstone = Boolean.parseBoolean(p.getProperty("sellRedstone", "true"));
                dropJunk = Boolean.parseBoolean(p.getProperty("dropJunk", "true"));
            } catch (IOException ignored) { }
        }

        static void save() {
            Properties p = new Properties();
            p.setProperty("mineCommand", mineCommand);
            p.setProperty("mineSeconds", String.valueOf(mineSeconds));
            p.setProperty("sellIron", String.valueOf(sellIron));
            p.setProperty("sellDiamond", String.valueOf(sellDiamond));
            p.setProperty("sellRedstone", String.valueOf(sellRedstone));
            p.setProperty("dropJunk", String.valueOf(dropJunk));
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "AutoSklep");
            } catch (IOException ignored) { }
        }
    }

    /** Okno ustawien otwierane klawiszem K */
    static final class ConfigScreen extends Screen {
        private final AutoSklepClient mod;
        private TextFieldWidget commandField;
        private TextFieldWidget secondsField;
        private ButtonWidget startButton;

        ConfigScreen(AutoSklepClient mod) {
            super(Text.literal("AutoSklep"));
            this.mod = mod;
        }

        private static Text toggleLabel(String name, boolean on) {
            return Text.literal(name + ": " + (on ? "TAK" : "NIE"));
        }

        private Text startLabel() {
            return Text.literal(mod.enabled ? "STOP" : "START");
        }

        @Override
        protected void init() {
            int cx = width / 2;
            int top = height / 2 - 110;

            commandField = new TextFieldWidget(textRenderer, cx - 150, top + 34, 300, 20, Text.literal("Komenda"));
            commandField.setMaxLength(256);
            commandField.setText(Config.mineCommand);
            addDrawableChild(commandField);

            secondsField = new TextFieldWidget(textRenderer, cx - 150, top + 78, 80, 20,
