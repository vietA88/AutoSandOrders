package autosand;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Predicate;

/**
 * AutoSandOrders (Fabric 1.21.11, Mojang mappings)
 *
 * 1) Đào cát (Baritone / giữ phím / chỉ Nuker) -> đầy túi -> /orders -> giao cát vào đơn đầu tiên -> xác nhận
 *    -> thoát các bảng server mở lại (thêm FINISH_CLOSES lần) -> đào tiếp.
 *    Đơn đầu không nhận cát (đầy, không mở được bảng giao...) -> mở lại /orders thử các đơn cát kế tiếp.
 * 2) Xẻng gần hư -> bình XP vào ô 2 thanh công cụ, xẻng vào tay trái, tay phải ném bình -> xẻng về lại.
 * 3) Hết bình XP -> vứt cát đang nằm trên thanh công cụ -> /shop -> ô totem -> bình XP -> đặt 64 -> xác nhận,
 *    lặp lại cho tới khi thanh công cụ đầy bình XP.
 * 4) Đói (thanh đói <= mức chỉnh trong GUI) -> dừng đào, cầm đồ ăn + giữ chuột phải tới khi no -> đào tiếp.
 * 5) Hết đồ ăn -> /shop -> FOOD -> Cà Rốt Vàng -> đặt 64 -> xác nhận -> ăn.
 * 6) Vứt mọi vật phẩm trong túi (không tính thanh công cụ) KHÔNG phải: cát, bình XP, xẻng, đồ ăn (xem keepItem()).
 *
 * 7) An toàn: né nước / lava / hố phía trước; rơi xuống nước thì tự bơi lên bờ rồi đào tiếp; kẹt trong hố thì
 *    nhảy + đặt cát dưới chân để leo lên (xem phần "AN TOÀN" bên dưới).
 *
 * 8) Ưu tiên nhặt cát: thấy cát rơi trong bán kính chỉnh được -> đi tới nhặt rồi đào tiếp.
 * 9) Tự bật lại: Baritone / Nuker tự dừng, lỗi, hoặc chết rồi hồi sinh -> tự gửi lại lệnh đào.
 *
 * 10) Chế độ "Tự đào" (mặc định, KHÔNG cần Baritone): xem AutoMiner.java. Tự tìm cát, tìm đường A*, đào xuyên
 *     cát / đất chắn đường, nhặt cát rơi, thăm dò khi hết cát. Chết -> ghi nhớ toạ độ -> tự hồi sinh -> /home cat
 *     -> đi về chỗ chết nhặt đồ -> xẻng về thanh công cụ, cát về túi -> đào tiếp. Đổi lệnh: /autosand home <lệnh>
 *
 * Key:    50 key ngắn (8 ký tự) vĩnh viễn có sẵn trong mod (xem class License). /autosand key <key> | status
 *
 * Lệnh:  /autosand            mở bảng GUI tuỳ chỉnh
 *        /autosand toggle     bật / tắt nhanh
 *        /autosand cmd <lệnh> đổi lệnh mở orders (không có dấu /)
 *        /autosand shop <lệnh> đổi lệnh mở shop (không có dấu /)
 */
public class AutoSandOrders implements ClientModInitializer {

    // ===================== HẰNG SỐ (sửa ở đây nếu cần) =====================
    static final String BARITONE_MINE = "#mine sand";
    static final String BARITONE_STOP = "#stop";
    static final String NUKER_ON = ".toggle nuker on";
    static final String NUKER_OFF = ".toggle nuker off";

    static final int ACTION_DELAY = 4;      // tick chờ giữa các thao tác (20 tick = 1 giây)
    static final int THROW_DELAY = 3;       // tick giữa mỗi lần ném bình XP
    static final int STEP_TIMEOUT = 200;    // quá số tick này mà 1 bước chưa xong -> huỷ
    static final int COOLDOWN_AFTER = 60;

    static final int FINISH_CLOSES = 2;     // sau khi giao cát xong, thoát bảng (ESC) ít nhất bấy nhiêu lần
    static final int BUY_MAX_ROUNDS = 9;    // tối đa số lần mua (mỗi lần 64 bình) khi lấp đầy thanh công cụ
    static final int BUY_RETRIES = 2;       // bảng mua bị đóng / quá hạn -> mở lại /shop thử lại tối đa bấy nhiêu lần
    static final int ORDER_MAX_TRIES = 15;  // đơn không nhận cát -> thử đơn cát kế tiếp, tối đa bấy nhiêu đơn
    static final int ORDER_OPEN_TIMEOUT = 60; // tick chờ bảng giao hàng mở ra sau khi bấm vào đơn (60 = 3 giây)

    static final int CONFIRM_SLOT = 16;     // màn xác nhận giao cát (dự phòng nếu không tìm thấy theo tên)
    static final int BOTTLE_SLOT = 1;       // ô số 2 trên thanh công cụ (đếm từ 0)
    static final int MEND_TARGET_PCT = 100; // sửa xẻng tới bao nhiêu % (100 = đầy). Hạ xuống để tiết kiệm bình

    static final int SHOP_TOTEM_SLOT = 13;  // /shop: ô totem
    static final int SHOP_BOTTLE_SLOT = 16; // SHOP -> GEAR: ô bình XP
    static final int BUY_SET64_SLOT = 17;   // "đặt thành 64"
    static final int BUY_CONFIRM_SLOT = 23; // "xác nhận"
    static final int SHOP_FOOD_SLOT = 14;       // /shop: ô danh mục FOOD (dự phòng nếu không tìm thấy theo tên)
    static final int SHOP_FOOD_ITEM_SLOT = 16;  // SHOP -> FOOD: ô Cà Rốt Vàng (dự phòng)
    static final int EAT_TO = 18;           // ăn tới khi thanh đói >= mức này (tối đa 20)
    static final int EAT_SLOT = 8;          // ô thanh công cụ dùng để cầm đồ ăn khi đồ ăn đang nằm trong túi
    static final int JUNK_INTERVAL = 100;   // tick tối thiểu giữa 2 lần dọn rác (100 = 5 giây)
    static final int JUNK_MAX_DROPS = 60;   // tối đa số stack vứt trong 1 lần dọn
    // =======================================================================

    // ===================== CẤU HÌNH (đổi bằng GUI, tự lưu) =====================
    enum Mode {
        BUILTIN("Tự đào"), BARITONE("Baritone"), KEYS("Giữ phím"), NUKER("Chỉ Nuker");
        final String label;
        Mode(String l) { label = l; }
        /** Bỏ qua BARITONE: mod không dùng Baritone nữa (giữ enum để file cấu hình cũ không lỗi). */
        Mode next() {
            Mode m = this;
            do { m = values()[(m.ordinal() + 1) % values().length]; } while (m == BARITONE);
            return m;
        }
    }

    static Mode mode = Mode.BUILTIN;
    static boolean useNuker = true;
    static boolean keysForward = true;
    static boolean autoDeliver = true;
    static boolean autoMend = true;
    static boolean autoBuy = true;
    static int mendPct = 10;
    static boolean autoEat = true;
    static boolean autoBuyFood = true;
    static boolean autoDrop = true;
    static int eatBelow = 14;               // thanh đói (0-20) <= mức này thì ăn
    static int pickupRadius = 8;            // bán kính ưu tiên nhặt cát (0 = tắt)
    static boolean autoRevive = true;       // Baritone / Nuker tự dừng -> tự bật lại
    static final int[] PICKUP_LEVELS = {0, 4, 8, 12};
    static final int[] EAT_LEVELS = {8, 12, 14, 16};
    static String ORDER_COMMAND = "orders sand";
    static String SHOP_COMMAND = "shop";
    static final int[] PCTS = {5, 10, 20, 30};
    // ===========================================================================

    enum S {
        MINING, WAIT_ORDERS, WAIT_DELIVER, DEPOSIT, WAIT_CONFIRM, FINISH,
        MEND_PREP, MEND_THROW, MEND_RESTORE,
        BUY_PREP, BUY_SHOP, BUY_GEAR, BUY_AMOUNT, BUY_CONFIRM, BUY_DONE,
        EAT_PREP, EAT_USE, JUNK
    }

    static S state = S.MINING;
    static boolean enabled = false;
    static boolean openGui = false;
    static int wait = 0, timeout = 0, cooldown = 0, lastSync = -1, clicks = 0;
    static int step = 0, shovelFrom = -1, mendThrows = 0, lastCheck = 0, lastDamage = 0, buyTries = 0;
    static int closes = 0, finishIdle = 0;                       // thoát bảng sau khi giao cát
    static int buyRound = 0, buyTarget = 1, buyBefore = 0, dropStep = 0; // mua đầy thanh công cụ
    static int buyRetry = 0, gone = 0;                           // thử lại khi bảng mua bị đóng
    static int orderTry = 0, orderSlot = -1, ordersWait = 0, sandBefore = 0; // thử lần lượt các đơn cát
    static boolean settle = false;
    static LocalPlayer lastPlayer = null;
    static int reviveIn = 0;
    static int licTick = 0;
    static boolean buyFood = false, foodWarn = false;            // đang mua đồ ăn (thay vì bình XP)
    static int foodBuyFails = 0, junkCool = 0, junkStep = 0, junkDropped = 0, eatPrevSlot = -1;
    static float eatXRot = Float.NaN, junkXRot = Float.NaN;
    static boolean shovelSwapped = false, needBuy = false;
    static float oldXRot = Float.NaN, dropXRot = Float.NaN;

    // ----------------------------- KHỞI TẠO -----------------------------
    @Override
    public void onInitializeClient() {
        load();
        License.init(FabricLoader.getInstance().getConfigDir());
        ClientTickEvents.END_CLIENT_TICK.register(AutoSandOrders::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("autosand")
                .executes(ctx -> {
                    openGui = true;
                    return 1;
                })
                .then(ClientCommandManager.literal("toggle")
                    .executes(ctx -> {
                        setEnabled(Minecraft.getInstance(), !enabled);
                        return 1;
                    }))
                .then(ClientCommandManager.literal("cmd")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String t = StringArgumentType.getString(ctx, "text").trim();
                            if (t.startsWith("/")) t = t.substring(1);
                            ORDER_COMMAND = t;
                            save();
                            ctx.getSource().sendFeedback(Component.literal("[AutoSand] Lệnh orders: /" + ORDER_COMMAND));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("key")
                    .then(ClientCommandManager.argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            activateKey(Minecraft.getInstance(), StringArgumentType.getString(ctx, "key"));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("status")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(Component.literal("[AutoSand] Key: " + License.status()));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("shop")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String t = StringArgumentType.getString(ctx, "text").trim();
                            if (t.startsWith("/")) t = t.substring(1);
                            SHOP_COMMAND = t;
                            save();
                            ctx.getSource().sendFeedback(Component.literal("[AutoSand] Lệnh shop: /" + SHOP_COMMAND));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("home")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String t = StringArgumentType.getString(ctx, "text").trim();
                            if (t.startsWith("/")) t = t.substring(1);
                            AutoMiner.homeCmd = t;
                            save();
                            ctx.getSource().sendFeedback(Component.literal("[AutoSand] Lệnh về home sau khi chết: /" + AutoMiner.homeCmd));
                            return 1;
                        })))));
    }

    // ----------------------------- LƯU / ĐỌC CẤU HÌNH -----------------------------
    static Path cfgFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("autosand.properties");
    }

    static void load() {
        try {
            Path f = cfgFile();
            if (!Files.exists(f)) return;
            Properties pr = new Properties();
            try (var in = Files.newInputStream(f)) { pr.load(in); }
            try { mode = Mode.valueOf(pr.getProperty("mode", "BUILTIN")); } catch (Exception e) { mode = Mode.BUILTIN; }
            if (mode == Mode.BARITONE) mode = Mode.BUILTIN;      // cấu hình cũ dùng Baritone -> chuyển sang tự đào
            useNuker = Boolean.parseBoolean(pr.getProperty("nuker", "true"));
            keysForward = Boolean.parseBoolean(pr.getProperty("keysForward", "true"));
            autoDeliver = Boolean.parseBoolean(pr.getProperty("deliver", "true"));
            autoMend = Boolean.parseBoolean(pr.getProperty("mend", "true"));
            autoBuy = Boolean.parseBoolean(pr.getProperty("buy", "true"));
            mendPct = Integer.parseInt(pr.getProperty("mendPct", "10"));
            autoEat = Boolean.parseBoolean(pr.getProperty("eat", "true"));
            autoBuyFood = Boolean.parseBoolean(pr.getProperty("buyFood", "true"));
            autoDrop = Boolean.parseBoolean(pr.getProperty("drop", "true"));
            eatBelow = Integer.parseInt(pr.getProperty("eatBelow", "14"));
            pickupRadius = Integer.parseInt(pr.getProperty("pickup", "8"));
            autoRevive = Boolean.parseBoolean(pr.getProperty("revive", "true"));
            avoidLiquid = Boolean.parseBoolean(pr.getProperty("avoidLiquid", "true"));
            avoidHole = Boolean.parseBoolean(pr.getProperty("avoidHole", "true"));
            bridgeWater = Boolean.parseBoolean(pr.getProperty("bridge", "true"));
            ORDER_COMMAND = pr.getProperty("orderCmd", ORDER_COMMAND);
            SHOP_COMMAND = pr.getProperty("shopCmd", SHOP_COMMAND);
            AutoMiner.homeCmd = pr.getProperty("homeCmd", AutoMiner.homeCmd);
            AutoMiner.deathRecover = Boolean.parseBoolean(pr.getProperty("deathRecover", "true"));
            AutoMiner.autoRespawn = Boolean.parseBoolean(pr.getProperty("autoRespawn", "true"));
            AutoMiner.digThrough = Boolean.parseBoolean(pr.getProperty("digThrough", "true"));
            AutoMiner.scanRadius = Integer.parseInt(pr.getProperty("scanRadius", "16"));
        } catch (Exception ignored) { }
    }

    static void save() {
        try {
            Properties pr = new Properties();
            pr.setProperty("mode", mode.name());
            pr.setProperty("nuker", String.valueOf(useNuker));
            pr.setProperty("keysForward", String.valueOf(keysForward));
            pr.setProperty("deliver", String.valueOf(autoDeliver));
            pr.setProperty("mend", String.valueOf(autoMend));
            pr.setProperty("buy", String.valueOf(autoBuy));
            pr.setProperty("mendPct", String.valueOf(mendPct));
            pr.setProperty("eat", String.valueOf(autoEat));
            pr.setProperty("buyFood", String.valueOf(autoBuyFood));
            pr.setProperty("drop", String.valueOf(autoDrop));
            pr.setProperty("eatBelow", String.valueOf(eatBelow));
            pr.setProperty("pickup", String.valueOf(pickupRadius));
            pr.setProperty("revive", String.valueOf(autoRevive));
            pr.setProperty("avoidLiquid", String.valueOf(avoidLiquid));
            pr.setProperty("avoidHole", String.valueOf(avoidHole));
            pr.setProperty("bridge", String.valueOf(bridgeWater));
            pr.setProperty("orderCmd", ORDER_COMMAND);
            pr.setProperty("shopCmd", SHOP_COMMAND);
            pr.setProperty("homeCmd", AutoMiner.homeCmd);
            pr.setProperty("deathRecover", String.valueOf(AutoMiner.deathRecover));
            pr.setProperty("autoRespawn", String.valueOf(AutoMiner.autoRespawn));
            pr.setProperty("digThrough", String.valueOf(AutoMiner.digThrough));
            pr.setProperty("scanRadius", String.valueOf(AutoMiner.scanRadius));
            try (var out = Files.newOutputStream(cfgFile())) { pr.store(out, "AutoSand"); }
        } catch (Exception ignored) { }
    }

    // ----------------------------- BẬT / TẮT -----------------------------
    static void setEnabled(Minecraft mc, boolean on) {
        if (on == enabled) return;
        LocalPlayer p = mc.player;
        if (on && !License.valid()) {                    // phải có key còn hạn mới bật được
            if (p != null) needKeyMsg(p);
            return;
        }
        enabled = on;
        state = S.MINING;
        cooldown = 0;
        wait = 0;
        guardReset(mc, p);
        AutoMiner.reset(mc);
        if (p != null) {
            if (on) {
                applyBaritoneSettings(p);
                startMining(p);
            } else {
                if (shovelSwapped && mc.gameMode != null) {
                    swapOffhand(mc, p, shovelFrom);
                    shovelSwapped = false;
                }
                restoreLook(mc, p);
                stopMining(mc, p);
            }
            msg(p, on ? "BẬT" : "TẮT");
        }
    }

    // ----------------------------- ĐIỀU KHIỂN ĐÀO -----------------------------
    static void startMining(LocalPlayer p) {
        if (!License.valid()) return;
        if (mode == Mode.BUILTIN) { AutoMiner.start(p); return; }      // tự đào: không cần Baritone / Nuker
        if (useNuker) send(p, NUKER_ON);
        if (mode == Mode.BARITONE) send(p, BARITONE_MINE);
    }

    /** Cho Baritone tự đi nhặt đồ rơi (cát) và nán lại nhặt lâu hơn tuỳ mức ưu tiên nhặt. */
    static void applyBaritoneSettings(LocalPlayer p) {
        if (mode != Mode.BARITONE || pickupRadius <= 0) return;
        send(p, "#set mineScanDroppedItems true");
        int ms = pickupRadius >= 12 ? 1000 : pickupRadius >= 8 ? 600 : 350;
        send(p, "#set mineDropLoiterDurationMSThanksLouca " + ms);
    }

    static void stopMining(Minecraft mc, LocalPlayer p) {
        if (mode == Mode.BUILTIN) { AutoMiner.stop(mc); return; }
        if (mode == Mode.BARITONE) send(p, BARITONE_STOP);
        if (useNuker) send(p, NUKER_OFF);
        releaseKeys(mc);
    }

    static void releaseKeys(Minecraft mc) {
        mc.options.keyAttack.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyUse.setDown(false);
    }

    // ----------------------------- TICK -----------------------------
    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return;

        if (openGui && mc.screen == null) {
            openGui = false;
            mc.setScreen(new AutoSandScreen());
            return;
        }
        if (!enabled) { lastPlayer = p; return; }

        // Chết rồi hồi sinh / vào lại thế giới: LocalPlayer mới -> reset trạng thái và bật lại lệnh đào
        if (p != lastPlayer) {
            boolean had = lastPlayer != null;
            lastPlayer = p;
            if (had) {
                guard = G.NONE;
                pickTarget = null;
                shovelSwapped = false;
                state = S.MINING;
                wait = 0;
                cooldown = 40;
                reviveIn = 60;
                wdX = Double.NaN;
                if (mode == Mode.BUILTIN) AutoMiner.onRespawn(mc, p);   // chết -> /home -> về chỗ chết nhặt đồ
            }
        }
        if (p.isDeadOrDying()) {
            if (mode == Mode.BUILTIN) AutoMiner.onDead(mc, p);          // ghi nhớ toạ độ chết + tự hồi sinh
            return;
        }
        if (reviveIn > 0 && --reviveIn == 0) { applyBaritoneSettings(p); startMining(p); }

        if (++licTick >= 20) {                           // mỗi ~1 giây kiểm tra lại key
            licTick = 0;
            if (!License.valid()) {
                msg(p, "Key hết hạn hoặc không hợp lệ -> đã tắt AutoSand.");
                needKeyMsg(p);
                setEnabled(mc, false);
                return;
            }
        }
        // Chốt chặn thứ 2, lệch pha với chốt trên: bypass 1 chỗ vẫn bị chặn ở chỗ còn lại.
        if (!License.quickCheck()) { setEnabled(mc, false); return; }

        if (state == S.MINING && mc.screen == null && guardTick(mc, p)) return;   // an toàn: nước / lava / hố

        if (state == S.MINING) {
            if (mc.screen != null) return;
            if (mode == Mode.KEYS) {
                mc.options.keyAttack.setDown(true);
                mc.options.keyUp.setDown(keysForward);
            }
            if (mode == Mode.BUILTIN) {
                AutoMiner.tick(mc, p);                                  // tự đào / tự nhặt / nhặt đồ sau khi chết
                if (AutoMiner.recovering()) return;                     // đang nhặt đồ: không dọn rác, ăn, giao cát
            }
            if (junkCool > 0) junkCool--;
            if (wait > 0) { wait--; return; }
            if (cooldown > 0) { cooldown--; return; }

            // 1) vứt đồ rác (không phải cát / bình XP / xẻng / đồ ăn)
            if (autoDrop && junkCool <= 0 && junkSlot(p) >= 0) {
                startJunk(mc, p);
                return;
            }
            // 2) đói -> ăn; hết đồ ăn -> mua
            if (autoEat && p.getFoodData().getFoodLevel() <= eatBelow) {
                if (findFood(p) >= 0) { foodWarn = false; startEat(mc, p); return; }
                if (autoBuyFood && emptySlot(p) >= 0) { startFoodBuy(mc, p); return; }
                if (!foodWarn) {
                    foodWarn = true;
                    msg(p, autoBuyFood
                        ? "Đói nhưng túi không còn ô trống để mua đồ ăn (giao cát trước)."
                        : "Đói nhưng hết đồ ăn (tự mua đồ ăn đang tắt).");
                }
            }
            // 3) giao cát / sửa xẻng
            if (autoDeliver && isFull(p) && countItem(p, Items.SAND) > 0) {
                startDeliver(mc, p);
            } else if (autoMend && lowShovel(p) >= 0) {
                startMend(mc, p);
            }
            return;
        }

        if (wait > 0) { wait--; return; }
        if (--timeout <= 0) {
            // Mua dở nhưng đã có bình XP (vd. hết tiền ở lần mua sau) -> cứ dùng số bình đang có
            if (state == S.BUY_DONE && boughtAny(p)) {
                finishBuy(mc, p);
                return;
            }
            // Bấm vào đơn mà không mở được bảng giao hàng -> thử đơn kế tiếp
            if (state == S.WAIT_DELIVER) {
                nextOrder(mc, p, "bấm vào mà không mở được bảng giao hàng");
                return;
            }
            boolean buying = state == S.BUY_SHOP || state == S.BUY_GEAR || state == S.BUY_AMOUNT
                || state == S.BUY_CONFIRM || state == S.BUY_DONE;
            if (buying && buyRetry < BUY_RETRIES) {
                retryBuy(mc, p, "hết thời gian chờ ở bước " + state);
                return;
            }
            abort(mc, "hết thời gian chờ ở bước " + state);
            return;
        }

        ChestMenu h = container(mc);

        switch (state) {

            // ================= GIAO CÁT VÀO ORDERS =================
            case WAIT_ORDERS -> {
                if (h == null || h.getRowCount() != 6 || !loaded(h)) return;
                // Chọn đơn cát thứ orderTry trong bảng (0 = đơn đầu tiên). Đơn nào không nhận cát
                // thì nextOrder() mở lại /orders và tăng orderTry để thử đơn kế tiếp.
                int pick = -1, seen = 0;
                int n = h.getRowCount() * 9;
                for (int i = 0; i < n && i < h.slots.size(); i++) {
                    if (h.slots.get(i).getItem().is(Items.SAND)) {
                        if (seen == orderTry) { pick = i; break; }
                        seen++;
                    }
                }
                if (pick < 0) {
                    if (++ordersWait < 10) return;   // chờ server điền nốt các ô đơn hàng
                    abort(mc, "không còn đơn cát nào để thử (đã thử " + orderTry + " đơn)");
                    return;
                }
                orderSlot = pick;
                click(mc, p, h, pick, ClickType.PICKUP);
                lastSync = h.containerId;
                state = S.WAIT_DELIVER;
                timeout = ORDER_OPEN_TIMEOUT;        // không mở được bảng giao -> thử đơn kế tiếp
                wait = ACTION_DELAY;
            }
            case WAIT_DELIVER -> {
                if (h == null || h.containerId == lastSync || h.getRowCount() != 6) return;
                lastSync = h.containerId;
                sandBefore = countItem(p, Items.SAND);   // để biết đơn có nhận cát không
                clicks = 0;
                settle = false;
                state = S.DEPOSIT;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case DEPOSIT -> {
                if (!License.quickCheck()) { abort(mc, "key không hợp lệ"); return; }
                if (h == null || h.containerId != lastSync) {
                    // bảng giao hàng biến mất: đã giao được cát thì sang bước xác nhận, chưa thì thử đơn khác
                    if (sandMoved(p)) {
                        state = S.WAIT_CONFIRM;
                        timeout = STEP_TIMEOUT;
                        wait = ACTION_DELAY;
                    } else {
                        nextOrder(mc, p, "đơn đóng bảng mà không nhận cát");
                    }
                    return;
                }
                if (settle) {                        // đã bấm thử 1 lần và chờ server trả lời
                    settle = false;
                    if (!sandMoved(p)) { nextOrder(mc, p, "đơn không nhận cát (đầy?)"); return; }
                }
                if (clicks < 120) {
                    for (int i = 0; i < h.slots.size(); i++) {
                        Slot s = h.slots.get(i);
                        if (s.container == p.getInventory() && s.getItem().is(Items.SAND)) {
                            click(mc, p, h, i, ClickType.QUICK_MOVE);
                            clicks++;
                            if (clicks == 1) { settle = true; wait = 6; } else wait = 1;
                            return;
                        }
                    }
                }
                if (clicks == 0) { abort(mc, "không có cát để giao"); return; }
                p.closeContainer();
                state = S.WAIT_CONFIRM;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case WAIT_CONFIRM -> {
                if (h == null || h.containerId == lastSync || h.getRowCount() != 3) return;
                int slot = find(h, st -> nameHas(st, "xác nhận", "confirm"), -1);
                if (slot < 0) slot = CONFIRM_SLOT;
                click(mc, p, h, slot, ClickType.PICKUP);
                state = S.FINISH;
                closes = 0;
                finishIdle = 0;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY * 3;
            }
            case FINISH -> {
                // Sau khi xác nhận, server mở lại các bảng đơn hàng -> thoát (ESC) từng bảng,
                // ít nhất FINISH_CLOSES lần, rồi mới đào tiếp.
                if (mc.screen != null) {
                    p.closeContainer();
                    closes++;
                    finishIdle = 0;
                    wait = ACTION_DELAY;   // chờ server mở bảng kế tiếp (nếu có)
                    return;
                }
                int idleMax = closes < FINISH_CLOSES ? 8 : 2;
                if (finishIdle < idleMax) {
                    finishIdle++;
                    wait = 3;
                    return;
                }
                startMining(p);
                state = S.MINING;
                cooldown = COOLDOWN_AFTER;
                msg(p, "Đã giao xong, tiếp tục đào.");
            }

            // ================= SỬA XẺNG BẰNG BÌNH XP =================
            case MEND_PREP -> {
                if (mc.screen != null) { p.closeContainer(); wait = 2; return; }
                if (step == 0) {
                    // bình XP -> ô số 2 thanh công cụ
                    if (!p.getInventory().getItem(BOTTLE_SLOT).is(Items.EXPERIENCE_BOTTLE)) {
                        int idx = findItem(p, Items.EXPERIENCE_BOTTLE);
                        if (idx < 0) { abort(mc, "không có bình XP"); return; }
                        swapHotbar(mc, p, idx, BOTTLE_SLOT);
                        wait = ACTION_DELAY;
                    }
                    step = 1;
                    return;
                }
                if (step == 1) {
                    // xẻng thấp nhất -> tay trái
                    int idx = lowShovel(p);
                    if (idx < 0) { finishMend(mc, p); return; }
                    shovelFrom = idx;
                    swapOffhand(mc, p, idx);
                    shovelSwapped = true;
                    step = 2;
                    wait = ACTION_DELAY;
                    return;
                }
                // step 2: cầm bình XP bằng tay phải
                setSelected(p, BOTTLE_SLOT);
                oldXRot = p.getXRot();
                mendThrows = 0;
                lastCheck = 0;
                lastDamage = p.getOffhandItem().getDamageValue();
                state = S.MEND_THROW;
                timeout = STEP_TIMEOUT;
                wait = 2;
            }
            case MEND_THROW -> {
                if (mc.screen != null) { p.closeContainer(); wait = 2; return; }
                ItemStack off = p.getOffhandItem();
                if (!off.is(ItemTags.SHOVELS) || remainingPct(off) >= MEND_TARGET_PCT) {
                    state = S.MEND_RESTORE;
                    return;
                }
                if (!p.getInventory().getItem(BOTTLE_SLOT).is(Items.EXPERIENCE_BOTTLE)) {
                    int idx = findItem(p, Items.EXPERIENCE_BOTTLE);
                    if (idx < 0) { needBuy = true; state = S.MEND_RESTORE; return; }
                    swapHotbar(mc, p, idx, BOTTLE_SLOT);
                    wait = ACTION_DELAY;
                    return;
                }
                if (getSelected(p) != BOTTLE_SLOT) { setSelected(p, BOTTLE_SLOT); wait = 2; return; }

                if (mendThrows - lastCheck >= 10) {
                    int dmg = off.getDamageValue();
                    if (dmg >= lastDamage) {
                        autoMend = false;
                        save();
                        msg(p, "Xẻng không được sửa (có Mending không?). Đã tắt tự sửa xẻng.");
                        state = S.MEND_RESTORE;
                        return;
                    }
                    lastDamage = dmg;
                    lastCheck = mendThrows;
                }
                p.setXRot(90f); // nhìn xuống để XP rơi dưới chân
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                p.swing(InteractionHand.MAIN_HAND);
                mendThrows++;
                timeout = STEP_TIMEOUT;
                wait = THROW_DELAY;
            }
            case MEND_RESTORE -> {
                if (mc.screen != null) { p.closeContainer(); wait = 2; return; }
                if (shovelSwapped) {
                    swapOffhand(mc, p, shovelFrom);   // xẻng về chỗ cũ
                    shovelSwapped = false;
                    if (shovelFrom >= 0 && shovelFrom < 9) setSelected(p, shovelFrom); // xẻng về tay phải
                    if (!Float.isNaN(oldXRot)) { p.setXRot(oldXRot); oldXRot = Float.NaN; }
                    wait = ACTION_DELAY;
                    return;
                }
                if (needBuy) {
                    needBuy = false;
                    if (autoBuy && buyTries < 2 && lowShovel(p) >= 0) {
                        buyTries++;
                        beginBuy();
                        return;
                    }
                }
                finishMend(mc, p);
            }

            // ================= MUA BÌNH XP (ĐẦY THANH CÔNG CỤ) =================
            case BUY_PREP -> {
                if (mc.screen != null) { p.closeContainer(); wait = 2; return; }
                int sand = hotbarSandSlot(p);

                if (dropStep == 0) {
                    if (sand < 0) { dropStep = 2; return; }   // thanh công cụ không có cát
                    dropXRot = p.getXRot();
                    p.setXRot(0f);                             // nhìn ngang để cát văng xa, không bị nhặt lại
                    dropStep = 1;
                    wait = 3;                                  // chờ server nhận hướng nhìn mới
                    return;
                }
                if (dropStep == 1) {
                    if (sand >= 0) {
                        dropStack(mc, p, sand);                // vứt cả stack cát ra ngoài
                        wait = 2;
                        return;
                    }
                    if (!Float.isNaN(dropXRot)) { p.setXRot(dropXRot); dropXRot = Float.NaN; }
                    dropStep = 2;
                    wait = ACTION_DELAY;
                    return;
                }
                // dropStep == 2: thanh công cụ đã sạch cát -> tính số lần mua rồi mở shop
                buyRound = 0;
                buyTarget = Math.max(1, Math.min(BUY_MAX_ROUNDS, (hotbarMissing(p) + 63) / 64));
                send(p, "/" + SHOP_COMMAND);
                state = S.BUY_SHOP;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case BUY_SHOP -> {
                if (h == null || h.getRowCount() != 3 || !loaded(h)) return;
                int i;
                if (buyFood) {                               // danh mục FOOD
                    i = find(h, st -> nameHas(st, "food"), -1);
                    if (i < 0) i = SHOP_FOOD_SLOT;
                } else {                                     // ô totem -> GEAR
                    i = find(h, st -> st.is(Items.TOTEM_OF_UNDYING), -1);
                    if (i < 0) i = SHOP_TOTEM_SLOT;
                }
                click(mc, p, h, i, ClickType.PICKUP);
                lastSync = h.containerId;
                state = S.BUY_GEAR;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case BUY_GEAR -> {
                if (h == null || h.containerId == lastSync || h.getRowCount() != 3 || !loaded(h)) return;
                int i;
                if (buyFood) {                               // SHOP -> FOOD: Cà Rốt Vàng
                    i = find(h, st -> st.is(Items.GOLDEN_CARROT), -1);
                    if (i < 0) i = SHOP_FOOD_ITEM_SLOT;
                } else {
                    i = find(h, st -> st.is(Items.EXPERIENCE_BOTTLE), -1);
                    if (i < 0) i = SHOP_BOTTLE_SLOT;
                }
                click(mc, p, h, i, ClickType.PICKUP);
                lastSync = h.containerId;
                state = S.BUY_AMOUNT;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case BUY_AMOUNT -> {
                if (h == null || h.containerId == lastSync || h.getRowCount() != 3 || !loaded(h)) return;
                int i = find(h, st -> nameHas(st, "64"), 13);
                if (i < 0) {
                    i = BUY_SET64_SLOT;
                    msg(p, "Không thấy nút 64 theo tên, bấm ô " + i + ". Các ô: " + dump(h));
                }
                click(mc, p, h, i, ClickType.PICKUP);
                lastSync = h.containerId;
                gone = 0;
                state = S.BUY_CONFIRM;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY * 2;
            }
            case BUY_CONFIRM -> {
                // Sau khi bấm "64", server có thể giữ nguyên bảng hoặc mở bảng mới -> bấm xác nhận ở bảng hiện tại.
                if (h == null || h.getRowCount() != 3) {
                    // bảng bị đóng / đang đổi: chờ ~1 giây, không thấy lại thì mở lại shop mua lại
                    if (++gone > 20) retryBuy(mc, p, "bảng mua bị đóng trước khi xác nhận");
                    return;
                }
                if (!loaded(h)) return;                      // bảng mới chưa nhận đủ đồ
                gone = 0;
                int i = find(h, st -> nameHas(st, "xác nhận", "confirm"), -1);
                if (i < 0) {
                    i = BUY_CONFIRM_SLOT;
                    msg(p, "Không thấy nút xác nhận theo tên, bấm ô " + i + ". Các ô: " + dump(h));
                }
                buyBefore = boughtCount(p); // để biết hàng về chưa
                click(mc, p, h, i, ClickType.PICKUP);
                state = S.BUY_DONE;
                timeout = 80;
                wait = ACTION_DELAY * 3;
            }
            case BUY_DONE -> {
                // chờ hàng về (quá hạn thì xử lý ở phần timeout phía trên)
                if (boughtCount(p) <= buyBefore) return;
                if (mc.screen != null) { p.closeContainer(); wait = 3; return; }
                buyRound++;
                buyRetry = 0;
                if (buyRound < buyTarget) {
                    // thanh công cụ chưa đầy -> mua thêm 1 stack nữa
                    send(p, "/" + SHOP_COMMAND);
                    state = S.BUY_SHOP;
                    timeout = STEP_TIMEOUT;
                    wait = ACTION_DELAY;
                    return;
                }
                finishBuy(mc, p);
            }

            // ================= VỨT ĐỒ KHÔNG PHẢI CÁT =================
            case JUNK -> {
                if (mc.screen != null) { p.closeContainer(); wait = 2; return; }
                if (junkStep == 0) {
                    junkXRot = p.getXRot();
                    p.setXRot(0f);                       // nhìn ngang để đồ văng xa, không bị nhặt lại
                    junkStep = 1;
                    wait = 3;
                    return;
                }
                int j = junkSlot(p);
                if (j >= 0 && junkDropped < JUNK_MAX_DROPS) {
                    dropStack(mc, p, j);
                    junkDropped++;
                    timeout = STEP_TIMEOUT;
                    wait = 2;
                    return;
                }
                if (!Float.isNaN(junkXRot)) { p.setXRot(junkXRot); junkXRot = Float.NaN; }
                state = S.MINING;
                junkCool = JUNK_INTERVAL;
                cooldown = 10;
                startMining(p);
                msg(p, "Đã vứt " + junkDropped + " stack đồ không cần.");
            }

            // ================= ĂN KHI ĐÓI =================
            case EAT_PREP -> {
                if (mc.screen != null) { p.closeContainer(); wait = 2; return; }
                if (p.getFoodData().getFoodLevel() >= EAT_TO) { finishEat(mc, p, COOLDOWN_AFTER); return; }
                int idx = findFood(p);
                if (idx < 0) { msg(p, "Không có đồ ăn."); finishEat(mc, p, 600); return; }
                eatPrevSlot = getSelected(p);
                idx = toHotbar(mc, p, idx);
                setSelected(p, idx);
                eatXRot = p.getXRot();
                p.setXRot(-90f);                         // nhìn lên trời để chuột phải không bấm trúng khối nào
                state = S.EAT_USE;
                timeout = STEP_TIMEOUT;
                wait = 3;
            }
            case EAT_USE -> {
                if (mc.screen != null) { p.closeContainer(); wait = 2; return; }
                if (!p.isUsingItem() && p.getFoodData().getFoodLevel() >= EAT_TO) {
                    finishEat(mc, p, COOLDOWN_AFTER);
                    return;
                }
                if (!edible(p.getMainHandItem())) {      // hết stack đang cầm -> cầm stack khác
                    mc.options.keyUse.setDown(false);
                    int idx = findFood(p);
                    if (idx < 0) { msg(p, "Hết đồ ăn."); finishEat(mc, p, 0); return; }
                    setSelected(p, toHotbar(mc, p, idx));
                    wait = 3;
                    return;
                }
                mc.options.keyUse.setDown(true);         // giữ chuột phải tới khi ăn xong
            }

            default -> { }
        }
    }

    // ----------------------------- BẮT ĐẦU CÁC QUY TRÌNH -----------------------------
    static void startDeliver(Minecraft mc, LocalPlayer p) {
        stopMining(mc, p);
        send(p, "/" + ORDER_COMMAND);
        state = S.WAIT_ORDERS;
        timeout = STEP_TIMEOUT;
        clicks = 0;
        orderTry = 0;
        ordersWait = 0;
        settle = false;
        wait = ACTION_DELAY;
    }

    /** Đơn vừa thử không nhận cát -> đóng bảng, mở lại /orders và thử đơn cát kế tiếp. */
    static void nextOrder(Minecraft mc, LocalPlayer p, String why) {
        int tried = orderTry + 1;
        orderTry++;
        if (orderTry >= ORDER_MAX_TRIES) {
            abort(mc, "đã thử " + tried + " đơn mà không đơn nào nhận cát");
            return;
        }
        msg(p, "Đơn ở ô " + orderSlot + ": " + why + ". Thử đơn kế tiếp (" + (orderTry + 1) + "/" + ORDER_MAX_TRIES + ").");
        if (mc.screen != null) p.closeContainer();
        send(p, "/" + ORDER_COMMAND);
        state = S.WAIT_ORDERS;
        timeout = STEP_TIMEOUT;
        clicks = 0;
        ordersWait = 0;
        settle = false;
        wait = ACTION_DELAY * 3;
    }

    /** Số cát trong túi đã giảm so với lúc bắt đầu giao = đơn có nhận cát. */
    static boolean sandMoved(LocalPlayer p) {
        return countItem(p, Items.SAND) < sandBefore;
    }

    static void startJunk(Minecraft mc, LocalPlayer p) {
        stopMining(mc, p);
        state = S.JUNK;
        junkStep = 0;
        junkDropped = 0;
        timeout = STEP_TIMEOUT;
        wait = ACTION_DELAY;
    }

    static void startEat(Minecraft mc, LocalPlayer p) {
        stopMining(mc, p);
        state = S.EAT_PREP;
        timeout = STEP_TIMEOUT;
        wait = ACTION_DELAY;
    }

    /** Hết đồ ăn: /shop -> FOOD -> Cà Rốt Vàng -> 64 -> xác nhận (dùng lại các bước BUY_*), rồi ăn. */
    static void startFoodBuy(Minecraft mc, LocalPlayer p) {
        stopMining(mc, p);
        msg(p, "Hết đồ ăn, đi mua thêm.");
        buyFood = true;
        buyRound = 0;
        buyTarget = 1;
        buyRetry = 0;
        gone = 0;
        send(p, "/" + SHOP_COMMAND);
        state = S.BUY_SHOP;
        timeout = STEP_TIMEOUT;
        wait = ACTION_DELAY;
    }

    static void finishEat(Minecraft mc, LocalPlayer p, int cool) {
        restoreLook(mc, p);
        state = S.MINING;
        cooldown = cool;
        startMining(p);
    }

    /** Trả lại hướng nhìn / ô đang cầm / nhả chuột phải sau khi ăn hoặc vứt đồ. */
    static void restoreLook(Minecraft mc, LocalPlayer p) {
        mc.options.keyUse.setDown(false);
        if (!Float.isNaN(eatXRot)) { p.setXRot(eatXRot); eatXRot = Float.NaN; }
        if (!Float.isNaN(junkXRot)) { p.setXRot(junkXRot); junkXRot = Float.NaN; }
        if (eatPrevSlot >= 0 && eatPrevSlot < 9) setSelected(p, eatPrevSlot);
        eatPrevSlot = -1;
    }

    static void startMend(Minecraft mc, LocalPlayer p) {
        if (countItem(p, Items.EXPERIENCE_BOTTLE) == 0 && !autoBuy) {
            msg(p, "Xẻng gần hư nhưng hết bình XP (tự mua đang tắt).");
            cooldown = 1200;
            return;
        }
        stopMining(mc, p);
        buyTries = 0;
        needBuy = false;
        step = 0;
        timeout = STEP_TIMEOUT;
        wait = ACTION_DELAY;
        if (countItem(p, Items.EXPERIENCE_BOTTLE) == 0) {
            buyTries = 1;
            beginBuy();
        } else {
            state = S.MEND_PREP;
        }
    }

    /** Vào quy trình mua: vứt cát trên thanh công cụ -> /shop -> mua tới khi đầy thanh công cụ. */
    static void beginBuy() {
        buyFood = false;
        dropStep = 0;
        buyRound = 0;
        buyRetry = 0;
        state = S.BUY_PREP;
        timeout = STEP_TIMEOUT;
        wait = ACTION_DELAY;
    }

    /** Bảng mua bị đóng / quá hạn giữa chừng -> đóng hết, mở lại /shop và mua lại lượt hiện tại. */
    static void retryBuy(Minecraft mc, LocalPlayer p, String why) {
        buyRetry++;
        msg(p, "Mua chưa xong (" + why + "), thử lại lần " + buyRetry + "/" + BUY_RETRIES + ".");
        if (mc.screen != null) p.closeContainer();
        send(p, "/" + SHOP_COMMAND);
        state = S.BUY_SHOP;
        gone = 0;
        timeout = STEP_TIMEOUT;
        wait = ACTION_DELAY * 3;
    }

    /** Mua xong -> quay lại sửa xẻng. */
    static void finishBuy(Minecraft mc, LocalPlayer p) {
        if (mc.screen != null) p.closeContainer();
        if (buyFood) {                                   // mua đồ ăn xong -> ăn luôn
            buyFood = false;
            foodBuyFails = 0;
            foodWarn = false;
            msg(p, "Đã mua đồ ăn, hiện có " + countFood(p) + ".");
            state = S.EAT_PREP;
            timeout = STEP_TIMEOUT;
            wait = ACTION_DELAY;
            return;
        }
        msg(p, "Đã mua bình XP, hiện có " + countItem(p, Items.EXPERIENCE_BOTTLE) + " bình.");
        state = S.MEND_PREP;
        step = 0;
        timeout = STEP_TIMEOUT;
        wait = ACTION_DELAY;
    }

    static void finishMend(Minecraft mc, LocalPlayer p) {
        if (mc.screen != null) p.closeContainer();
        boolean still = lowShovel(p) >= 0;
        buyTries = 0;
        needBuy = false;
        state = S.MINING;
        cooldown = still ? 1200 : COOLDOWN_AFTER;
        startMining(p);
        msg(p, still ? "Xẻng vẫn còn thấp. Thử lại sau 1 phút." : "Đã sửa xong xẻng, tiếp tục đào.");
    }

    static void abort(Minecraft mc, String why) {
        LocalPlayer p = mc.player;
        if (p != null) {
            if (mc.screen != null) p.closeContainer();
            if (shovelSwapped && mc.gameMode != null) {
                swapOffhand(mc, p, shovelFrom);
                shovelSwapped = false;
            }
            if (!Float.isNaN(oldXRot)) { p.setXRot(oldXRot); oldXRot = Float.NaN; }
            if (!Float.isNaN(dropXRot)) { p.setXRot(dropXRot); dropXRot = Float.NaN; }
            restoreLook(mc, p);
            startMining(p);
            msg(p, "Huỷ: " + why);
            if (buyFood && ++foodBuyFails >= 3) {            // mua đồ ăn thất bại 3 lần liên tiếp -> tắt tự mua
                autoBuyFood = false;
                save();
                msg(p, "Mua đồ ăn lỗi 3 lần, đã tắt tự mua đồ ăn. Kiểm tra lại ô /shop -> FOOD.");
            }
        }
        buyFood = false;
        state = S.MINING;
        cooldown = 200;
    }

    // ----------------------------- HÀM PHỤ: TÚI ĐỒ -----------------------------
    /** Số ô trong menu túi đồ (windowId 0): hotbar 0-8 -> 36-44, túi 9-35 giữ nguyên. */
    static int menuSlot(int invIdx) { return invIdx < 9 ? invIdx + 36 : invIdx; }

    static void swapOffhand(Minecraft mc, LocalPlayer p, int invIdx) {
        mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(invIdx), 40, ClickType.SWAP, p);
    }

    static void swapHotbar(Minecraft mc, LocalPlayer p, int invIdx, int hotbar) {
        mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(invIdx), hotbar, ClickType.SWAP, p);
    }

    /** Vứt nguyên stack ở ô túi đồ invIdx ra ngoài (như Ctrl+Q). */
    static void dropStack(Minecraft mc, LocalPlayer p, int invIdx) {
        ItemStack st = p.getInventory().getItem(invIdx);
        if (st.isEmpty()) return;
        // CHỐT AN TOÀN: tuyệt đối không vứt đồ ăn, bình XP, xẻng (thanh công cụ chỉ vứt cát để lấy chỗ mua bình XP)
        if (st.has(DataComponents.FOOD) || st.is(Items.EXPERIENCE_BOTTLE) || st.is(ItemTags.SHOVELS)) return;
        mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(invIdx), 1, ClickType.THROW, p);
    }

    static void setSelected(LocalPlayer p, int slot) { p.getInventory().setSelectedSlot(slot); }

    static int getSelected(LocalPlayer p) { return p.getInventory().getSelectedSlot(); }

    static int findItem(LocalPlayer p, Item item) {
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    static int countItem(LocalPlayer p, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    /** Vật phẩm được GIỮ trong túi: cát, bình XP, xẻng, đồ ăn. Muốn giữ thêm món nào thì thêm vào đây. */
    static boolean keepItem(ItemStack st) {
        return st.is(Items.SAND)
            || st.is(Items.EXPERIENCE_BOTTLE)
            || st.is(ItemTags.SHOVELS)
            || st.has(DataComponents.FOOD);
    }

    /** Ô (9-35) đầu tiên chứa đồ cần vứt; -1 nếu không có. Không đụng thanh công cụ (0-8), giáp và tay trái. */
    static int junkSlot(LocalPlayer p) {
        for (int i = 9; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && !keepItem(st)) return i;
        }
        return -1;
    }

    /** Đồ ăn dùng được để ăn lấp đói (bỏ qua đồ có hiệu ứng xấu và táo vàng). */
    static boolean edible(ItemStack st) {
        if (st.isEmpty() || !st.has(DataComponents.FOOD)) return false;
        return !(st.is(Items.ROTTEN_FLESH) || st.is(Items.SPIDER_EYE) || st.is(Items.POISONOUS_POTATO)
            || st.is(Items.PUFFERFISH) || st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE)
            || st.is(Items.CHORUS_FRUIT) || st.is(Items.SUSPICIOUS_STEW));
    }

    /** Ưu tiên cà rốt vàng, không có thì món ăn khác; -1 nếu hết đồ ăn. */
    static int findFood(LocalPlayer p) {
        int any = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!edible(st)) continue;
            if (st.is(Items.GOLDEN_CARROT)) return i;
            if (any < 0) any = i;
        }
        return any;
    }

    static int countFood(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (edible(st)) n += st.getCount();
        }
        return n;
    }

    /** Số hàng đang mua (đồ ăn hoặc bình XP) để biết hàng đã về chưa. */
    static int boughtCount(LocalPlayer p) {
        return buyFood ? countFood(p) : countItem(p, Items.EXPERIENCE_BOTTLE);
    }

    static boolean boughtAny(LocalPlayer p) {
        return boughtCount(p) > 0;
    }

    static int emptySlot(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /** Đưa món ở ô túi idx lên thanh công cụ (nếu đang ở trong túi); trả về ô thanh công cụ (0-8). */
    static int toHotbar(Minecraft mc, LocalPlayer p, int idx) {
        if (idx < 9) return idx;
        int target = EAT_SLOT;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).isEmpty()) { target = i; break; }
        }
        swapHotbar(mc, p, idx, target);
        return target;
    }

    /** Ô cát đầu tiên trên thanh công cụ (0-8); -1 nếu không có. */
    static int hotbarSandSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(Items.SAND)) return i;
        }
        return -1;
    }

    /** Số bình XP còn thiếu để thanh công cụ đầy: ô trống = 1 stack, ô bình chưa đủ stack = phần thiếu. */
    static int hotbarMissing(LocalPlayer p) {
        int need = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) need += 64;
            else if (st.is(Items.EXPERIENCE_BOTTLE)) need += st.getMaxStackSize() - st.getCount();
        }
        return need;
    }

    /** Đầy = không còn ô trống và không còn stack cát nào chưa đủ 64. */
    static boolean isFull(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) return false;
            if (st.is(Items.SAND) && st.getCount() < st.getMaxStackSize()) return false;
        }
        return true;
    }

    static double remainingPct(ItemStack st) {
        int max = st.getMaxDamage();
        if (max <= 0) return 100;
        return (max - st.getDamageValue()) * 100.0 / max;
    }

    /** Vị trí xẻng có độ bền còn lại thấp nhất và <= mendPct; -1 nếu không có. */
    static int lowShovel(LocalPlayer p) {
        int best = -1;
        double bestPct = 101;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty() || !st.is(ItemTags.SHOVELS) || !st.isDamageableItem()) continue;
            double pct = remainingPct(st);
            if (pct <= mendPct && pct < bestPct) { best = i; bestPct = pct; }
        }
        return best;
    }

    // ----------------------------- HÀM PHỤ: GUI SERVER -----------------------------
    static ChestMenu container(Minecraft mc) {
        if (mc.screen instanceof AbstractContainerScreen<?> sc && sc.getMenu() instanceof ChestMenu m) {
            return m;
        }
        return null;
    }

    static void click(Minecraft mc, LocalPlayer p, ChestMenu h, int slot, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(h.containerId, slot, 0, type, p);
    }

    /** GUI đã có đồ (server đã gửi xong nội dung) chưa. */
    static boolean loaded(ChestMenu h) {
        int n = h.getRowCount() * 9;
        for (int i = 0; i < n && i < h.slots.size(); i++) {
            if (!h.slots.get(i).getItem().isEmpty()) return true;
        }
        return false;
    }

    /** Tìm ô trong phần chest thoả điều kiện (bỏ qua ô skip); -1 nếu không có. */
    static int find(ChestMenu h, Predicate<ItemStack> pr, int skip) {
        int n = h.getRowCount() * 9;
        for (int i = 0; i < n && i < h.slots.size(); i++) {
            if (i == skip) continue;
            ItemStack st = h.slots.get(i).getItem();
            if (!st.isEmpty() && pr.test(st)) return i;
        }
        return -1;
    }

    /** a-z kiểu small-caps (ᴀʙᴄ...) theo đúng thứ tự a..z, để so tên nút server viết bằng chữ small-caps. */
    static final String SMALL_CAPS =
        "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍ"
        + "ɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    /** Chuẩn hoá tên để so sánh: chữ thường, bỏ dấu tiếng Việt, đổi small-caps về a-z. */
    static String norm(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int k = SMALL_CAPS.indexOf(c);
            sb.append(k >= 0 ? (char) ('a' + k) : c);
        }
        String t = Normalizer.normalize(sb.toString().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return t.replaceAll("\\p{M}+", "").replace('đ', 'd');
    }

    static boolean nameHas(ItemStack st, String... keys) {
        if (st.isEmpty()) return false;
        String n = norm(st.getHoverName().getString());
        for (String k : keys) {
            if (n.contains(norm(k))) return true;
        }
        return false;
    }

    /** Liệt kê "ô=tên" của các ô có đồ trong phần chest, dùng để báo lỗi khi không tìm thấy nút theo tên. */
    static String dump(ChestMenu h) {
        StringBuilder sb = new StringBuilder();
        int n = h.getRowCount() * 9;
        for (int i = 0; i < n && i < h.slots.size(); i++) {
            ItemStack st = h.slots.get(i).getItem();
            if (!st.isEmpty()) sb.append(i).append('=').append(st.getHoverName().getString()).append(" | ");
        }
        return sb.toString();
    }

    // ----------------------------- HÀM PHỤ: CHAT -----------------------------
    /** "/xxx" gửi như lệnh server; "#xxx" / ".xxx" sẽ bị Baritone / Meteor chặn ở client. */
    static void send(LocalPlayer p, String s) {
        if (s.startsWith("/")) p.connection.sendCommand(s.substring(1));
        else p.connection.sendChat(s);
    }

    static void activateKey(Minecraft mc, String key) {
        if (mc.player != null) msg(mc.player, License.activate(key));
    }

    static void needKeyMsg(LocalPlayer p) {
        msg(p, "Cần key để dùng. Nhắn Zalo " + License.ZALO + " để mua key. Có key thì gõ /autosand key <key>.");
    }

    static void msg(LocalPlayer p, String t) {
        p.displayClientMessage(Component.literal("[AutoSand] " + t), false);
    }

    // ============================== HỆ THỐNG KEY (LICENSE) ==============================
    /**
     * 50 key NGẮN (8 ký tự) VĨNH VIỄN có sẵn trong mod, dùng được trên mọi máy, không cần Internet.
     * Trong mod chỉ lưu mã băm SHA-256 của key (không lưu key gốc), nên mở file mod ra cũng không đọc được key.
     * Danh sách key gốc nằm trong file autosand_keys.txt (chỉ bạn giữ, đừng đưa vào mod).
     * Khách gõ /autosand key <key> (hoặc copy key rồi bấm "Kích hoạt key từ clipboard" trong /autosand).
     */
    static final class License {
        private License() { }

        private static final byte[] ZALO_ENC = {85, 86, 92, 82, 83, 80, 82, 87, 80, 85}; // XOR("0397657250", 0x65)
        public static final String ZALO = deobf(ZALO_ENC);

        private static String deobf(byte[] enc) {
            char[] c = new char[enc.length];
            for (int i = 0; i < enc.length; i++) c[i] = (char) (enc[i] ^ 0x65);
            return new String(c);
        }

        private static final String SALT = "AutoSand/key/v3|";
        private static final int KEY_LEN = 8;
        private static final String[] KEY_HASHES = {
            "fe74adceb220ea043e417d5e74a7627954066e91e60c99f5bb893dd945311b53",
            "96f6f9762214befd2bda48168113aa2f9abfb7446a1c389cc90cc33bd58d0dd5",
            "afd6482fe2285e2f83872337d3a5c262dc6f99fdb08b273e6ea1bb90af295636",
            "46edd9659dbdb2f6937918bdc8fb358b16b5f6035ae59cd2725f8d0c794788cc",
            "c3b5e893e247811530f07f45b463f0d6c918a29fb249edf87ca9d861e1c60e8c",
            "640d204b6c758a1bf0c69aee5e6aba5e0bc448183c761f533fc8015dad1e3a3a",
            "b170bfe1c403db3e940ea57b4dc20994b973f786ad5874c99e0ad8179adcca2d",
            "8cfd899892d83c1729a5fe58280434e91a58ff77594f36311f7bd756433b09ea",
            "f783dc15f1cad16656f3728a6b5b93edba97a5d22034e934a7f59ac7e6b1e27e",
            "0ff818aabb23dc38d8c94fd5886e7acb16dd108e1f686c60f98f59a9002f1939",
            "3193c07d6144e65b23d833b19e9af123fe4293ec1f0638c33603e2dbe7637cae",
            "730fc96ba2d900d62eec2ed353ee44bcda0c4f0e4adbf865d96ed1f50cb78b2b",
            "4c70f1e6cf6ad460491ac71c0851e134b62da5c11cfa1bf1ddc365f43598637e",
            "6dd4c56964dbf5d6cca212fcc62ef53e1d3c8e4b18ead089b00006dcadaccaa6",
            "a97d2ddd99b3c8c2d81d3964c94ec2341190312f970ecd6d1828bb4cdf528bda",
            "03b00732a898b80bd0f14335b66157569794a82efc533b78b6a0b8121c5b322d",
            "74cc715b9645279891bf1870989e12e25b34b27f0e5cd708d9228075e21d737f",
            "45001e801d4504284d307b7a0603aee2a61f3d8490e6be679e9f6a1ddb3ba3d3",
            "bc9505ec442f3db2b4df4bf2acf5a3b296b6f43ccaf942942402968366df3871",
            "1526a6978bb9267f5c5ea3b26cac81120a8de10d5c20cefebd3f9edc5788beb0",
            "50e965e781695f348604ca8f327912e8d9804e01213430ee8a8b151aca2e96d2",
            "c6db9bc77920c8358fead4b800db27a41d5b95809cbc7f075cf6144045fd4fe5",
            "67928ad882fdfca7129f3d712c1b1c093e6eb5d1a7ad04aaebed7c3827563c68",
            "0a6248d926af9511915217500806dbb0e701ea7378304a2dd98716dea0ee5b21",
            "75762f8a44596ae941b9afacaaa5e58ff555ccc769e93c4c8cf0f8b672810df5",
            "defc3dd1522ea3a957dd88ed7d39df2c88283e212b4b5e3db012cffbc4c2e325",
            "f66b50d4522a65e0dd5ad2e7eda24050085e6e5e83ade7385545b2831e47cdc4",
            "e63ff59ab779a987828472b13312cf1ad36e84122e569f43c2769d6a6ca03d15",
            "42dd2f343183254392d610bf207134ec57885148c30664aa477e478a96e703df",
            "135808bce3484c84605dbc69b33449788bf45c2c079547097710da74356c6a57",
            "41dd5a1d96185923ddecc833017f19b326a44719b22ef4439ca6e0bad7d280e7",
            "6a663ab9f1d07a12e2a40e97d16de6d1ba69ffc2e6aa42205926fc93ca143644",
            "a74883890845eff9298d88652e8aaf89bc6605e4fea3d6ca83581c9ecc0903ca",
            "20039b758fa7e861b6dd065e8fffb964edb62a74a53d5529079819340990c657",
            "7517ab368388276084bcf016c1cc57f0e29984b98a751e60e0f68c4a4c939861",
            "f17cdb79f4f9744e69d25953234adc18c0f8506318a56d75b0b320dcdf984af0",
            "5962ff95e4f7396262cb1834264404325bd9d70986a05ed6c9002810f9ef0050",
            "3c3fd873c664082a3be82131dd6765e8b4b113df666e93bd5549df0ee34b4856",
            "02adc2e142c644cde4ad6ae1cddcc9db72ece8a7e2de86097393db9d88857228",
            "a751a22d96033d1ed38f5c63f4cb1092735a17c08e2a1bce4d1ee6e077ca7a68",
            "5950e4091107b7b98417be31e310e7c182ae92f9be603a38adc8c468902be972",
            "f3dfd3dcbab2b7a694a834790fb604322f77a8238c211911f7279da3d0a6f394",
            "b64f983f7d3cf73e7351ac17564175f2e7791ed52dde04a62e00c7c5b080323b",
            "2d0065576eb1272a9dfd7a6a07ffd7d0844e6a41b6ff8170974feaf57e696757",
            "a49bb1637963a95bd8adbde19c41c47a019ec21d5e90f8ec22610073d595c310",
            "293a33f75ea4546784ba24c7b67060d24ce3d87b8dfdaf1409f98a145faad0a2",
            "6ba7b772674c240d6d25b6f1385635f45199dab52c7187c2351cabc450b0f5b2",
            "c72795bd0814d5f037a2c43b3ef02599644b1acad44a2b88c0c82bfc3ff4b87c",
            "baba66b6aac6de729b01dd31c29e2323bd3bf1151acfd54c26f8ee80df98c89a",
            "ced49ab6d2664baa5ec6e615310b0b8d90ae7ffd4df4ecb7a68b4f3e666af95e"
        };

        private static Path file;
        private static volatile String storedKey = null;
        private static volatile boolean ok = false;

        public static void init(Path configDir) {
            file = configDir.resolve("autosand.lic");
            storedKey = null;
            try {
                if (Files.exists(file)) {
                    List<String> l = Files.readAllLines(file, StandardCharsets.UTF_8);
                    if (!l.isEmpty()) storedKey = l.get(0).trim();
                }
            } catch (Exception ignored) { }
            String k = storedKey;
            ok = k != null && matches(k);
        }

        /** Viết hoa, bỏ dấu gạch và khoảng trắng: "ab12-cd34" -> "AB12CD34". */
        private static String normalize(String key) {
            return key == null ? "" : key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        }

        private static boolean matches(String key) {
            String n = normalize(key);
            if (n.length() != KEY_LEN) return false;
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest((SALT + n).getBytes(StandardCharsets.UTF_8));
                String hex = HexFormat.of().formatHex(d);
                for (String h : KEY_HASHES) {
                    if (h.equals(hex)) return true;
                }
            } catch (Exception ignored) { }
            return false;
        }

        /** Key đã kích hoạt (kết quả lưu sẵn). */
        public static boolean valid() {
            return ok;
        }

        /** Kiểm tra lại độc lập với valid(): tính lại mã băm của key đang lưu. */
        public static boolean quickCheck() {
            String k = storedKey;
            return k != null && matches(k);
        }

        public static String status() {
            return ok ? "đã kích hoạt (vĩnh viễn)" : "chưa kích hoạt";
        }

        public static String activate(String key) {
            if (!matches(key)) return "Key không đúng. Nhắn Zalo " + ZALO + " để mua key.";
            storedKey = normalize(key);
            ok = true;
            try {
                if (file != null) Files.write(file, List.of(storedKey), StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
            return "Kích hoạt thành công: key vĩnh viễn.";
        }
    }

    // ============================== AN TOÀN: NÉ NƯỚC / LAVA / HỐ ==============================
    // Chạy mỗi tick khi đang ở trạng thái MINING (xem guardTick):
    //  - LIQUID: rơi xuống nước / lava -> nhấn nhảy, đi về phía bờ gần nhất, lên bờ xong tự gửi lại lệnh đào.
    //            Đây là chỗ sửa lỗi "rơi xuống nước thì auto bị tắt": Baritone / Nuker dừng lại mà không ai bật lại.
    //  - ESCAPE: kẹt trong hố (4 phía đều chắn) -> nhảy lên, đặt cát dưới chân, lặp lại tới khi ra khỏi hố.
    //  - SIDE:   thấy nước / lava / hố ngay phía trước -> dừng, quay sang hướng an toàn đi vài tick rồi đào tiếp.
    //  - Watchdog: đứng yên quá lâu thì gửi lại lệnh đào (phòng khi Baritone / Nuker tự dừng vì lý do khác).
    static final int HOLE_DEPTH = 3;          // chỗ trống phía trước sâu từ bấy nhiêu ô trở lên thì coi là hố
    static final int TRAP_TICKS = 4;          // kẹt trong hố liên tục bấy nhiêu tick mới bắt đầu thoát
    static final int ESCAPE_MAX_PILLARS = 20; // tối đa số lần đặt block cho 1 lần thoát hố
    static final int GUARD_COOLDOWN = 60;     // nghỉ sau mỗi lần xử lý để không lặp liên tục
    static final int WATCHDOG_TICKS = 200;    // đứng yên bấy nhiêu tick (10 giây) thì gửi lại lệnh đào
    static final int SHORE_RADIUS = 10;       // bán kính tìm bờ khi đang ở dưới nước / lava
    static final int SIDE_TICKS = 14;         // số tick đi sang bên khi né
    static final int BRIDGE_RADIUS = 16;      // tìm bờ để bắc cầu trong bán kính này
    static final int BRIDGE_MAX = 40;         // tối đa số block đặt cho 1 lần bắc cầu
    static final int PICKUP_MAX_TICKS = 100;  // đi nhặt 1 món quá bấy nhiêu tick thì bỏ qua món đó
    static final int WD_STEP = 20;            // watchdog kiểm tra mỗi bấy nhiêu tick
    static final int WD_IDLE_MIN = 100;       // đứng yên / không đào bấy nhiêu tick (3 giây) thì bật lại lệnh đào
    static final int WD_IDLE_MAX = 400;       // mỗi lần bật lại không ăn thua thì chờ lâu gấp đôi, tối đa bấy nhiêu

    static boolean avoidLiquid = true;        // né nước / lava + tự bơi lên bờ
    static boolean avoidHole = true;          // né hố + tự đặt block thoát hố
    static boolean bridgeWater = true;        // bị nước bao quanh -> đặt block bắc cầu vào bờ

    enum G { NONE, LIQUID, ESCAPE, SIDE, PICKUP, BRIDGE }

    static G guard = G.NONE;
    static int guardCool = 0, guardTicks = 0, trapTicks = 0, wdTick = 0;
    static double wdX = Double.NaN, wdZ = Double.NaN;
    static BlockPos shore = null;
    static int escPhase = 0, escPhaseTicks = 0, escPillars = 0, escSlot = -1, escPrevSel = 0, escSwapIdx = -1;
    static BlockPos escBase = null;
    static float escPitch = Float.NaN;
    static int sideTicks = 0, sideStrikes = 0, sideLock = 0, sideWindow = 0;
    static BlockPos bridgeTarget = null;
    static int bridgeCool = 0, brPlaced = 0, brCool = 0, surroundTicks = 0;
    static ItemEntity pickTarget = null;
    static int pickTicks = 0, pickCool = 0;
    static final Set<Integer> pickIgnore = new HashSet<>();
    static int wetTicks = 0;
    static int wdIdle = 0, wdGap = WD_IDLE_MIN, wdFails = 0, wdSand = -1;

    static final Direction[] HORIZ = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** Ô không cản đường (không khí, nước, cỏ...). */
    static boolean passable(ClientLevel lv, BlockPos pos) {
        return lv.getBlockState(pos).getCollisionShape(lv, pos).isEmpty();
    }

    static boolean liquidAt(ClientLevel lv, BlockPos pos) {
        var fs = lv.getBlockState(pos).getFluidState();
        return fs.is(FluidTags.WATER) || fs.is(FluidTags.LAVA);
    }

    /**
     * Kẹt trong hố: phía trên còn trống để đặt block, nhưng 4 phía đều chắn cả chân lẫn đầu
     * và cũng không có bậc nào (cao 1 ô) để bước lên.
     */
    static boolean trapped(ClientLevel lv, BlockPos f) {
        if (!passable(lv, f.above()) || !passable(lv, f.above(2))) return false;
        for (Direction d : HORIZ) {
            BlockPos n = f.relative(d);
            boolean feet = passable(lv, n), head = passable(lv, n.above());
            if (feet && head) return false;                              // đi ngang ra được
            if (!feet && head && passable(lv, n.above(2))) return false; // bước lên 1 bậc được
        }
        return true;
    }

    static boolean lavaAt(ClientLevel lv, BlockPos pos) {
        return lv.getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    /** Nước sâu từ 2 ô trở lên (nước nông 1 ô thì đi bộ được, không tính). */
    static boolean deepWater(ClientLevel lv, BlockPos a) {
        return (liquidAt(lv, a) && liquidAt(lv, a.below()))
            || (liquidAt(lv, a.below()) && liquidAt(lv, a.below(2)));
    }

    /** Đang ở chỗ nguy hiểm thật: trong lava, hoặc đang bơi / chìm trong nước sâu. Đứng trong nước nông thì không. */
    static boolean danger(LocalPlayer p) {
        if (p.isInLava()) return true;
        return p.isInWater() && (p.isUnderWater() || !p.onGround());
    }

    /** Đi theo hướng (dx, dz) từ (x, y, z), trong 2 ô tới có nước / lava / hố không. */
    static boolean hazardDir(ClientLevel lv, double x, double y, double z, double dx, double dz) {
        for (double k : new double[] {1.0, 1.8}) {
            BlockPos a = BlockPos.containing(x + dx * k, y + 0.1, z + dz * k);
            if (avoidLiquid && (lavaAt(lv, a) || lavaAt(lv, a.below()) || deepWater(lv, a))) return true;
            if (avoidHole && passable(lv, a)) {
                boolean drop = true;
                for (int i = 1; i <= HOLE_DEPTH; i++) {
                    if (!passable(lv, a.below(i))) { drop = false; break; }
                }
                if (drop) return true;
            }
        }
        return false;
    }

    /** Bờ gần nhất: ô có nền đứng, đủ chỗ cho người, không có nước / lava. */
    static BlockPos findShore(ClientLevel lv, LocalPlayer p) {
        BlockPos o = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SHORE_RADIUS; dx <= SHORE_RADIUS; dx++) {
            for (int dz = -SHORE_RADIUS; dz <= SHORE_RADIUS; dz++) {
                double d = dx * dx + dz * dz;
                if (d >= bestD) continue;
                for (int dy = 2; dy >= -2; dy--) {
                    BlockPos f = o.offset(dx, dy, dz);
                    BlockPos g = f.below();
                    if (lavaAt(lv, f) || lavaAt(lv, f.above()) || lavaAt(lv, g) || liquidAt(lv, f.above())) continue;
                    if (!passable(lv, f) || !passable(lv, f.above())) continue;
                    if (passable(lv, g)) continue;               // phải có nền để đứng
                    best = f;
                    bestD = d;
                    break;
                }
            }
        }
        return best;
    }

    /** Block đặt được để leo hố: ưu tiên cát, không có thì block bất kỳ. */
    static boolean placeable(ItemStack st) {
        return !st.isEmpty() && (st.is(Items.SAND) || st.getItem() instanceof BlockItem);
    }

    static int findPlaceable(LocalPlayer p) {
        int idx = findItem(p, Items.SAND);
        if (idx >= 0) return idx;
        for (int i = 0; i < 36; i++) {
            if (placeable(p.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    static int hotbarPlaceSlot(LocalPlayer p) {
        int s = hotbarSandSlot(p);
        if (s >= 0) return s;
        for (int i = 0; i < 9; i++) {
            if (placeable(p.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    static void guardReset(Minecraft mc, LocalPlayer p) {
        if ((guard == G.ESCAPE || guard == G.BRIDGE) && p != null && mc.gameMode != null) {
            if (escSwapIdx >= 0) { swapHotbar(mc, p, escSwapIdx, escSlot); escSwapIdx = -1; }
            setSelected(p, escPrevSel);
            if (!Float.isNaN(escPitch)) { p.setXRot(escPitch); escPitch = Float.NaN; }
        }
        guard = G.NONE;
        mc.options.keyJump.setDown(false);
        trapTicks = 0;
        guardCool = 0;
        wdTick = 0;
        wdX = Double.NaN;
        pickTarget = null;
        wdIdle = 0;
        wdGap = WD_IDLE_MIN;
    }

    /** Kết thúc 1 lần xử lý: nhả phím, đào tiếp. */
    static void endGuard(Minecraft mc, LocalPlayer p) {
        mc.options.keyJump.setDown(false);
        releaseKeys(mc);
        guard = G.NONE;
        guardCool = GUARD_COOLDOWN;
        wetTicks = 0;
        trapTicks = 0;
        wdTick = 0;
        wdX = Double.NaN;
        startMining(p);
    }

    /** Trả về true nếu bộ an toàn đang điều khiển (bỏ qua logic đào của tick này). */
    static boolean guardTick(Minecraft mc, LocalPlayer p) {
        ClientLevel lv = mc.level;
        if (lv == null) return false;

        if (guard == G.LIQUID) { tickLiquid(mc, p, lv); return true; }
        if (guard == G.ESCAPE) { tickEscape(mc, p, lv); return true; }
        if (guard == G.SIDE) { tickSide(mc, p, lv); return true; }
        if (guard == G.PICKUP) { tickPickup(mc, p, lv); return true; }
        if (guard == G.BRIDGE) { tickBridge(mc, p, lv); return true; }

        if (guardCool > 0) guardCool--;
        if (pickCool > 0) pickCool--;
        if (sideLock > 0) sideLock--;
        if (sideWindow > 0 && --sideWindow == 0) sideStrikes = 0;

        // 1) rơi xuống nước / lava
        //    Baritone tự bơi được nên cho nó vài giây tự xoay xở trước; lava thì ra tay gần như ngay.
        if (avoidLiquid && danger(p)) {
            wetTicks++;
            int need = p.isInLava() ? 3 : (mode == Mode.BARITONE ? 50 : 15);
            if (wetTicks >= need) {
                wetTicks = 0;
                stopMining(mc, p);
                guard = G.LIQUID;
                guardTicks = 0;
                shore = null;
                msg(p, p.isInLava() ? "Đang ở trong lava -> tự thoát lên bờ." : "Kẹt trong nước sâu -> tự bơi lên bờ.");
                return true;
            }
        } else {
            wetTicks = 0;
        }

        // 2) kẹt trong hố -> đặt block leo lên
        if (avoidHole && p.onGround() && trapped(lv, p.blockPosition())) {
            if (++trapTicks >= TRAP_TICKS) {
                trapTicks = 0;
                if (startEscape(mc, p)) return true;
            }
        } else {
            trapTicks = 0;
        }

        // 2b) bị nước bao quanh, không còn đường đi -> đặt block bắc cầu vào bờ
        if (bridgeCool > 0) bridgeCool--;
        if (avoidLiquid && bridgeWater && bridgeCool <= 0 && p.onGround() && surroundedByWater(lv, p.blockPosition())) {
            if (++surroundTicks >= (mode == Mode.BARITONE ? 40 : 20)) {
                surroundTicks = 0;
                if (startBridge(mc, p)) return true;
            }
        } else {
            surroundTicks = 0;
        }

        // 3) nước / lava / hố ngay phía trước -> né sang bên
        if (mode != Mode.BARITONE && mode != Mode.BUILTIN && (avoidLiquid || avoidHole) && guardCool <= 0 && sideLock <= 0 && p.onGround()) {
            var v = p.getDeltaMovement();
            double h = Math.hypot(v.x, v.z);
            if (h > 0.08 && hazardDir(lv, p.getX(), p.getY(), p.getZ(), v.x / h, v.z / h)) {
                startSide(mc, p, lv, Math.toDegrees(Math.atan2(-v.x, v.z)));
                return true;
            }
        }

        // 4) ưu tiên nhặt cát đang rơi gần đó
        if (mode != Mode.BARITONE && mode != Mode.BUILTIN && pickupRadius > 0 && pickCool <= 0 && guardCool <= 0 && p.onGround() && !isFull(p)) {
            ItemEntity it = nearestSand(lv, p);
            if (it != null) { startPickup(mc, p, it); return true; }
        }

        // 5) watchdog: Baritone / Nuker tự dừng, báo lỗi, hết đường... -> tự bật lại.
        //    "Đang làm việc" = có di chuyển, đang phá khối, hoặc số cát trong túi vừa thay đổi.
        if (autoRevive && mode != Mode.BUILTIN && (mode == Mode.BARITONE || useNuker) && ++wdTick >= WD_STEP) {
            wdTick = 0;
            double moved = Double.isNaN(wdX) ? 99 : Math.hypot(p.getX() - wdX, p.getZ() - wdZ);
            wdX = p.getX();
            wdZ = p.getZ();
            int sandNow = countItem(p, Items.SAND);
            boolean working = moved > 0.15 || mc.gameMode.isDestroying() || sandNow != wdSand || wait > 0;
            wdSand = sandNow;
            if (working) {
                wdIdle = 0;
                wdGap = WD_IDLE_MIN;
                wdFails = 0;
            } else {
                wdIdle += WD_STEP;
                if (wdIdle >= wdGap) {
                    wdIdle = 0;
                    wdFails++;
                    wdGap = Math.min(WD_IDLE_MAX, wdGap * 2);
                    if (wdFails <= 2 || wdFails % 5 == 0) {
                        msg(p, "Baritone / Nuker có vẻ đã dừng -> bật lại (lần " + wdFails + ").");
                    }
                    if (wdFails % 3 == 0) stopMining(mc, p);     // lần thứ 3 trở đi: tắt hẳn rồi bật lại cho sạch
                    startMining(p);
                }
            }
        }
        return false;
    }

    // ----- BRIDGE (bắc cầu qua nước) -----
    static boolean walkable(ClientLevel lv, BlockPos n) {
        return passable(lv, n) && passable(lv, n.above()) && !passable(lv, n.below());
    }

    /** Bị nước bao quanh: cả 4 phía đều không đi bộ / bước lên được và ít nhất 1 phía là nước sâu. */
    static boolean surroundedByWater(ClientLevel lv, BlockPos f) {
        boolean deep = false;
        for (Direction d : HORIZ) {
            BlockPos n = f.relative(d);
            if (walkable(lv, n)) return false;
            if (!passable(lv, n) && passable(lv, n.above()) && passable(lv, n.above(2))) return false; // bước lên được
            if (deepWater(lv, n)) deep = true;
        }
        return deep;
    }

    /** Bờ khô gần nhất (cách ít nhất 3 ô) và là vùng đất rộng, không phải cột đá nhỏ giữa nước. */
    static BlockPos findDry(ClientLevel lv, LocalPlayer p, int radius) {
        BlockPos o = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = dx * dx + dz * dz;
                if (d < 9 || d >= bestD) continue;
                for (int dy = 2; dy >= -2; dy--) {
                    BlockPos f = o.offset(dx, dy, dz);
                    if (!walkable(lv, f) || liquidAt(lv, f) || liquidAt(lv, f.above())) continue;
                    int land = 0;
                    for (Direction h : HORIZ) {
                        BlockPos n = f.relative(h);
                        if (walkable(lv, n) && !liquidAt(lv, n)) land++;
                    }
                    if (land < 3) continue;
                    best = f;
                    bestD = d;
                    break;
                }
            }
        }
        return best;
    }

    static boolean startBridge(Minecraft mc, LocalPlayer p) {
        ClientLevel lv = mc.level;
        if (lv == null) return false;
        BlockPos t = findDry(lv, p, BRIDGE_RADIUS);
        if (t == null) {
            msg(p, "Bị nước bao quanh nhưng không thấy bờ trong " + BRIDGE_RADIUS + " ô.");
            bridgeCool = 400;
            return false;
        }
        int slot = hotbarPlaceSlot(p);
        int swapped = -1;
        if (slot < 0) {
            int idx = findPlaceable(p);
            if (idx < 0) {
                msg(p, "Bị nước bao quanh nhưng không có block để bắc cầu.");
                bridgeCool = 400;
                return false;
            }
            slot = toHotbar(mc, p, idx);
            swapped = idx;
        }
        stopMining(mc, p);
        bridgeTarget = t;
        escSlot = slot;
        escSwapIdx = swapped;
        escPrevSel = getSelected(p);
        escPitch = p.getXRot();
        setSelected(p, slot);
        brPlaced = 0;
        brCool = 0;
        guardTicks = 0;
        guard = G.BRIDGE;
        msg(p, "Bị nước bao quanh -> đặt block bắc cầu vào bờ.");
        return true;
    }

    static void tickBridge(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        guardTicks++;
        setSelected(p, escSlot);
        if (bridgeTarget == null) { finishBridge(mc, p, "mất mục tiêu", 200); return; }
        double dx = bridgeTarget.getX() + 0.5 - p.getX(), dz = bridgeTarget.getZ() + 0.5 - p.getZ();
        if (Math.hypot(dx, dz) < 1.2 && p.onGround() && !danger(p)) { finishBridge(mc, p, null, 100); return; }
        if (guardTicks > 800 || brPlaced >= BRIDGE_MAX) { finishBridge(mc, p, "chưa tới được bờ sau " + brPlaced + " block", 400); return; }
        if (p.isInLava()) { finishBridge(mc, p, "đang ở trong lava", 400); return; }
        if (guardTicks % 40 == 0) {
            BlockPos nt = findDry(lv, p, BRIDGE_RADIUS);
            if (nt != null) bridgeTarget = nt;
        }

        Direction dir = Math.abs(dx) >= Math.abs(dz)
            ? (dx > 0 ? Direction.EAST : Direction.WEST)
            : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        p.setYRot(dir.toYRot());
        p.setXRot(60f);
        BlockPos feet = p.blockPosition();
        BlockPos f = feet.relative(dir), b = f.below();
        if (lavaAt(lv, f) || lavaAt(lv, b)) { finishBridge(mc, p, "có lava phía trước", 400); return; }

        mc.options.keyJump.setDown(p.isInWater() && !p.onGround());     // đang bơi thì giữ nhảy để nổi
        if (!passable(lv, b) && passable(lv, f) && passable(lv, f.above())) {   // phía trước đã có nền -> đi tới
            mc.options.keyUp.setDown(true);
            return;
        }
        if (!passable(lv, f)) {                                          // gặp vách / bậc -> đi tới và nhảy
            mc.options.keyUp.setDown(true);
            mc.options.keyJump.setDown(true);
            return;
        }
        mc.options.keyUp.setDown(false);                                 // đứng yên đặt block
        if (brCool > 0) { brCool--; return; }

        // Đặt block vào ô nền phía trước (b). Ưu tiên đặt vào mặt bên của block đang đứng.
        BlockPos u = feet.below();
        BlockHitResult hit = null;
        if (!passable(lv, u)) {
            hit = new BlockHitResult(Vec3.atCenterOf(u).add(dir.getStepX() * 0.5, 0, dir.getStepZ() * 0.5), dir, u, false);
        } else {
            // đang bơi, chưa có nền dưới chân: đặt vào ô dưới chân, dựa vào block rắn bên cạnh nếu có
            for (Direction d : HORIZ) {
                BlockPos n = u.relative(d);
                if (!passable(lv, n)) {
                    hit = new BlockHitResult(Vec3.atCenterOf(n).add(-d.getStepX() * 0.5, 0, -d.getStepZ() * 0.5), d.getOpposite(), n, false);
                    break;
                }
            }
            if (hit == null) hit = new BlockHitResult(Vec3.atCenterOf(u), Direction.UP, u, false);
        }
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        brPlaced++;
        brCool = 3;
    }

    static void finishBridge(Minecraft mc, LocalPlayer p, String err, int cool) {
        mc.options.keyJump.setDown(false);
        if (escSwapIdx >= 0) { swapHotbar(mc, p, escSwapIdx, escSlot); escSwapIdx = -1; }
        setSelected(p, escPrevSel);
        if (!Float.isNaN(escPitch)) { p.setXRot(escPitch); escPitch = Float.NaN; }
        bridgeTarget = null;
        bridgeCool = cool;
        msg(p, err == null ? "Đã bắc cầu vào bờ (" + brPlaced + " block)." : "Bắc cầu dừng: " + err);
        endGuard(mc, p);
    }

    // ----- PICKUP (ưu tiên nhặt cát) -----
    static ItemEntity nearestSand(ClientLevel lv, LocalPlayer p) {
        var box = p.getBoundingBox().inflate(pickupRadius, 3, pickupRadius);
        List<ItemEntity> list = lv.getEntitiesOfClass(ItemEntity.class, box,
            e -> !e.isRemoved() && e.getItem().is(Items.SAND) && !pickIgnore.contains(e.getId()));
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : list) {
            double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
            double d = dx * dx + dz * dz;
            if (d < 1.4 * 1.4) continue;                        // quá gần: tự nhặt được rồi
            if (d < bestD) { best = e; bestD = d; }
        }
        return best;
    }

    static void startPickup(Minecraft mc, LocalPlayer p, ItemEntity it) {
        if (mode == Mode.BARITONE) send(p, BARITONE_STOP);       // dừng Baritone để mình tự đi nhặt
        pickTarget = it;
        pickTicks = 0;
        guard = G.PICKUP;
    }

    static void tickPickup(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        pickTicks++;
        ItemEntity it = pickTarget;
        if (it == null || it.isRemoved() || !it.getItem().is(Items.SAND) || isFull(p)
            || p.isInWater() || p.isInLava()) {
            endPickup(mc, p, 5);                                 // nhặt xong (hoặc hết chỗ chứa)
            return;
        }
        double dx = it.getX() - p.getX(), dz = it.getZ() - p.getZ();
        double d = Math.hypot(dx, dz);
        if (pickTicks > PICKUP_MAX_TICKS || (d > 0.01 && hazardDir(lv, p.getX(), p.getY(), p.getZ(), dx / d, dz / d))) {
            if (pickIgnore.size() > 60) pickIgnore.clear();
            pickIgnore.add(it.getId());                          // không tới được / có nguy hiểm -> bỏ món này
            endPickup(mc, p, 40);
            return;
        }
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot(0f);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.horizontalCollision || it.getY() > p.getY() + 0.6);
    }

    static void endPickup(Minecraft mc, LocalPlayer p, int cool) {
        mc.options.keyJump.setDown(false);
        releaseKeys(mc);
        guard = G.NONE;
        pickTarget = null;
        pickCool = cool;
        wdX = Double.NaN;
        startMining(p);
    }

    // ----- LIQUID -----
    static void tickLiquid(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        guardTicks++;
        if (!danger(p) && p.onGround()) {
            msg(p, "Đã lên bờ, đào tiếp.");
            endGuard(mc, p);
            return;
        }
        if (guardTicks > 400) {                                  // 20 giây vẫn chưa lên được -> giao lại cho Baritone
            msg(p, "Chưa lên được bờ sau 20 giây, thử đào tiếp.");
            endGuard(mc, p);
            return;
        }
        if (bridgeWater && guardTicks >= 60 && bridgeCool <= 0 && !p.isInLava()) {
            mc.options.keyJump.setDown(false);
            releaseKeys(mc);
            if (startBridge(mc, p)) return;                      // bơi 3 giây chưa lên được -> đặt block bắc cầu
        }
        if (shore == null || guardTicks % 10 == 1) shore = findShore(lv, p);
        mc.options.keyJump.setDown(true);                        // nhấn nhảy = bơi lên mặt nước / lava
        if (shore != null) {
            double dx = shore.getX() + 0.5 - p.getX(), dz = shore.getZ() + 0.5 - p.getZ();
            p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            p.setXRot(0f);
            mc.options.keyUp.setDown(true);
        } else {
            mc.options.keyUp.setDown(true);                      // không thấy bờ trong bán kính -> cứ bơi thẳng
        }
    }

    // ----- SIDE -----
    static void startSide(Minecraft mc, LocalPlayer p, ClientLevel lv, double heading) {
        stopMining(mc, p);
        double best = heading + 180;                             // không hướng nào an toàn thì quay đầu
        for (double off : new double[] {90, -90, 180}) {
            double r = Math.toRadians(heading + off);
            if (!hazardDir(lv, p.getX(), p.getY(), p.getZ(), -Math.sin(r), Math.cos(r))) { best = heading + off; break; }
        }
        p.setYRot((float) best);
        guard = G.SIDE;
        sideTicks = SIDE_TICKS;
        if (sideWindow <= 0) sideWindow = 300;
        sideStrikes++;
        if (sideStrikes == 1) msg(p, "Có nước / lava / hố phía trước -> né sang bên.");
        if (sideStrikes >= 4) {                                  // né liên tục -> có thể đường bắt buộc qua đó, nghỉ để khỏi lặp vô hạn
            sideStrikes = 0;
            sideWindow = 0;
            sideLock = 600;
            msg(p, "Né liên tục nhiều lần, tạm tắt né phía trước 30 giây.");
        }
    }

    static void tickSide(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        double r = Math.toRadians(p.getYRot());
        if (--sideTicks <= 0 || p.isInWater() || p.isInLava()
            || hazardDir(lv, p.getX(), p.getY(), p.getZ(), -Math.sin(r), Math.cos(r))) {
            endGuard(mc, p);
            return;
        }
        mc.options.keyUp.setDown(true);
    }

    // ----- ESCAPE -----
    static boolean startEscape(Minecraft mc, LocalPlayer p) {
        int slot = hotbarPlaceSlot(p);
        int swapped = -1;
        if (slot < 0) {
            int idx = findPlaceable(p);
            if (idx < 0) {
                msg(p, "Kẹt trong hố nhưng không có block để đặt làm bậc.");
                guardCool = GUARD_COOLDOWN;
                return false;
            }
            slot = toHotbar(mc, p, idx);
            swapped = idx;
        }
        stopMining(mc, p);                                       // để Baritone / Nuker không đổi ô cầm trên tay
        escSlot = slot;
        escSwapIdx = swapped;
        escPrevSel = getSelected(p);
        escPitch = p.getXRot();
        setSelected(p, slot);
        p.setXRot(90f);
        escBase = null;
        escPhase = 0;
        escPhaseTicks = 0;
        escPillars = 0;
        guardTicks = 0;
        guard = G.ESCAPE;
        msg(p, "Kẹt trong hố -> đặt cát dưới chân để leo lên.");
        return true;
    }

    static void tickEscape(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        guardTicks++;
        setSelected(p, escSlot);
        p.setXRot(90f);
        if (guardTicks > 400 || escPillars >= ESCAPE_MAX_PILLARS) {
            finishEscape(mc, p, "không thoát được sau " + escPillars + " lần đặt block");
            return;
        }
        BlockPos f = p.blockPosition();

        if (escPhase == 0) {                                     // đang đứng dưới đáy: kiểm tra rồi nhảy
            if (!p.onGround()) return;
            if (!passable(lv, f.above()) || !passable(lv, f.above(2))) { finishEscape(mc, p, "trên đầu bị chặn"); return; }
            if (!trapped(lv, f)) { finishEscape(mc, p, null); return; }
            escBase = f;
            escPhase = 1;
            escPhaseTicks = 0;
            mc.options.keyJump.setDown(true);
            return;
        }
        if (escPhase == 1) {                                     // đang bay lên: đủ cao thì đặt block vào ô chân
            escPhaseTicks++;
            mc.options.keyJump.setDown(escPhaseTicks <= 1);
            if (p.getY() >= escBase.getY() + 1.0) {
                Vec3 hit = new Vec3(escBase.getX() + 0.5, escBase.getY(), escBase.getZ() + 0.5);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, escBase.below(), false));
                p.swing(InteractionHand.MAIN_HAND);
                escPillars++;
                escPhase = 2;
                escPhaseTicks = 0;
            } else if (escPhaseTicks > 12 && p.onGround()) {
                escPhase = 0;                                    // nhảy không lên được -> thử lại
            }
            return;
        }
        escPhaseTicks++;                                         // escPhase == 2: chờ đáp xuống block vừa đặt
        if (p.onGround() || escPhaseTicks > 20) escPhase = 0;
    }

    static void finishEscape(Minecraft mc, LocalPlayer p, String err) {
        mc.options.keyJump.setDown(false);
        if (escSwapIdx >= 0) { swapHotbar(mc, p, escSwapIdx, escSlot); escSwapIdx = -1; }
        setSelected(p, escPrevSel);
        if (!Float.isNaN(escPitch)) { p.setXRot(escPitch); escPitch = Float.NaN; }
        msg(p, err == null ? "Đã thoát khỏi hố." : "Thoát hố: " + err);
        endGuard(mc, p);
    }

    // ============================== GUI MỚI ==============================
    static Component onOff(String name, boolean v) {
        return Component.literal(name + ": ")
            .append(Component.literal(v ? "BẬT" : "TẮT").withStyle(v ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    static Component val(String name, String value) {
        return Component.literal(name + ": ").append(Component.literal(value).withStyle(ChatFormatting.AQUA));
    }

    static String pickupLabel() {
        return pickupRadius <= 0 ? "Tắt" : pickupRadius <= 4 ? "Gần (4 ô)" : pickupRadius <= 8 ? "Vừa (8 ô)" : "Xa (12 ô)";
    }

    /** Trạng thái hiện tại bằng chữ dễ hiểu (hiện ở tab Chính). */
    static String stateText() {
        if (!enabled) return "Đang tắt";
        if (mode == Mode.BUILTIN && AutoMiner.recovering()) return AutoMiner.recoverText();
        if (state == S.MINING && guard != G.NONE) {
            return switch (guard) {
                case LIQUID -> "Đang thoát khỏi nước / lava";
                case ESCAPE -> "Đang đặt block leo khỏi hố";
                case SIDE -> "Đang né nguy hiểm";
                case PICKUP -> "Đang đi nhặt cát";
                case BRIDGE -> "Đang bắc cầu qua nước";
                default -> "Đang đào cát";
            };
        }
        return switch (state) {
            case MINING -> "Đang đào cát";
            case WAIT_ORDERS, WAIT_DELIVER, DEPOSIT, WAIT_CONFIRM, FINISH -> "Đang giao cát vào đơn";
            case MEND_PREP, MEND_THROW, MEND_RESTORE -> "Đang sửa xẻng bằng bình XP";
            case BUY_PREP, BUY_SHOP, BUY_GEAR, BUY_AMOUNT, BUY_CONFIRM, BUY_DONE ->
                buyFood ? "Đang mua đồ ăn" : "Đang mua bình XP";
            case EAT_PREP, EAT_USE -> "Đang ăn";
            case JUNK -> "Đang dọn đồ không cần";
        };
    }

    /** % độ bền xẻng thấp nhất trong túi; -1 nếu không có xẻng. */
    static int shovelPct(LocalPlayer p) {
        double best = 101;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty() || !st.is(ItemTags.SHOVELS) || !st.isDamageableItem()) continue;
            best = Math.min(best, remainingPct(st));
        }
        return best > 100 ? -1 : (int) Math.round(best);
    }

    // ============================== GUI BÓNG BẨY ==============================
    static int lerpC(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = 0;
        for (int s = 24; s >= 0; s -= 8) {
            int ca = (a >> s) & 255, cb = (b >> s) & 255;
            r |= (((int) (ca + (cb - ca) * t)) & 255) << s;
        }
        return r;
    }

    /** Hình chữ nhật bo góc. */
    static void rrect(GuiGraphics g, int x, int y, int w, int h, int r, int c) {
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) { g.fill(x, y, x + w, y + h, c); return; }
        g.fill(x, y + r, x + w, y + h - r, c);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int dx = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            g.fill(x + dx, y + i, x + w - dx, y + i + 1, c);
            g.fill(x + dx, y + h - i - 1, x + w - dx, y + h - i, c);
        }
    }

    static class AutoSandScreen extends Screen {
        static final int PW = 392, SIDE = 94;
        static final String[] TABS = {"Chính", "Đào cát", "Sinh tồn", "An toàn", "Key", "Tự đào"};
        static final int[] TAB_COL = {0xFF3B82F6, 0xFFF59E0B, 0xFF22C55E, 0xFFEF4444, 0xFFA855F7, 0xFF14B8A6};
        static final int TXT = 0xFFFFFFFF, MUTED = 0xFF8A94A6;

        /** Một điều khiển tự vẽ (nút thật của game nằm bên dưới, trong suốt, chỉ để nhận chuột). */
        static final class Ctl {
            int kind, x, y, w, h, color;        // 0 công tắc, 1 chọn giá trị, 2 nút thường, 3 nút nguồn, 4 tab, 5 đóng
            String title, desc;
            BooleanSupplier on;
            Supplier<String> value;
            float t = -1f;
            boolean selected;
        }

        final List<Ctl> ctls = new ArrayList<>();
        int tab = 0, x0, y0, ph, cx, cy, cw, availH, rowH = 30;

        AutoSandScreen() {
            super(Component.literal("AutoSand"));
        }

        Ctl add(int kind, int x, int y, int w, int h, String title, String desc, Runnable act) {
            Ctl c = new Ctl();
            c.kind = kind; c.x = x; c.y = y; c.w = w; c.h = h; c.title = title; c.desc = desc;
            Button b = Button.builder(Component.empty(), bb -> act.run()).bounds(x, y, w, h).build();
            b.setAlpha(0f);                      // trong suốt: hình vẽ do renderBackground lo
            addRenderableWidget(b);
            ctls.add(c);
            return c;
        }

        void begin(int n) {
            rowH = Math.max(20, Math.min(30, (availH - (n - 1) * 4) / n));
        }

        int cardY(int i) { return cy + i * (rowH + 4); }

        void toggle(int i, String title, String desc, BooleanSupplier get, Consumer<Boolean> set) {
            Ctl c = add(0, cx, cardY(i), cw, rowH, title, desc, () -> { set.accept(!get.getAsBoolean()); save(); });
            c.on = get;
        }

        void cycle(int i, String title, String desc, Supplier<String> value, Runnable next) {
            Ctl c = add(1, cx, cardY(i), cw, rowH, title, desc, () -> { next.run(); save(); });
            c.value = value;
        }

        @Override
        protected void init() {
            ctls.clear();
            ph = Math.min(this.height - 8, 262);
            x0 = this.width / 2 - PW / 2;
            y0 = this.height / 2 - ph / 2;
            cx = x0 + SIDE + 10;
            cw = PW - SIDE - 20;
            cy = y0 + 44;
            availH = ph - 44 - 10;

            for (int i = 0; i < TABS.length; i++) {
                final int t = i;
                Ctl c = add(4, x0 + 8, y0 + 44 + i * 28, SIDE - 14, 24, TABS[i], null, () -> { tab = t; rebuildWidgets(); });
                c.selected = (i == tab);
                c.color = TAB_COL[i];
            }
            add(5, x0 + PW - 26, y0 + 9, 16, 16, "x", null, () -> onClose());

            switch (tab) {
                case 0 -> add(3, cx, cy, cw, 36, "", null, () -> setEnabled(Minecraft.getInstance(), !enabled));
                case 1 -> {
                    begin(6);
                    cycle(0, "Chế độ đào", "Tự đào (có sẵn), giữ phím hoặc chỉ Nuker", () -> mode.label, () -> {
                        Minecraft mc = Minecraft.getInstance();
                        boolean running = enabled && state == S.MINING && mc.player != null;
                        if (running) stopMining(mc, mc.player);
                        mode = mode.next();
                        if (running) startMining(mc.player);
                    });
                    toggle(1, "Nuker", "Phá khối xung quanh bằng Nuker", () -> useNuker, v -> {
                        Minecraft mc = Minecraft.getInstance();
                        useNuker = v;
                        if (enabled && state == S.MINING && mc.player != null) send(mc.player, useNuker ? NUKER_ON : NUKER_OFF);
                    });
                    toggle(2, "Giữ phím tiến", "Chế độ Giữ phím: tự giữ W đi tới", () -> keysForward, v -> keysForward = v);
                    cycle(3, "Nhặt cát", "Ưu tiên nhặt cát rơi quanh bạn", AutoSandOrders::pickupLabel, () -> {
                        int next = PICKUP_LEVELS[0];
                        for (int v : PICKUP_LEVELS) {
                            if (v > pickupRadius) { next = v; break; }
                        }
                        pickupRadius = next;
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null && enabled) applyBaritoneSettings(mc.player);
                    });
                    toggle(4, "Tự giao cát", "Đầy túi -> /orders -> giao vào đơn cát", () -> autoDeliver, v -> autoDeliver = v);
                    toggle(5, "Tự bật lại khi dừng", "Baritone / Nuker dừng hoặc lỗi -> bật lại", () -> autoRevive, v -> autoRevive = v);
                }
                case 2 -> {
                    begin(7);
                    toggle(0, "Tự ăn khi đói", "Đói -> ngừng đào, ăn no rồi đào tiếp", () -> autoEat, v -> autoEat = v);
                    cycle(1, "Ăn khi đói <=", "Thanh đói từ mức này trở xuống thì ăn", () -> eatBelow + "/20", () -> {
                        int next = EAT_LEVELS[0];
                        for (int v : EAT_LEVELS) {
                            if (v > eatBelow) { next = v; break; }
                        }
                        eatBelow = next;
                    });
                    toggle(2, "Tự mua đồ ăn", "Hết đồ ăn -> /shop -> FOOD -> Cà Rốt Vàng", () -> autoBuyFood, v -> { autoBuyFood = v; foodBuyFails = 0; });
                    toggle(3, "Tự sửa xẻng", "Xẻng gần hư -> ném bình XP (cần Mending)", () -> autoMend, v -> autoMend = v);
                    cycle(4, "Sửa khi còn dưới", "Độ bền xẻng thấp hơn mức này thì sửa", () -> mendPct + "%", () -> {
                        int next = PCTS[0];
                        for (int v : PCTS) {
                            if (v > mendPct) { next = v; break; }
                        }
                        mendPct = next;
                    });
                    toggle(5, "Tự mua bình XP", "Hết bình XP -> mua ở /shop (chỉ vứt cát)", () -> autoBuy, v -> autoBuy = v);
                    toggle(6, "Vứt đồ không cần", "Giữ cát, bình XP, xẻng, đồ ăn. Vứt phần còn lại", () -> autoDrop, v -> autoDrop = v);
                }
                case 3 -> {
                    begin(3);
                    toggle(0, "Thoát nước sâu / lava", "Kẹt nước sâu hoặc lava -> tự bơi lên bờ", () -> avoidLiquid, v -> avoidLiquid = v);
                    toggle(1, "Bắc cầu qua nước", "Bị nước bao quanh -> đặt block đi vào bờ", () -> bridgeWater, v -> bridgeWater = v);
                    toggle(2, "Né hố & tự leo", "Rơi xuống hố -> tự đặt block leo lên", () -> avoidHole, v -> avoidHole = v);
                }
                case 5 -> {
                    begin(4);
                    toggle(0, "Chết -> về nhặt đồ", "Hồi sinh -> /home -> về chỗ chết nhặt lại đồ", () -> AutoMiner.deathRecover, v -> AutoMiner.deathRecover = v);
                    toggle(1, "Tự hồi sinh", "Chết thì tự bấm nút Hồi sinh", () -> AutoMiner.autoRespawn, v -> AutoMiner.autoRespawn = v);
                    toggle(2, "Đào xuyên khi đi", "Cát / đất chắn đường thì đào qua", () -> AutoMiner.digThrough, v -> AutoMiner.digThrough = v);
                    cycle(3, "Phạm vi tìm cát", "Tìm cát trong bán kính này", () -> AutoMiner.scanRadius + " ô", () -> {
                        int next = AutoMiner.SCAN_LEVELS[0];
                        for (int v : AutoMiner.SCAN_LEVELS) {
                            if (v > AutoMiner.scanRadius) { next = v; break; }
                        }
                        AutoMiner.scanRadius = next;
                    });
                }
                default -> add(2, cx, cy + 40, cw, 28, "Dán key từ clipboard", null, () -> {
                    Minecraft mc = Minecraft.getInstance();
                    activateKey(mc, mc.keyboardHandler.getClipboard());
                });
            }
        }

        void stat(GuiGraphics g, int x, int y, int w, int h, String label, String value, int vcol, float bar, int barCol) {
            rrect(g, x, y, w, h, 4, 0xFF252F45);
            rrect(g, x + 1, y + 1, w - 2, h - 2, 3, 0xFF182032);
            g.drawString(this.font, label, x + 8, y + 5, MUTED, false);
            g.drawString(this.font, value, x + 8, y + 16, vcol, false);
            if (bar >= 0f) {
                rrect(g, x + 8, y + h - 7, w - 16, 3, 1, 0xFF2A3347);
                rrect(g, x + 8, y + h - 7, Math.max(2, (int) ((w - 16) * Math.min(1f, bar))), 3, 1, barCol);
            }
        }

        void bullet(GuiGraphics g, String s, int x, int y) {
            g.fill(x, y + 3, x + 3, y + 6, 0xFF3B82F6);
            g.drawString(this.font, s, x + 8, y, 0xFFB6C0D0, false);
        }

        @Override
        public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
            super.renderBackground(g, mx, my, pt);
            g.fillGradient(0, 0, this.width, this.height, 0x66050A18, 0xAA0A1030);

            // ----- bóng đổ + khung bảng -----
            for (int i = 6; i >= 1; i--) {
                rrect(g, x0 - i, y0 - i, PW + 2 * i, ph + 2 * i, 8 + i, ((0x1C - i * 3) << 24) | 0x3B5BDB);
            }
            rrect(g, x0, y0, PW, ph, 8, 0xFF3B5BDB);
            rrect(g, x0 + 1, y0 + 1, PW - 2, ph - 2, 7, 0xFF0F1420);
            g.fillGradient(x0 + 4, y0 + 1, x0 + PW - 4, y0 + 34, 0xFF222E4C, 0xFF141B2D);       // thanh tiêu đề
            g.fillGradient(x0 + 8, y0 + 35, x0 + PW - 8, y0 + 36 + (ph - 40) / 3, 0x12FFFFFF, 0x00FFFFFF); // ánh bóng
            for (int i = 0; i < 48; i++) {                                                         // vạch màu dưới tiêu đề
                g.fill(x0 + 6 + i * (PW - 12) / 48, y0 + 34, x0 + 6 + (i + 1) * (PW - 12) / 48, y0 + 36,
                    lerpC(0xFF38BDF8, 0xFFC084FC, i / 47f));
            }
            g.fill(x0 + SIDE, y0 + 44, x0 + SIDE + 1, y0 + ph - 10, 0xFF252F45);                  // đường kẻ dọc

            // ----- tiêu đề chữ chuyển màu -----
            String title = "AutoSand";
            int tx = x0 + 14;
            for (int i = 0; i < title.length(); i++) {
                String ch = title.substring(i, i + 1);
                g.drawString(this.font, ch, tx, y0 + 13, lerpC(0xFF60A5FA, 0xFFC084FC, i / (float) (title.length() - 1)), true);
                tx += this.font.width(ch);
            }
            g.drawString(this.font, mode.label, tx + 8, y0 + 13, MUTED, false);

            String run = enabled ? "ĐANG CHẠY" : "ĐANG TẮT";
            int chipW = this.font.width(run) + 24, chipX = x0 + PW - 34 - chipW, chipY = y0 + 9;
            int chipCol = enabled ? 0xFF22C55E : 0xFFEF4444;
            rrect(g, chipX, chipY, chipW, 16, 8, chipCol);
            rrect(g, chipX + 1, chipY + 1, chipW - 2, 14, 7, enabled ? 0xFF10301F : 0xFF35151A);
            rrect(g, chipX + 7, chipY + 5, 6, 6, 3, chipCol);
            g.drawString(this.font, run, chipX + 17, chipY + 4, chipCol, false);

            // ----- nội dung riêng từng tab -----
            LocalPlayer p = Minecraft.getInstance().player;
            if (tab == 0) {
                rrect(g, cx, cy + 42, cw, 24, 4, 0xFF252F45);
                rrect(g, cx + 1, cy + 43, cw - 2, 22, 3, 0xFF182032);
                rrect(g, cx + 9, cy + 51, 6, 6, 3, enabled ? 0xFF22C55E : 0xFF6B7686);
                g.drawString(this.font, stateText(), cx + 22, cy + 50, TXT, false);

                int sw = (cw - 8) / 3;
                if (p != null) {
                    int sp = shovelPct(p), food = p.getFoodData().getFoodLevel();
                    int spCol = sp > 50 ? 0xFF22C55E : sp > 20 ? 0xFFF59E0B : 0xFFEF4444;
                    stat(g, cx, cy + 72, sw, 36, "Cát", String.valueOf(countItem(p, Items.SAND)), TXT, -1f, 0);
                    stat(g, cx + sw + 4, cy + 72, sw, 36, "Bình XP", String.valueOf(countItem(p, Items.EXPERIENCE_BOTTLE)), TXT, -1f, 0);
                    stat(g, cx + 2 * (sw + 4), cy + 72, sw, 36, "Đồ ăn", String.valueOf(countFood(p)), TXT, -1f, 0);
                    stat(g, cx, cy + 112, sw, 36, "Xẻng", sp < 0 ? "không có" : sp + "%", TXT, sp < 0 ? -1f : sp / 100f, spCol);
                    stat(g, cx + sw + 4, cy + 112, sw, 36, "Đói", food + "/20", TXT, food / 20f, food <= eatBelow ? 0xFFEF4444 : 0xFFF59E0B);
                    stat(g, cx + 2 * (sw + 4), cy + 112, sw, 36, "Nhặt cát", pickupLabel(), TXT, -1f, 0);
                } else {
                    g.drawString(this.font, "Chưa vào thế giới.", cx + 4, cy + 80, MUTED, false);
                }
                rrect(g, cx, cy + 152, cw, 24, 4, 0xFF252F45);
                rrect(g, cx + 1, cy + 153, cw - 2, 22, 3, 0xFF182032);
                g.drawString(this.font, "Key: " + License.status(), cx + 9, cy + 160, License.valid() ? 0xFF22C55E : 0xFFEF4444, false);
            } else if (tab == 3) {
                int iy = cardY(3) + 6;
                rrect(g, cx, iy, cw, 66, 4, 0xFF252F45);
                rrect(g, cx + 1, iy + 1, cw - 2, 64, 3, 0xFF182032);
                bullet(g, "Kẹt nước sâu / lava: tự bơi lên bờ.", cx + 10, iy + 8);
                bullet(g, "Bị nước bao quanh: đặt cát bắc cầu vào bờ.", cx + 10, iy + 22);
                bullet(g, "Rơi xuống hố: nhảy + đặt cát để leo, không đào lên.", cx + 10, iy + 36);
                bullet(g, "Né nước/hố phía trước: chỉ ở chế độ ngoài Baritone.", cx + 10, iy + 50);
            } else if (tab == 5) {
                int iy = cardY(4) + 6;
                rrect(g, cx, iy, cw, 50, 4, 0xFF252F45);
                rrect(g, cx + 1, iy + 1, cw - 2, 48, 3, 0xFF182032);
                bullet(g, "Sau khi chết: /" + AutoMiner.homeCmd, cx + 10, iy + 8);
                bullet(g, AutoMiner.deathText(), cx + 10, iy + 22);
                bullet(g, "Xong: xẻng về thanh công cụ.", cx + 10, iy + 36);
            } else if (tab == 4) {
                rrect(g, cx, cy, cw, 32, 4, 0xFF252F45);
                rrect(g, cx + 1, cy + 1, cw - 2, 30, 3, 0xFF182032);
                g.drawString(this.font, "Trạng thái key", cx + 9, cy + 5, MUTED, false);
                g.drawString(this.font, License.status(), cx + 9, cy + 18, License.valid() ? 0xFF22C55E : 0xFFEF4444, false);
                int iy = cy + 76;
                rrect(g, cx, iy, cw, 60, 4, 0xFF252F45);
                rrect(g, cx + 1, iy + 1, cw - 2, 58, 3, 0xFF182032);
                bullet(g, "Copy key rồi bấm nút phía trên.", cx + 10, iy + 8);
                bullet(g, "Hoặc gõ lệnh: /autosand key <key>", cx + 10, iy + 22);
                bullet(g, "Mua key: Zalo " + License.ZALO, cx + 10, iy + 36);
            }

            // ----- vẽ các điều khiển -----
            for (Ctl c : ctls) drawCtl(g, c, mx, my);

            g.drawString(this.font, "ESC để đóng", x0 + 10, y0 + ph - 18, 0xFF5B6678, false);
        }

        void drawCtl(GuiGraphics g, Ctl c, int mx, int my) {
            boolean hov = mx >= c.x && mx < c.x + c.w && my >= c.y && my < c.y + c.h;
            switch (c.kind) {
                case 0, 1 -> {
                    rrect(g, c.x, c.y, c.w, c.h, 4, hov ? 0xFF3B5BDB : 0xFF252F45);
                    rrect(g, c.x + 1, c.y + 1, c.w - 2, c.h - 2, 3, hov ? 0xFF222C42 : 0xFF182032);
                    g.fillGradient(c.x + 3, c.y + 1, c.x + c.w - 3, c.y + 1 + c.h / 2, 0x10FFFFFF, 0x00FFFFFF);
                    boolean showDesc = c.desc != null && c.h >= 26;
                    int ty = showDesc ? c.y + 4 : c.y + (c.h - 8) / 2;
                    if (c.kind == 0) {
                        boolean on = c.on != null && c.on.getAsBoolean();
                        float target = on ? 1f : 0f;
                        if (c.t < 0f) c.t = target;
                        c.t += Math.max(-0.2f, Math.min(0.2f, target - c.t));
                        rrect(g, c.x + 1, c.y + 4, 3, c.h - 8, 1, on ? 0xFF22C55E : 0xFF3A4254);
                        int sx = c.x + c.w - 38, sy = c.y + (c.h - 14) / 2;
                        rrect(g, sx, sy, 28, 14, 7, lerpC(0xFF3A4254, 0xFF22C55E, c.t));
                        rrect(g, sx + 2 + (int) (c.t * 14), sy + 2, 10, 10, 5, 0xFFFFFFFF);
                    } else {
                        rrect(g, c.x + 1, c.y + 4, 3, c.h - 8, 1, 0xFF3B82F6);
                        String v = c.value == null ? "" : c.value.get();
                        int chipW = this.font.width(v) + 16, chipX = c.x + c.w - chipW - 8, chipY = c.y + (c.h - 16) / 2;
                        rrect(g, chipX, chipY, chipW, 16, 5, hov ? 0xFF3B82F6 : 0xFF2B4A9A);
                        g.drawString(this.font, v, chipX + 8, chipY + 4, TXT, false);
                    }
                    g.drawString(this.font, c.title, c.x + 11, ty, TXT, false);
                    if (showDesc) g.drawString(this.font, c.desc, c.x + 11, c.y + 15, MUTED, false);
                }
                case 2 -> {
                    rrect(g, c.x, c.y, c.w, c.h, 5, hov ? 0xFF7C9CFF : 0xFF3B5BDB);
                    rrect(g, c.x + 1, c.y + 1, c.w - 2, c.h - 2, 4, hov ? 0xFF3B5BDB : 0xFF2A43A8);
                    g.fillGradient(c.x + 3, c.y + 1, c.x + c.w - 3, c.y + 1 + c.h / 2, 0x28FFFFFF, 0x00FFFFFF);
                    g.drawString(this.font, c.title, c.x + (c.w - this.font.width(c.title)) / 2, c.y + (c.h - 8) / 2, TXT, true);
                }
                case 3 -> {
                    float target = enabled ? 1f : 0f;
                    if (c.t < 0f) c.t = target;
                    c.t += Math.max(-0.12f, Math.min(0.12f, target - c.t));
                    int edge = lerpC(0xFFEF4444, 0xFF22C55E, c.t);
                    int fill = lerpC(hov ? 0xFF4A1C26 : 0xFF35151A, hov ? 0xFF14452D : 0xFF10301F, c.t);
                    for (int i = 4; i >= 1; i--) rrect(g, c.x - i, c.y - i, c.w + 2 * i, c.h + 2 * i, 6 + i, ((0x20 - i * 5) << 24) | (edge & 0xFFFFFF));
                    rrect(g, c.x, c.y, c.w, c.h, 6, edge);
                    rrect(g, c.x + 1, c.y + 1, c.w - 2, c.h - 2, 5, fill);
                    g.fillGradient(c.x + 4, c.y + 1, c.x + c.w - 4, c.y + 1 + c.h / 2, 0x26FFFFFF, 0x00FFFFFF);
                    Component label = Component.literal(enabled ? "ĐANG CHẠY  -  bấm để TẮT" : "ĐANG TẮT  -  bấm để BẬT").withStyle(ChatFormatting.BOLD);
                    g.drawString(this.font, label, c.x + (c.w - this.font.width(label)) / 2, c.y + (c.h - 8) / 2, edge, true);
                }
                case 4 -> {
                    if (c.selected) {
                        rrect(g, c.x, c.y, c.w, c.h, 5, c.color);
                        rrect(g, c.x + 1, c.y + 1, c.w - 2, c.h - 2, 4, lerpC(0xFF0F1420, c.color, 0.28f));
                        rrect(g, c.x + 1, c.y + 5, 3, c.h - 10, 1, c.color);
                    } else if (hov) {
                        rrect(g, c.x, c.y, c.w, c.h, 5, 0xFF1B2437);
                    }
                    rrect(g, c.x + 10, c.y + (c.h - 6) / 2, 6, 6, 3, c.color);
                    g.drawString(this.font, c.title, c.x + 22, c.y + (c.h - 8) / 2, c.selected ? TXT : (hov ? 0xFFD5DCE8 : MUTED), false);
                }
                default -> {
                    rrect(g, c.x, c.y, c.w, c.h, 8, hov ? 0xFFEF4444 : 0xFF2A3347);
                    g.drawString(this.font, c.title, c.x + (c.w - this.font.width(c.title)) / 2, c.y + (c.h - 8) / 2, TXT, false);
                }
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
