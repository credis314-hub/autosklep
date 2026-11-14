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
 * Kompas 21, czerwona welna 23, zelazo 14, diament 15, redstone 16, lapis 25, miedz 12, wegiel 11.
 */
public class AutoSklepClient implements ClientModInitializer {
    private static final String STOP_COMMAND = "#stop";
    private static final int COOLDOWN_TICKS = 20 * 5;

    private static final int COMPASS_SLOT = 21;
    private static final int WOOL_SLOT = 23;

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
            "coal", "coal_ore", "deepslate_coal_ore",
            "raw_gold", "gold_ingot", "gold_ore", "deepslate_gold_ore",
            "dripstone_block", "pointed_dripstone",
            "beef", "cooked_beef", "porkchop", "cooked_porkchop",
            "mutton", "cooked_mutton", "chicken", "cooked_chicken",
            "rabbit", "cooked_rabbit"
    );

    static final int MODE_OFF = 0;
    static final int MODE_SHOP = 1;
    static final int MODE_MARKET = 2;

    /** Surowiec: rudy dla Baritone, sprzedaz w sklepie albo wystawianie stosow 64 na rynek. */
    static final class Res {
        final String key;
        final String label;
        final String[] ores;
        final Item[] shopItems;
        final int fallbackSlot;
        final Item listItem;
        boolean mine = true;
        int sellMode = MODE_SHOP;
        String price;

        Res(String key, String label, String[] ores, Item[] shopItems, int fallbackSlot,
            Item listItem, String price) {
            this.key = key;
            this.label = label;
            this.ores = ores;
            this.shopItems = shopItems;
            this.fallbackSlot = fallbackSlot;
            this.listItem = listItem;
            this.price = price;
        }

        /** sklep -> rynek -> wylaczone -> sklep ... */
        void cycleMode() {
            sellMode = (sellMode + 1) % 3;
        }

        String modeText() {
            if (sellMode == MODE_SHOP) return "Sklep";
            if (sellMode == MODE_MARKET) return "Rynek";
            return "Wył.";
        }
    }

    static final Res[] RES = {
            new Res("iron", "Żelazo",
                    new String[]{"minecraft:iron_ore", "minecraft:deepslate_iron_ore"},
                    new Item[]{Items.RAW_IRON}, 14, Items.RAW_IRON, ""),
            new Res("diamond", "Diament",
                    new String[]{"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"},
                    new Item[]{Items.DIAMOND}, 15, Items.DIAMOND, ""),
            new Res("redstone", "Redstone",
                    new String[]{"minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"},
                    new Item[]{Items.REDSTONE}, 16, Items.REDSTONE, "750"),
            new Res("lapis", "Lapis",
                    new String[]{"minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"},
                    new Item[]{Items.LAPIS_LAZULI}, 25, Items.LAPIS_LAZULI, ""),
            new Res("copper", "Miedź",
                    new String[]{"minecraft:copper_ore", "minecraft:deepslate_copper_ore"},
                    new Item[]{Items.RAW_COPPER}, 12, Items.RAW_COPPER, ""),
            new Res("coal", "Węgiel",
                    new String[]{"minecraft:coal_ore", "minecraft:deepslate_coal_ore"},
                    new Item[]{Items.COAL}, 11, Items.COAL, ""),
            new Res("gold", "Złoto",
                    new String[]{"minecraft:gold_ore", "minecraft:deepslate_gold_ore"},
                    new Item[]{Items.RAW_GOLD}, 13, Items.RAW_GOLD, ""),
            new Res("dripstone", "Dripstone",
                    new String[]{"minecraft:dripstone_block", "minecraft:pointed_dripstone"},
                    new Item[]{Items.DRIPSTONE_BLOCK, Items.POINTED_DRIPSTONE}, -1,
                    Items.DRIPSTONE_BLOCK, "")
    };

    private KeyBinding openKey;
    private boolean enabled = false;
    private int tickCounter = 0;
    private int waitTicks = 0;
    private int retries = 0;
    private int dropIndex = INV_FIRST;
    private int sellIndex = 0;
    private int marketResIndex = -1;
    private Res marketRes = null;
    private int marketAttempts = 0;
    private int marketSwapSlot = -1;
    private int marketOrigSel = 0;
    private Stage stage = Stage.IDLE;

    private enum Stage {
        IDLE, MINING, PRE_SHOP, WAIT_FIRST_MENU, WAIT_SECOND_MENU, WAIT_SELL_MENU,
        SELL_NEXT, FINISH, DROP_JUNK,
        MARKET_NEXT,
        MARKET_LIST, MARKET_LIST_SEND, MARKET_RESTORE
    }

    /** Ustawienia zapisywane w config/autosklep.properties */
    static final class Config {
        static String mineExtra = "";
        static int mineSeconds = 60;
        static boolean dropJunk = true;

        /** Komenda faktycznie wysylana do Baritone, zlozona z wlaczonych surowcow. */
        static String effectiveMineCommand() {
            StringBuilder sb = new StringBuilder("#mine");
            for (Res r : RES) {
                if (!r.mine) continue;
                for (String ore : r.ores) sb.append(' ').append(ore);
            }
            if (!mineExtra.isBlank()) sb.append(' ').append(mineExtra.trim());
            return sb.toString();
        }

        private static Path file() {
            return FabricLoader.getInstance().getConfigDir().resolve("autosklep.properties");
        }

        private static boolean flag(Properties p, String key, String oldKey, boolean def) {
            String v = p.getProperty(key);
            if (v == null && oldKey != null) v = p.getProperty(oldKey);
            return v == null ? def : Boolean.parseBoolean(v);
        }

        static void load() {
            Path f = file();
            if (!Files.exists(f)) return;
            try (InputStream in = Files.newInputStream(f)) {
                Properties p = new Properties();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                mineExtra = p.getProperty("mineExtra", "").trim();
                try {
                    mineSeconds = Math.max(10, Integer.parseInt(p.getProperty("mineSeconds", String.valueOf(mineSeconds))));
                } catch (NumberFormatException ignored) { }
                dropJunk = Boolean.parseBoolean(p.getProperty("dropJunk", "true"));
                boolean oldMarket = Boolean.parseBoolean(p.getProperty("marketEnabled", "false"));
                for (Res r : RES) {
                    String cap = Character.toUpperCase(r.key.charAt(0)) + r.key.substring(1);
                    r.mine = flag(p, "res." + r.key + ".mine", "mine" + cap, true);
                    String m = p.getProperty("res." + r.key + ".mode");
                    if (m != null) {
                        try {
                            r.sellMode = Math.max(0, Math.min(2, Integer.parseInt(m.trim())));
                        } catch (NumberFormatException ignored) { }
                    } else if (!flag(p, "res." + r.key + ".sell", "sell" + cap, true)) {
                        r.sellMode = MODE_OFF;
                    } else if (r.key.equals("redstone") && oldMarket) {
                        r.sellMode = MODE_MARKET;
                    }
                    String pr = p.getProperty("res." + r.key + ".price");
                    if (pr != null) {
                        r.price = pr.trim();
                    } else if (r.key.equals("redstone") && p.getProperty("marketPrice") != null) {
                        r.price = p.getProperty("marketPrice").trim();
                    }
                }
            } catch (IOException ignored) { }
        }

        static void save() {
            Properties p = new Properties();
            p.setProperty("mineExtra", mineExtra);
            p.setProperty("mineSeconds", String.valueOf(mineSeconds));
            p.setProperty("dropJunk", String.valueOf(dropJunk));
            for (Res r : RES) {
                p.setProperty("res." + r.key + ".mine", String.valueOf(r.mine));
                p.setProperty("res." + r.key + ".mode", String.valueOf(r.sellMode));
                p.setProperty("res." + r.key + ".price", r.price);
            }
            try (OutputStream out = Files.newOutputStream(file())) {
                p.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "AutoSklep");
            } catch (IOException ignored) { }
        }
    }

    /** Okno ustawien otwierane klawiszem K: jeden wiersz na surowiec. */
    static final class ConfigScreen extends Screen {
        private final AutoSklepClient mod;
        private TextFieldWidget extraField;
        private TextFieldWidget secondsField;
        private final TextFieldWidget[] priceFields = new TextFieldWidget[RES.length];
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
            int x0 = cx - 150;
            int top = height / 2 - 165;

            secondsField = new TextFieldWidget(textRenderer, x0 + 130, top + 16, 60, 20, Text.literal("Sekundy"));
            secondsField.setMaxLength(5);
            secondsField.setText(String.valueOf(Config.mineSeconds));
            addDrawableChild(secondsField);

            extraField = new TextFieldWidget(textRenderer, x0 + 100, top + 40, 200, 20, Text.literal("Dodatkowe rudy"));
            extraField.setMaxLength(256);
            extraField.setText(Config.mineExtra);
            addDrawableChild(extraField);

            for (int i = 0; i < RES.length; i++) {
                final Res r = RES[i];
                int y = top + 84 + i * 22;

                addDrawableChild(ButtonWidget.builder(toggleLabel("Kop.", r.mine), b -> {
                    r.mine = !r.mine;
                    b.setMessage(toggleLabel("Kop.", r.mine));
                }).dimensions(x0 + 56, y, 64, 20).build());

                addDrawableChild(ButtonWidget.builder(Text.literal("Sprz.: " + r.modeText()), b -> {
                    r.cycleMode();
                    b.setMessage(Text.literal("Sprz.: " + r.modeText()));
                }).dimensions(x0 + 124, y, 84, 20).build());

                TextFieldWidget pf = new TextFieldWidget(textRenderer, x0 + 212, y, 88, 20, Text.literal("Cena"));
                pf.setMaxLength(10);
                pf.setText(r.price);
                priceFields[i] = pf;
                addDrawableChild(pf);
            }

            addDrawableChild(ButtonWidget.builder(toggleLabel("Wyrzucaj śmieci", Config.dropJunk), b -> {
                Config.dropJunk = !Config.dropJunk;
                b.setMessage(toggleLabel("Wyrzucaj śmieci", Config.dropJunk));
            }).dimensions(x0, top + 84 + RES.length * 22 + 4, 300, 20).build());

            int yb = top + 84 + RES.length * 22 + 30;
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
            }).dimensions(x0, yb, 148, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Zamknij"), b -> close())
                    .dimensions(x0 + 152, yb, 148, 20).build());
        }

        private void applySettings() {
            Config.mineExtra = extraField.getText().trim();
            try {
                Config.mineSeconds = Math.max(10, Integer.parseInt(secondsField.getText().trim()));
            } catch (NumberFormatException ignored) { }
            for (int i = 0; i < RES.length; i++) {
                RES[i].price = priceFields[i].getText().trim();
            }
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
            int x0 = cx - 150;
            int top = height / 2 - 165;
            int gray = 0xFFAAAAAA;
            ctx.drawCenteredTextWithShadow(textRenderer, getTitle(), cx, top, 0xFFFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Czas kopania (s):"), x0, top + 22, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Dodatkowe rudy:"), x0, top + 46, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Surowiec"), x0, top + 72, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Kopanie"), x0 + 60, top + 72, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Sprzedaż"), x0 + 128, top + 72, gray);
            ctx.drawTextWithShadow(textRenderer, Text.literal("/wystaw 64 ..."), x0 + 214, top + 72, gray);
            for (int i = 0; i < RES.length; i++) {
                ctx.drawTextWithShadow(textRenderer, Text.literal(RES[i].label), x0, top + 84 + i * 22 + 6, 0xFFFFFFFF);
            }
            String status = mod.enabled ? "DZIAŁA (" + mod.stage + ")" : "WYŁĄCZONY";
            int ys = top + 84 + RES.length * 22 + 56;
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Status: " + status), cx, ys, 0xFFFFFFFF);
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

        if (stage == Stage.MARKET_NEXT) { marketNext(client); return; }
        if (stage == Stage.MARKET_LIST) { marketList(client); return; }
        if (stage == Stage.MARKET_LIST_SEND) { marketListSend(client); return; }
        if (stage == Stage.MARKET_RESTORE) { marketRestore(client); return; }

        if (client.currentScreen == null || client.player.currentScreenHandler == client.player.playerScreenHandler) {
            if (stage == Stage.WAIT_FIRST_MENU) {
                if (++retries > 30) abort(client, "Menu /sklep nie otworzyło się. Automat zatrzymany.");
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
                stage = Stage.SELL_NEXT;
                sellIndex = 0;
                waitTicks = 4;
            }
            case SELL_NEXT -> {
                while (sellIndex < RES.length && !wantsSell(RES[sellIndex])) sellIndex++;
                if (sellIndex >= RES.length) {
                    stage = Stage.FINISH;
                    waitTicks = 10;
                } else {
                    sellResource(client, RES[sellIndex]);
                    sellIndex++;
                    waitTicks = 4;
                }
            }
            case FINISH -> {
                client.player.closeHandledScreen();
                tell(client, "AutoSklep: sprzedaż zakończona.");
                if (anyMarket()) {
                    marketResIndex = -1;
                    stage = Stage.MARKET_NEXT;
                    waitTicks = 10;
                } else {
                    afterSale(client);
                }
            }
            default -> { }
        }
    }

    /** Redstone nie idzie do sklepu, gdy jest skladana na bloki dla rynku. */
    private static boolean wantsSell(Res r) {
        return r.sellMode == MODE_SHOP;
    }

    /** Szuka w menu sklepu (bez ekwipunku gracza) slotu z danym przedmiotem. */
    private static int findShopSlot(ScreenHandler h, Item item) {
        int container = h.slots.size() - 36;
        for (int i = 0; i < container; i++) {
            if (h.getSlot(i).getStack().isOf(item)) return i;
        }
        return -1;
    }

    private void sellResource(MinecraftClient client, Res r) {
        ScreenHandler h = client.player.currentScreenHandler;
        boolean any = false;
        for (Item it : r.shopItems) {
            int slot = findShopSlot(h, it);
            if (slot >= 0) {
                shiftRightClick(client, slot);
                any = true;
            }
        }
        if (!any && r.fallbackSlot >= 0) {
            shiftRightClick(client, r.fallbackSlot);
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

    private static boolean priceOk(String price) {
        return price != null && price.matches("[0-9]+(\\.[0-9]+)?");
    }

    private static boolean anyMarket() {
        for (Res r : RES) {
            if (r.sellMode == MODE_MARKET) return true;
        }
        return false;
    }

    private static int countItem(MinecraftClient client, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = client.player.getInventory().getStack(i);
            if (st.isOf(item)) n += st.getCount();
        }
        return n;
    }

    /** Przechodzi do nastepnego surowca w trybie Rynek. */
    private void marketNext(MinecraftClient client) {
        marketResIndex++;
        while (marketResIndex < RES.length && RES[marketResIndex].sellMode != MODE_MARKET) marketResIndex++;
        if (marketResIndex >= RES.length) {
            afterSale(client);
            return;
        }
        marketRes = RES[marketResIndex];
        if (!priceOk(marketRes.price)) {
            tell(client, "AutoSklep: ustaw cenę dla: " + marketRes.label + " (okno K) – pomijam rynek.");
            stage = Stage.MARKET_NEXT;
            waitTicks = 2;
            return;
        }
        marketAttempts = 0;
        stage = Stage.MARKET_LIST;
        waitTicks = 4;
    }

    /** Wybiera pelny stos 64 sztuk biezacego surowca do reki (bez ruszania paska, jesli juz tam jest). */
    private void marketList(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;
        if (marketAttempts >= 8) {
            tell(client, "AutoSklep: za dużo prób wystawienia (" + marketRes.label + "), idę dalej.");
            stage = Stage.MARKET_NEXT;
            waitTicks = 2;
            return;
        }
        var inv = client.player.getInventory();
        int found = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            // wystawiamy dopiero pelny stos 64 sztuk
            if (st.isOf(marketRes.listItem) && st.getCount() >= 64) { found = i; break; }
        }
        if (found < 0) {
            if (marketAttempts == 0) {
                tell(client, "AutoSklep: " + marketRes.label + ": " + countItem(client, marketRes.listItem)
                        + "/64 – czekam na pełny stos.");
            }
            stage = Stage.MARKET_NEXT;
            waitTicks = 2;
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
        if (hand.isOf(marketRes.listItem)) {
            String cmd = "wystaw " + hand.getCount() + " " + marketRes.price;
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
        String cmd = Config.effectiveMineCommand();
        if (cmd.equals("#mine")) {
            abort(client, "Nie wybrano żadnej rudy do kopania.");
            return;
        }
        sendChat(client, cmd);
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
