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
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.CraftingScreenHandler;
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
 * Kompas 21, czerwona welna 23, zelazo 14, diament 15, redstone 16, lapis 25, miedz 12.
 */
public class AutoSklepClient implements ClientModInitializer {
    private static final String STOP_COMMAND = "#stop";
    private static final int COOLDOWN_TICKS = 20 * 5;

    private static final int COMPASS_SLOT = 21;
    private static final int WOOL_SLOT = 23;
    private static final int IRON_SLOT = 14;
    private static final int DIAMOND_SLOT = 15;
    private static final int REDSTONE_SLOT = 16;
    private static final int LAPIS_SLOT = 25;
    private static final int COPPER_SLOT = 12;

    // Sloty ekwipunku gracza w PlayerScreenHandler: 9-35 plecak, 36-44 pasek
    private static final int INV_FIRST = 9;
    private static final int INV_LAST = 44;

    /** Przedmioty, ktorych NIE wyrzucamy (poza kilofami). */
    private static final Set<String> KEEP = Set.of(
            "diamond", "diamond_ore", "deepslate_diamond_ore",
            "raw_iron", "iron_ingot", "iron_ore", "deepslate_iron_ore",
            "redstone", "redstone_block", "redstone_ore", "deepslate_redstone_ore",
            "lapis_lazuli", "lapis_ore", "deepslate_lapis_ore",
            "raw_copper", "copper_ingot", "copper_ore", "deepslate_copper_ore",
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
    private int marketSlot = -1;
    private int marketAttempts = 0;
    private int marketFills = 0;
    private int marketSwapSlot = -1;
    private int marketOrigSel = 0;
    private Stage stage = Stage.IDLE;

    private enum Stage {
        IDLE, MINING, PRE_SHOP, WAIT_FIRST_MENU, WAIT_SECOND_MENU, WAIT_SELL_MENU,
        SELL_IRON, SELL_DIAMOND, SELL_REDSTONE, SELL_LAPIS, SELL_COPPER, FINISH, DROP_JUNK,
        MARKET_START, MARKET_WAIT_WB, MARKET_FILL, MARKET_RETURN, MARKET_TAKE, MARKET_CLOSE,
        MARKET_LIST, MARKET_LIST_SEND, MARKET_RESTORE
    }

    /** Ustawienia zapisywane w config/autosklep.properties */
    static final class Config {
        static final String OLD_DEFAULT_MINE = "#mine minecraft:diamond_ore minecraft:iron_ore minecraft:redstone_ore";
        static final String PREV_DEFAULT_MINE = "#mine minecraft:diamond_ore minecraft:deepslate_diamond_ore"
                + " minecraft:iron_ore minecraft:deepslate_iron_ore"
                + " minecraft:redstone_ore minecraft:deepslate_redstone_ore";
        static final String DEEPSLATE_ONLY_MINE = "#mine minecraft:deepslate_diamond_ore minecraft:deepslate_iron_ore"
                + " minecraft:deepslate_redstone_ore minecraft:deepslate_lapis_ore";
        static final String DEFAULT_MINE = "#mine minecraft:diamond_ore minecraft:deepslate_diamond_ore"
                + " minecraft:iron_ore minecraft:deepslate_iron_ore"
                + " minecraft:redstone_ore minecraft:deepslate_redstone_ore"
                + " minecraft:lapis_ore minecraft:deepslate_lapis_ore";
        static String mineCommand = DEFAULT_MINE;
        static int mineSeconds = 60;
        static boolean sellIron = true;
        static boolean sellDiamond = true;
        static boolean sellRedstone = true;
        static boolean sellLapis = true;
        static boolean sellCopper = true;
        static boolean mineCopper = true;
        static boolean dropJunk = true;
        static boolean marketEnabled = false;
        static String marketPrice = "750";

        /** Komenda faktycznie wysylana do Baritone (z miedzia, jesli wlaczona i jeszcze jej nie ma). */
        static String effectiveMineCommand() {
            if (mineCopper && !mineCommand.contains("copper_ore")) {
                return mineCommand + " minecraft:copper_ore minecraft:deepslate_copper_ore";
            }
            return mineCommand;
        }

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
                if (mineCommand.equals(OLD_DEFAULT_MINE) || mineCommand.equals(PREV_DEFAULT_MINE)
                        || mineCommand.equals(DEEPSLATE_ONLY_MINE)) mineCommand = DEFAULT_MINE;
                try {
                    mineSeconds = Math.max(10, Integer.parseInt(p.getProperty("mineSeconds", String.valueOf(mineSeconds))));
                } catch (NumberFormatException ignored) { }
                sellIron = Boolean.parseBoolean(p.getProperty("sellIron", "true"));
                sellDiamond = Boolean.parseBoolean(p.getProperty("sellDiamond", "true"));
                sellRedstone = Boolean.parseBoolean(p.getProperty("sellRedstone", "true"));
                sellLapis = Boolean.parseBoolean(p.getProperty("sellLapis", "true"));
                sellCopper = Boolean.parseBoolean(p.getProperty("sellCopper", "true"));
                mineCopper = Boolean.parseBoolean(p.getProperty("mineCopper", "true"));
                dropJunk = Boolean.parseBoolean(p.getProperty("dropJunk", "true"));
                marketEnabled = Boolean.parseBoolean(p.getProperty("marketEnabled", "false"));
                marketPrice = p.getProperty("marketPrice", "750").trim();
                if (marketPrice.isEmpty()) marketPrice = "750";
            } catch (IOException ignored) { }
        }

        static void save() {
            Properties p = new Properties();
            p.setProperty("mineCommand", mineCommand);
            p.setProperty("mineSeconds", String.valueOf(mineSeconds));
            p.setProperty("sellIron", String.valueOf(sellIron));
            p.setProperty("sellDiamond", String.valueOf(sellDiamond));
            p.setProperty("sellRedstone", String.valueOf(sellRedstone));
            p.setProperty("sellLapis", String.valueOf(sellLapis));
            p.setProperty("sellCopper", String.valueOf(sellCopper));
            p.setProperty("mineCopper", String.valueOf(mineCopper));
            p.setProperty("dropJunk", String.valueOf(dropJunk));
            p.setProperty("marketEnabled", String.valueOf(marketEnabled));
            p.setProperty("marketPrice", marketPrice);
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
        private TextFieldWidget priceField;
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
            int top = height / 2 - 150;

            commandField = new TextFieldWidget(textRenderer, cx - 150, top + 34, 300, 20, Text.literal("Komenda"));
            commandField.setMaxLength(256);
            commandField.setText(Config.mineCommand);
            addDrawableChild(commandField);

            secondsField = new TextFieldWidget(textRenderer, cx - 150, top + 78, 80, 20, Text.literal("Sekundy"));
            secondsField.setMaxLength(5);
            secondsField.setText(String.valueOf(Config.mineSeconds));
            addDrawableChild(secondsField);

            addDrawableChild(ButtonWidget.builder(toggleLabel("Żelazo", Config.sellIron), b -> {
                Config.sellIron = !Config.sellIron;
                b.setMessage(toggleLabel("Żelazo", Config.sellIron));
            }).dimensions(cx - 150, top + 122, 148, 20).build());

            addDrawableChild(ButtonWidget.builder(toggleLabel("Diament", Config.sellDiamond), b -> {
                Config.sellDiamond = !Config.sellDiamond;
                b.setMessage(toggleLabel("Diament", Config.sellDiamond));
            }).dimensions(cx + 2, top + 122, 148, 20).build());

            addDrawableChild(ButtonWidget.builder(toggleLabel("Redstone", Config.sellRedstone), b -> {
                Config.sellRedstone = !Config.sellRedstone;
                b.setMessage(toggleLabel("Redstone", Config.sellRedstone));
            }).dimensions(cx - 150, top + 146, 148, 20).build());

            addDrawableChild(ButtonWidget.builder(toggleLabel("Lapis", Config.sellLapis), b -> {
                Config.sellLapis = !Config.sellLapis;
                b.setMessage(toggleLabel("Lapis", Config.sellLapis));
            }).dimensions(cx + 2, top + 146, 148, 20).build());

            addDrawableChild(ButtonWidget.builder(toggleLabel("Sprzedaż miedzi", Config.sellCopper), b -> {
                Config.sellCopper = !Config.sellCopper;
                b.setMessage(toggleLabel("Sprzedaż miedzi", Config.sellCopper));
            }).dimensions(cx - 150, top + 170, 148, 20).build());

            addDrawableChild(ButtonWidget.builder(toggleLabel("Kopanie miedzi", Config.mineCopper), b -> {
                Config.mineCopper = !Config.mineCopper;
                b.setMessage(toggleLabel("Kopanie miedzi", Config.mineCopper));
            }).dimensions(cx + 2, top + 170, 148, 20).build());

            addDrawableChild(ButtonWidget.builder(toggleLabel("Wystawiaj bloki redstone", Config.marketEnabled), b -> {
                Config.marketEnabled = !Config.marketEnabled;
                b.setMessage(toggleLabel("Wystawiaj bloki redstone", Config.marketEnabled));
            }).dimensions(cx - 150, top + 196, 300, 20).build());

            priceField = new TextFieldWidget(textRenderer, cx - 60, top + 220, 210, 20, Text.literal("Kwota"));
            priceField.setMaxLength(12);
            priceField.setText(Config.marketPrice);
            addDrawableChild(priceField);

            addDrawableChild(ButtonWidget.builder(toggleLabel("Wyrzucaj śmieci", Config.dropJunk), b -> {
                Config.dropJunk = !Config.dropJunk;
                b.setMessage(toggleLabel("Wyrzucaj śmieci", Config.dropJunk));
            }).dimensions(cx - 150, top + 246, 300, 20).build());

            startButton = addDrawableChild(ButtonWidget.builder(startLabel(), b -> {
                applySettings();
                MinecraftClient c = MinecraftClient.getInstance();
                if (mod.enabled) {
                    mod.stop(c);
                    b.setMessage(startLabel());
                } else {
                    mod.start(c);
                    close();
                }
            }).dimensions(cx - 150, top + 274, 148, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Zamknij"), b -> close())
                    .dimensions(cx + 2, top + 274, 148, 20).build());
        }

        private void applySettings() {
            String cmd = commandField.getText().trim();
            if (!cmd.isEmpty()) Config.mineCommand = cmd;
            try {
                Config.mineSeconds = Math.max(10, Integer.parseInt(secondsField.getText().trim()));
            } catch (NumberFormatException ignored) { }
            Config.marketPrice = priceField.getText().trim();
            Config.save();
        }

        @Override
        public void removed() {
            applySettings();
        }

        @Override
        public boolean shouldPause() {
            return false;
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            int cx = width / 2;
            int top = height / 2 - 150;
            ctx.drawCenteredTextWithShadow(textRenderer, getTitle(), cx, top, 0xFFFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Komenda kopania (Baritone):"), cx - 150, top + 22, 0xFFAAAAAA);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Czas kopania (sekundy):"), cx - 150, top + 66, 0xFFAAAAAA);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Co sprzedawać:"), cx - 150, top + 110, 0xFFAAAAAA);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Kwota /wystaw:"), cx - 150, top + 226, 0xFFAAAAAA);
            String status = mod.enabled ? "DZIAŁA (" + mod.stage + ")" : "WYŁĄCZONY";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Status: " + status), cx, top + 302, 0xFFFFFFFF);
        }
    }

    @Override
    public void onInitializeClient() {
        Config.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autosklep.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "key.categories.misc"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    private void start(MinecraftClient client) {
        if (client.player == null) return;
        enabled = true;
        waitTicks = 0;
        tell(client, "AutoSklep: WŁĄCZONY");
        startMining(client);
    }

    private void stop(MinecraftClient client) {
        enabled = false;
        stage = Stage.IDLE;
        sendChat(client, STOP_COMMAND);
        tell(client, "AutoSklep: WYŁĄCZONY");
    }

    private void tick(MinecraftClient client) {
        while (openKey.wasPressed()) {
            if (client.player != null) client.setScreen(new ConfigScreen(this));
        }

        if (!enabled || client.player == null || client.world == null) return;

        if (stage == Stage.MINING) {
            if (++tickCounter >= Config.mineSeconds * 20) {
                tickCounter = 0;
                sendChat(client, STOP_COMMAND);
                tell(client, "AutoSklep: koniec kopania, za 5 s wpisuję /sklep");
                stage = Stage.PRE_SHOP;
                waitTicks = COOLDOWN_TICKS;
            }
            return;
        }

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        if (stage == Stage.PRE_SHOP) {
            tell(client, "AutoSklep: wpisuję /sklep");
            client.player.networkHandler.sendChatCommand("sklep");
            stage = Stage.WAIT_FIRST_MENU;
            retries = 0;
            waitTicks = 40;
            return;
        }

        if (stage == Stage.DROP_JUNK) {
            dropStep(client);
            return;
        }

        if (stage == Stage.MARKET_START) { marketStart(client); return; }
        if (stage == Stage.MARKET_CLOSE) {
            client.player.closeHandledScreen();
            marketAttempts = 0;
            stage = Stage.MARKET_LIST;
            waitTicks = 10;
            return;
        }
        if (stage == Stage.MARKET_LIST) { marketList(client); return; }
        if (stage == Stage.MARKET_LIST_SEND) { marketListSend(client); return; }
        if (stage == Stage.MARKET_RESTORE) { marketRestore(client); return; }

        if (client.currentScreen == null || client.player.currentScreenHandler == client.player.playerScreenHandler) {
            if (stage == Stage.WAIT_FIRST_MENU) {
                if (++retries > 30) abort(client, "Menu /sklep nie otworzyło się. Automat zatrzymany.");
                else waitTicks = 10;
            } else if (stage == Stage.MARKET_WAIT_WB) {
                if (++retries > 30) abort(client, "Stół po /wb nie otworzył się. Automat zatrzymany.");
                else waitTicks = 10;
            } else {
                abort(client, "Nie znaleziono oczekiwanego menu. Automat zatrzymany.");
            }
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
                if (Config.sellIron) {
                    shiftRightClick(client, IRON_SLOT);
                    waitTicks = 4;
                }
                stage = Stage.SELL_DIAMOND;
            }
            case SELL_DIAMOND -> {
                if (Config.sellDiamond) {
                    shiftRightClick(client, DIAMOND_SLOT);
                    waitTicks = 4;
                }
                stage = Stage.SELL_REDSTONE;
            }
            case SELL_REDSTONE -> {
                if (Config.sellRedstone && !Config.marketEnabled) {
                    shiftRightClick(client, REDSTONE_SLOT);
                    waitTicks = 4;
                }
                stage = Stage.SELL_LAPIS;
            }
            case SELL_LAPIS -> {
                if (Config.sellLapis) {
                    shiftRightClick(client, LAPIS_SLOT);
                    waitTicks = 4;
                }
                stage = Stage.SELL_COPPER;
            }
            case SELL_COPPER -> {
                if (Config.sellCopper) {
                    shiftRightClick(client, COPPER_SLOT);
                }
                stage = Stage.FINISH;
                waitTicks = 10;
            }
            case FINISH -> {
                client.player.closeHandledScreen();
                tell(client, "AutoSklep: sprzedaż zakończona.");
                if (Config.marketEnabled) {
                    stage = Stage.MARKET_START;
                    waitTicks = 10;
                } else {
                    afterSale(client);
                }
            }
            case MARKET_WAIT_WB -> {
                if (!(client.player.currentScreenHandler instanceof CraftingScreenHandler)) {
                    abort(client, "Po /wb nie otworzył się stół rzemieślniczy. Automat zatrzymany.");
                } else {
                    stage = Stage.MARKET_FILL;
                    waitTicks = 4;
                }
            }
            case MARKET_FILL -> marketFill(client);
            case MARKET_RETURN -> {
                // oddaj reszte z kursora na dawne miejsce, zanim wezmiemy wynik
                if (marketSlot >= 0) click(client, marketSlot, 0, SlotActionType.PICKUP);
                stage = Stage.MARKET_TAKE;
                waitTicks = 4;
            }
            case MARKET_TAKE -> {
                click(client, 0, 0, SlotActionType.QUICK_MOVE);
                stage = Stage.MARKET_FILL;
                waitTicks = 6;
            }
            default -> { }
        }
    }

    /** Po sprzedazy (i ewentualnym rynku): wyrzuc smieci albo od razu wracaj do kopania. */
    private void afterSale(MinecraftClient client) {
        if (Config.dropJunk) {
            dropIndex = INV_FIRST;
            // obroc sie tylem i patrz lekko w gore, zeby rzeczy poleciały daleko za plecy
            client.player.setYaw(client.player.getYaw() + 180f);
            client.player.setPitch(-25f);
            stage = Stage.DROP_JUNK;
            waitTicks = 10;
        } else {
            startMining(client);
        }
    }

    private static boolean priceOk() {
        return Config.marketPrice.matches("[0-9]+(\\.[0-9]+)?");
    }

    private static int countItem(MinecraftClient client, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = client.player.getInventory().getStack(i);
            if (st.isOf(item)) n += st.getCount();
        }
        return n;
    }

    private void marketStart(MinecraftClient client) {
        if (!priceOk()) {
            tell(client, "AutoSklep: ustaw kwotę rynku w oknie (K) – pomijam wystawianie.");
            afterSale(client);
            return;
        }
        marketFills = 0;
        marketAttempts = 0;
        if (countItem(client, Items.REDSTONE) < 9) {
            // nie ma z czego skladac, ale moga lezec gotowe bloki do wystawienia
            stage = Stage.MARKET_LIST;
            waitTicks = 4;
            return;
        }
        tell(client, "AutoSklep: wpisuję /wb");
        client.player.networkHandler.sendChatCommand("wb");
        stage = Stage.MARKET_WAIT_WB;
        retries = 0;
        waitTicks = 20;
    }

    /** Jeden obieg skladania: stos redstone na kursor, rozlozenie rowno na siatke 3x3. */
    private void marketFill(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        ScreenHandler h = client.player.currentScreenHandler;
        int found = -1;
        for (int i = 10; i < h.slots.size(); i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isOf(Items.REDSTONE) && st.getCount() >= 9) { found = i; break; }
        }
        if (found < 0 || ++marketFills > 12) {
            stage = Stage.MARKET_CLOSE;
            waitTicks = 4;
            return;
        }
        marketSlot = found;
        int id = h.syncId;
        var im = client.interactionManager;
        im.clickSlot(id, found, 0, SlotActionType.PICKUP, client.player);
        im.clickSlot(id, -999, 0, SlotActionType.QUICK_CRAFT, client.player);
        for (int g = 1; g <= 9; g++) {
            im.clickSlot(id, g, 1, SlotActionType.QUICK_CRAFT, client.player);
        }
        im.clickSlot(id, -999, 2, SlotActionType.QUICK_CRAFT, client.player);
        stage = Stage.MARKET_RETURN;
        waitTicks = 4;
    }

    /** Wybiera pelny stos 64 blokow redstone do reki (bez ruszania paska, jesli juz tam jest). */
    private void marketList(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        if (marketAttempts >= 8) {
            tell(client, "AutoSklep: za dużo prób wystawienia, kończę.");
            afterSale(client);
            return;
        }
        var inv = client.player.getInventory();
        int found = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            // wystawiamy dopiero pelny stos 64 blokow
            if (st.isOf(Items.REDSTONE_BLOCK) && st.getCount() >= 64) { found = i; break; }
        }
        if (found < 0) {
            if (marketAttempts == 0) {
                tell(client, "AutoSklep: bloki redstone: " + countItem(client, Items.REDSTONE_BLOCK)
                        + "/64 – czekam na pełny stos.");
            }
            afterSale(client);
            return;
        }
        marketOrigSel = inv.selectedSlot;
        if (found < 9) {
            marketSwapSlot = -1;
            inv.selectedSlot = found;
        } else {
            marketSwapSlot = found;
            client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId,
                    found, marketOrigSel, SlotActionType.SWAP, client.player);
        }
        stage = Stage.MARKET_LIST_SEND;
        waitTicks = 6;
    }

    private void marketListSend(MinecraftClient client) {
        marketAttempts++;
        ItemStack hand = client.player.getMainHandStack();
        if (hand.isOf(Items.REDSTONE_BLOCK)) {
            String cmd = "wystaw " + hand.getCount() + " " + Config.marketPrice;
            tell(client, "AutoSklep: /" + cmd);
            client.player.networkHandler.sendChatCommand(cmd);
            waitTicks = 30;
        } else {
            waitTicks = 2;
        }
        stage = Stage.MARKET_RESTORE;
    }

    /** Zamienia z powrotem, zeby kilof wrocil na pasek (Baritone bierze narzedzia tylko z paska). */
    private void marketRestore(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        if (marketSwapSlot >= 0) {
            client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId,
                    marketSwapSlot, marketOrigSel, SlotActionType.SWAP, client.player);
        }
        client.player.getInventory().selectedSlot = marketOrigSel;
        marketSwapSlot = -1;
        stage = Stage.MARKET_LIST;
        waitTicks = 6;
    }

    private static boolean shouldKeep(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        return path.endsWith("_pickaxe") || KEEP.contains(path);
    }

    private void dropStep(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        ScreenHandler h = client.player.playerScreenHandler;

        while (dropIndex <= INV_LAST) {
            ItemStack stack = h.getSlot(dropIndex).getStack();
            if (!stack.isEmpty() && !shouldKeep(stack)) break;
            dropIndex++;
        }

        if (dropIndex > INV_LAST) {
            tell(client, "AutoSklep: śmieci wyrzucone, wracam do kopania.");
            startMining(client);
            return;
        }

        // THROW z przyciskiem 1 = wyrzucenie calego stosu (jak Ctrl+Q)
        client.interactionManager.clickSlot(h.syncId, dropIndex, 1, SlotActionType.THROW, client.player);
        dropIndex++;
        waitTicks = 0; // jeden stos na tick, zeby skonczyc przed koncem 2 s blokady podnoszenia
    }

    private void startMining(MinecraftClient client) {
        sendChat(client, Config.effectiveMineCommand());
        stage = Stage.MINING;
        tickCounter = 0;
    }

    private void sendChat(MinecraftClient client, String message) {
        if (client.player == null) return;
        if (message.startsWith("/")) client.player.networkHandler.sendChatCommand(message.substring(1));
        else client.player.networkHandler.sendChatMessage(message);
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
        tell(client, "AutoSklep: " + message + " Otwórz okno (K) i kliknij START.");
    }

    private void tell(MinecraftClient client, String message) {
        if (client.player != null) client.player.sendMessage(Text.literal(message), false);
    }
}
