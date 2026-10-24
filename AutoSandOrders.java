package autosand;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;
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
 * Key:    cần key (ngày/tuần/tháng) mới bật được. /autosand hwid | key <key> | status  (xem License.java)
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
        BARITONE("Baritone"), KEYS("Giữ phím"), NUKER("Chỉ Nuker");
        final String label;
        Mode(String l) { label = l; }
        Mode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    static Mode mode = Mode.BARITONE;
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
                .then(ClientCommandManager.literal("hwid")
                    .executes(ctx -> {
                        Minecraft.getInstance().keyboardHandler.setClipboard(License.hwid());
                        ctx.getSource().sendFeedback(Component.literal("[AutoSand] HWID: " + License.hwid()
                            + " (đã copy). Nhắn Zalo " + License.ZALO + " kèm HWID + loại key (ngày/tuần/tháng) để lấy key."));
                        return 1;
                    }))
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
            try { mode = Mode.valueOf(pr.getProperty("mode", "BARITONE")); } catch (Exception e) { mode = Mode.BARITONE; }
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
            ORDER_COMMAND = pr.getProperty("orderCmd", ORDER_COMMAND);
            SHOP_COMMAND = pr.getProperty("shopCmd", SHOP_COMMAND);
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
            pr.setProperty("orderCmd", ORDER_COMMAND);
            pr.setProperty("shopCmd", SHOP_COMMAND);
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
        if (p != null) {
            if (on) {
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
        if (useNuker) send(p, NUKER_ON);
        if (mode == Mode.BARITONE) send(p, BARITONE_MINE);
    }

    static void stopMining(Minecraft mc, LocalPlayer p) {
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
        if (!enabled) return;

        License.poll();
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

        if (state == S.MINING) {
            if (mc.screen != null) return;
            if (mode == Mode.KEYS) {
                mc.options.keyAttack.setDown(true);
                mc.options.keyUp.setDown(keysForward);
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
        if (mc.player != null) msg(mc.player, "Đang kiểm tra key...");
        License.activateAsync(key, r -> mc.execute(() -> { if (mc.player != null) msg(mc.player, r); }));
    }

    static void needKeyMsg(LocalPlayer p) {
        msg(p, "Cần key để dùng. Nhắn Zalo " + License.ZALO + " kèm HWID: " + License.hwid()
            + " và loại key (ngày/tuần/tháng). Có key thì gõ /autosand key <key>.");
    }

    static void msg(LocalPlayer p, String t) {
        p.displayClientMessage(Component.literal("[AutoSand] " + t), false);
    }

    // ============================== HỆ THỐNG KEY (LICENSE) ==============================
    /**
     * Hệ thống key cho AutoSand (chỉ dùng JDK, không phụ thuộc Minecraft).
     *
     * - Key do CHỦ tool tạo bằng KeyGen (giữ private key ở máy bạn, KHÔNG đưa vào mod).
     * - Mỗi key được ký Ed25519 và GẮN VỚI 1 MÁY (HWID) + THỜI HẠN TUYỆT ĐỐI (ngày / tuần / tháng).
     *   -> Không thể làm giả key, không dùng chung cho máy khác, hết hạn là chết hẳn, không dùng lại được.
     * - Giờ được lấy từ Internet (header Date của HTTPS) + đồng hồ nanoTime (không bị chỉnh được) để chống lùi giờ máy.
     */
    static final class License {
        private License() { }

        private static final byte[] ZALO_ENC = {85, 86, 92, 82, 83, 80, 82, 87, 80, 85}; // XOR("0397657250", 0x65)
        public static final String ZALO = deobf(ZALO_ENC);

        /** Dán public key (do `java KeyGen.java genpair` in ra) vào đây. */
        private static final String PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAtxxbnLFOwiBxighydzyT7BJukyEvV2Z4HE4BwgxMsr0=";

        private static final byte[] DOMAIN_ENC = {36, 16, 17, 10, 54, 4, 11, 1, 74, 14, 0, 28, 74, 19, 84}; // XOR("AutoSand/key/v1", 0x65)
        private static final byte[] DOMAIN = deobf(DOMAIN_ENC).getBytes(StandardCharsets.UTF_8);

        private static String deobf(byte[] enc) {
            char[] c = new char[enc.length];
            for (int i = 0; i < enc.length; i++) c[i] = (char) (enc[i] ^ 0x65);
            return new String(c);
        }
        private static final int PAYLOAD_LEN = 22;   // ver(1) type(1) issued(4) expires(4) hwid(8) id(4)
        private static final int SIG_LEN = 64;
        private static final String[] TYPE_NAME = {"?", "Ngày", "Tuần", "Tháng"};
        private static final long RESYNC_NANOS = 10L * 60 * 1_000_000_000L;

        private record Info(int type, long issued, long expires, byte[] hw) { }

        private static Path file;
        private static volatile String storedKey = null;
        private static volatile long lastSeen = 0;          // mốc giờ lớn nhất đã thấy (chống lùi đồng hồ khi offline)
        private static volatile long[] clock = null;        // {giây online, nanoTime lúc đồng bộ}
        private static volatile boolean syncing = false;
        private static volatile long nextSyncAt = System.nanoTime();
        private static volatile long lastSaved = 0;
        private static volatile String hwidCache = null;

        // ----------------------------- KHỞI TẠO / LƯU -----------------------------
        public static void init(Path configDir) {
            file = configDir.resolve("autosand.lic");
            try {
                if (Files.exists(file)) {
                    List<String> l = Files.readAllLines(file, StandardCharsets.UTF_8);
                    if (!l.isEmpty()) storedKey = l.get(0).trim();
                    if (l.size() > 1) lastSeen = Long.parseLong(l.get(1).trim());
                }
            } catch (Exception ignored) { }
            poll();
        }

        private static void save() {
            try {
                if (file == null) return;
                String k = storedKey == null ? "" : storedKey;
                Files.write(file, List.of(k, String.valueOf(lastSeen)), StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
        }

        // ----------------------------- THỜI GIAN TIN CẬY -----------------------------
        private static long now() {
            long[] c = clock;
            long t;
            if (c != null) t = c[0] + (System.nanoTime() - c[1]) / 1_000_000_000L;
            else t = Math.max(System.currentTimeMillis() / 1000, lastSeen);
            if (t > lastSeen) {
                lastSeen = t;
                if (t - lastSaved >= 60) { lastSaved = t; save(); }
            }
            return t;
        }

        private static boolean syncTime() {
            String[] urls = {"https://www.google.com", "https://www.cloudflare.com", "https://www.microsoft.com"};
            for (String u : urls) {
                try {
                    HttpURLConnection c = (HttpURLConnection) URI.create(u).toURL().openConnection();
                    c.setRequestMethod("HEAD");
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.setInstanceFollowRedirects(false);
                    long d = c.getDate();
                    c.disconnect();
                    if (d > 0) {
                        clock = new long[]{d / 1000, System.nanoTime()};
                        return true;
                    }
                } catch (Exception ignored) { }
            }
            return false;
        }

        /** Gọi mỗi tick: tự đồng bộ giờ ngầm mỗi 10 phút. */
        public static void poll() {
            if (syncing || System.nanoTime() - nextSyncAt < 0) return;
            syncing = true;
            Thread t = new Thread(() -> {
                try { syncTime(); } finally {
                    nextSyncAt = System.nanoTime() + RESYNC_NANOS;
                    syncing = false;
                }
            }, "autosand-time");
            t.setDaemon(true);
            t.start();
        }

        // ----------------------------- HWID -----------------------------
        public static String hwid() {
            String c = hwidCache;
            if (c != null) return c;
            StringBuilder s = new StringBuilder("AutoSand-HW-v1");
            s.append('|').append(System.getProperty("os.name")).append('|').append(System.getProperty("os.arch"));
            s.append('|').append(System.getProperty("user.name"));
            try { s.append('|').append(InetAddress.getLocalHost().getHostName()); } catch (Exception ignored) { }
            String mac = "";
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    byte[] m = ni.getHardwareAddress();
                    if (m == null || m.length != 6 || ni.isLoopback() || ni.isVirtual()) continue;
                    String n = (ni.getDisplayName() + ni.getName()).toLowerCase();
                    if (n.contains("vmware") || n.contains("virtual") || n.contains("vbox") || n.contains("vethernet")
                        || n.contains("hyper-v") || n.contains("tap") || n.contains("tun") || n.contains("docker")) continue;
                    StringBuilder h = new StringBuilder();
                    for (byte x : m) h.append(String.format("%02x", x));
                    if (mac.isEmpty() || h.toString().compareTo(mac) < 0) mac = h.toString();
                }
            } catch (Exception ignored) { }
            s.append('|').append(mac);
            byte[] d = sha256(s.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0 && i % 2 == 0) r.append('-');
                r.append(String.format("%02X", d[i]));
            }
            return hwidCache = r.toString();
        }

        private static byte[] hwBytes() {
            String h = hwid().replace("-", "");
            byte[] b = new byte[8];
            for (int i = 0; i < 8; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            return b;
        }

        private static byte[] sha256(byte[] in) {
            try { return MessageDigest.getInstance("SHA-256").digest(in); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }

        // ----------------------------- KIỂM TRA KEY -----------------------------
        private static boolean verify(byte[] payload, byte[] sig) {
            try {
                PublicKey pk = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_B64)));
                Signature s = Signature.getInstance("Ed25519");
                s.initVerify(pk);
                s.update(DOMAIN);
                s.update(payload);
                return s.verify(sig);
            } catch (Exception e) {
                return false;
            }
        }

        private static Info parse(String key) {
            if (PUBLIC_KEY_B64.startsWith("PASTE")) throw new IllegalArgumentException("Mod chưa được cấu hình public key.");
            byte[] raw;
            try { raw = Base64.getUrlDecoder().decode(key.trim()); }
            catch (Exception e) { throw new IllegalArgumentException("Key sai định dạng."); }
            if (raw.length != PAYLOAD_LEN + SIG_LEN) throw new IllegalArgumentException("Key sai định dạng.");
            byte[] payload = Arrays.copyOfRange(raw, 0, PAYLOAD_LEN);
            byte[] sig = Arrays.copyOfRange(raw, PAYLOAD_LEN, raw.length);
            if (!verify(payload, sig)) throw new IllegalArgumentException("Key không hợp lệ.");
            ByteBuffer b = ByteBuffer.wrap(payload);
            int ver = b.get() & 0xff;
            int type = b.get() & 0xff;
            long issued = b.getInt() & 0xffffffffL;
            long expires = b.getInt() & 0xffffffffL;
            byte[] hw = new byte[8];
            b.get(hw);
            if (ver != 1 || type < 1 || type > 3) throw new IllegalArgumentException("Key không hợp lệ.");
            return new Info(type, issued, expires, hw);
        }

        private static boolean forThisMachine(Info i) {
            return MessageDigest.isEqual(i.hw(), hwBytes());
        }

        /** Key đang lưu còn hợp lệ không (đúng chữ ký, đúng máy, chưa hết hạn). */
        public static boolean valid() {
            String k = storedKey;
            if (k == null || k.isEmpty()) return false;
            try {
                Info i = parse(k);
                return forThisMachine(i) && now() < i.expires() && now() >= i.issued() - 300;
            } catch (Exception e) {
                return false;
            }
        }

        public static String status() {
            String k = storedKey;
            if (k == null || k.isEmpty()) return "chưa kích hoạt";
            try {
                Info i = parse(k);
                if (!forThisMachine(i)) return "key không đúng máy";
                long left = i.expires() - now();
                if (left <= 0) return "key " + TYPE_NAME[i.type()] + " đã hết hạn";
                return "key " + TYPE_NAME[i.type()] + ", còn " + fmt(left);
            } catch (Exception e) {
                return "key không hợp lệ";
            }
        }

        private static String fmt(long sec) {
            return (sec / 86400) + "d " + (sec % 86400 / 3600) + "h " + (sec % 3600 / 60) + "m";
        }

        // ----------------------------- KÍCH HOẠT -----------------------------
        /** Kích hoạt trong luồng nền (không làm đơ game); cb nhận thông báo kết quả (gọi từ luồng nền). */
        public static void activateAsync(String key, Consumer<String> cb) {
            Thread t = new Thread(() -> {
                String r;
                try { r = activate(key); } catch (Throwable e) { r = "Lỗi kích hoạt: " + e.getMessage(); }
                cb.accept(r);
            }, "autosand-lic");
            t.setDaemon(true);
            t.start();
        }

        private static String activate(String key) {
            key = key == null ? "" : key.replaceAll("\\s+", "");
            Info i;
            try { i = parse(key); } catch (IllegalArgumentException e) { return e.getMessage(); }
            if (!forThisMachine(i)) {
                return "Key này không dành cho máy này (HWID không khớp). Nhắn Zalo " + ZALO + " để lấy key đúng máy.";
            }
            if (!syncTime()) return "Cần kết nối Internet để kích hoạt key.";
            if (now() >= i.expires()) {
                return "Key đã hết hạn / đã dùng, không thể dùng lại. Nhắn Zalo " + ZALO + " để lấy key mới.";
            }
            storedKey = key;
            save();
            return "Kích hoạt thành công: key " + TYPE_NAME[i.type()] + ", còn " + fmt(i.expires() - now()) + ".";
        }
    }

    // ============================== GUI TUỲ CHỈNH ==============================
    static Component onOff(String name, boolean v) {
        return Component.literal(name + ": " + (v ? "BẬT" : "TẮT"));
    }

    static class AutoSandScreen extends Screen {

        AutoSandScreen() {
            super(Component.literal("AutoSand"));
        }

        @Override
        protected void init() {
            int w = 230, count = 18;
            int gap = Math.max(14, Math.min(21, (this.height - 12) / count));
            int h = gap - 3;
            int x = this.width / 2 - w / 2;
            int y = Math.max(6, this.height / 2 - (gap * count) / 2);

            Button head = Button.builder(Component.literal("=== AutoSand ==="), b -> { })
                .bounds(x, y, w, h).build();
            head.active = false;
            addRenderableWidget(head);
            y += gap;

            Button st = Button.builder(Component.literal("Key: " + License.status()), b -> { })
                .bounds(x, y, w, h).build();
            st.active = false;
            addRenderableWidget(st);
            y += gap;

            addRenderableWidget(Button.builder(Component.literal("Copy HWID (gửi Zalo " + License.ZALO + ")"), b -> {
                Minecraft mc = Minecraft.getInstance();
                mc.keyboardHandler.setClipboard(License.hwid());
                if (mc.player != null) msg(mc.player, "Đã copy HWID: " + License.hwid());
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(Component.literal("Kích hoạt key từ clipboard"), b -> {
                Minecraft mc = Minecraft.getInstance();
                activateKey(mc, mc.keyboardHandler.getClipboard());
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(onOff("AutoSand", enabled), b -> {
                setEnabled(Minecraft.getInstance(), !enabled);
                b.setMessage(onOff("AutoSand", enabled));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(Component.literal("Chế độ đào: " + mode.label), b -> {
                Minecraft mc = Minecraft.getInstance();
                boolean running = enabled && state == S.MINING && mc.player != null;
                if (running) stopMining(mc, mc.player);
                mode = mode.next();
                if (running) startMining(mc.player);
                save();
                b.setMessage(Component.literal("Chế độ đào: " + mode.label));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(onOff("Nuker", useNuker), b -> {
                Minecraft mc = Minecraft.getInstance();
                useNuker = !useNuker;
                if (enabled && state == S.MINING && mc.player != null) {
                    send(mc.player, useNuker ? NUKER_ON : NUKER_OFF);
                }
                save();
                b.setMessage(onOff("Nuker", useNuker));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(onOff("Giữ phím tiến (chế độ Giữ phím)", keysForward), b -> {
                keysForward = !keysForward;
                save();
                b.setMessage(onOff("Giữ phím tiến (chế độ Giữ phím)", keysForward));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(onOff("Tự giao cát khi đầy", autoDeliver), b -> {
                autoDeliver = !autoDeliver;
                save();
                b.setMessage(onOff("Tự giao cát khi đầy", autoDeliver));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(onOff("Tự sửa xẻng", autoMend), b -> {
                autoMend = !autoMend;
                save();
                b.setMessage(onOff("Tự sửa xẻng", autoMend));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(Component.literal("Sửa khi xẻng còn dưới: " + mendPct + "%"), b -> {
                int next = PCTS[0];
                for (int v : PCTS) {
                    if (v > mendPct) { next = v; break; }
                }
                mendPct = next;
                save();
                b.setMessage(Component.literal("Sửa khi xẻng còn dưới: " + mendPct + "%"));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(onOff("Tự mua bình XP", autoBuy), b -> {
                autoBuy = !autoBuy;
                save();
                b.setMessage(onOff("Tự mua bình XP", autoBuy));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(onOff("Tự ăn khi đói", autoEat), b -> {
                autoEat = !autoEat;
                save();
                b.setMessage(onOff("Tự ăn khi đói", autoEat));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(Component.literal("Ăn khi thanh đói <= " + eatBelow + "/20"), b -> {
                int next = EAT_LEVELS[0];
                for (int v : EAT_LEVELS) {
                    if (v > eatBelow) { next = v; break; }
                }
                eatBelow = next;
                save();
                b.setMessage(Component.literal("Ăn khi thanh đói <= " + eatBelow + "/20"));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(onOff("Tự mua đồ ăn khi hết", autoBuyFood), b -> {
                autoBuyFood = !autoBuyFood;
                foodBuyFails = 0;
                save();
                b.setMessage(onOff("Tự mua đồ ăn khi hết", autoBuyFood));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(onOff("Vứt đồ không phải cát", autoDrop), b -> {
                autoDrop = !autoDrop;
                save();
                b.setMessage(onOff("Vứt đồ không phải cát", autoDrop));
            }).bounds(x, y, w, h).build());
            y += gap;

            addRenderableWidget(Button.builder(Component.literal("Đóng"), b ->
                Minecraft.getInstance().setScreen(null)
            ).bounds(x, y, w, h).build());
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
