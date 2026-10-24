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
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * AutoSandOrders (Fabric 1.21.11, Mojang mappings)
 *
 * 1) Đào cát (Baritone / giữ phím / chỉ Nuker) -> đầy túi -> /orders -> giao cát vào đơn đầu tiên -> xác nhận
 *    -> thoát các bảng server mở lại (thêm FINISH_CLOSES lần) -> đào tiếp.
 * 2) Xẻng gần hư -> bình XP vào ô 2 thanh công cụ, xẻng vào tay trái, tay phải ném bình -> xẻng về lại.
 * 3) Hết bình XP -> vứt cát đang nằm trên thanh công cụ -> /shop -> ô totem -> bình XP -> đặt 64 -> xác nhận,
 *    lặp lại cho tới khi thanh công cụ đầy bình XP.
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

    static final int CONFIRM_SLOT = 16;     // màn xác nhận giao cát (dự phòng nếu không tìm thấy theo tên)
    static final int BOTTLE_SLOT = 1;       // ô số 2 trên thanh công cụ (đếm từ 0)
    static final int MEND_TARGET_PCT = 100; // sửa xẻng tới bao nhiêu % (100 = đầy). Hạ xuống để tiết kiệm bình

    static final int SHOP_TOTEM_SLOT = 13;  // /shop: ô totem
    static final int SHOP_BOTTLE_SLOT = 16; // SHOP -> GEAR: ô bình XP
    static final int BUY_SET64_SLOT = 17;   // "đặt thành 64"
    static final int BUY_CONFIRM_SLOT = 23; // "xác nhận"
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
    static String ORDER_COMMAND = "orders sand";
    static String SHOP_COMMAND = "shop";
    static final int[] PCTS = {5, 10, 20, 30};
    // ===========================================================================

    enum S {
        MINING, WAIT_ORDERS, WAIT_DELIVER, DEPOSIT, WAIT_CONFIRM, FINISH,
        MEND_PREP, MEND_THROW, MEND_RESTORE,
        BUY_PREP, BUY_SHOP, BUY_GEAR, BUY_AMOUNT, BUY_CONFIRM, BUY_DONE
    }

    static S state = S.MINING;
    static boolean enabled = false;
    static boolean openGui = false;
    static int wait = 0, timeout = 0, cooldown = 0, lastSync = -1, clicks = 0;
    static int step = 0, shovelFrom = -1, mendThrows = 0, lastCheck = 0, lastDamage = 0, buyTries = 0;
    static int closes = 0, finishIdle = 0;                       // thoát bảng sau khi giao cát
    static int buyRound = 0, buyTarget = 1, buyBefore = 0, dropStep = 0; // mua đầy thanh công cụ
    static int buyRetry = 0, gone = 0;                           // thử lại khi bảng mua bị đóng
    static boolean shovelSwapped = false, needBuy = false;
    static float oldXRot = Float.NaN, dropXRot = Float.NaN;

    // ----------------------------- KHỞI TẠO -----------------------------
    @Override
    public void onInitializeClient() {
        load();
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
            pr.setProperty("orderCmd", ORDER_COMMAND);
            pr.setProperty("shopCmd", SHOP_COMMAND);
            try (var out = Files.newOutputStream(cfgFile())) { pr.store(out, "AutoSand"); }
        } catch (Exception ignored) { }
    }

    // ----------------------------- BẬT / TẮT -----------------------------
    static void setEnabled(Minecraft mc, boolean on) {
        if (on == enabled) return;
        LocalPlayer p = mc.player;
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
                stopMining(mc, p);
            }
            msg(p, on ? "BẬT" : "TẮT");
        }
    }

    // ----------------------------- ĐIỀU KHIỂN ĐÀO -----------------------------
    static void startMining(LocalPlayer p) {
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

        if (state == S.MINING) {
            if (mc.screen != null) return;
            if (mode == Mode.KEYS) {
                mc.options.keyAttack.setDown(true);
                mc.options.keyUp.setDown(keysForward);
            }
            if (wait > 0) { wait--; return; }
            if (cooldown > 0) { cooldown--; return; }

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
            if (state == S.BUY_DONE && countItem(p, Items.EXPERIENCE_BOTTLE) > 0) {
                finishBuy(mc, p);
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
                if (h == null || h.getRowCount() != 6) return;
                ItemStack first = h.slots.get(0).getItem();
                if (first.isEmpty()) return;
                if (!first.is(Items.SAND)) { abort(mc, "ô đầu tiên không phải đơn cát"); return; }
                click(mc, p, h, 0, ClickType.PICKUP);
                lastSync = h.containerId;
                state = S.WAIT_DELIVER;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case WAIT_DELIVER -> {
                if (h == null || h.containerId == lastSync || h.getRowCount() != 6) return;
                lastSync = h.containerId;
                state = S.DEPOSIT;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case DEPOSIT -> {
                if (h == null || h.containerId != lastSync) { abort(mc, "mất GUI giao hàng"); return; }
                if (clicks < 120) {
                    for (int i = 0; i < h.slots.size(); i++) {
                        Slot s = h.slots.get(i);
                        if (s.container == p.getInventory() && s.getItem().is(Items.SAND)) {
                            click(mc, p, h, i, ClickType.QUICK_MOVE);
                            clicks++;
                            wait = 1;
                            return;
                        }
                    }
                }
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
                int i = find(h, st -> st.is(Items.TOTEM_OF_UNDYING), -1);
                if (i < 0) i = SHOP_TOTEM_SLOT;
                click(mc, p, h, i, ClickType.PICKUP);
                lastSync = h.containerId;
                state = S.BUY_GEAR;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case BUY_GEAR -> {
                if (h == null || h.containerId == lastSync || h.getRowCount() != 3 || !loaded(h)) return;
                int i = find(h, st -> st.is(Items.EXPERIENCE_BOTTLE), -1);
                if (i < 0) i = SHOP_BOTTLE_SLOT;
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
                buyBefore = countItem(p, Items.EXPERIENCE_BOTTLE); // để biết hàng về chưa
                click(mc, p, h, i, ClickType.PICKUP);
                state = S.BUY_DONE;
                timeout = 80;
                wait = ACTION_DELAY * 3;
            }
            case BUY_DONE -> {
                // chờ hàng về (quá hạn thì xử lý ở phần timeout phía trên)
                if (countItem(p, Items.EXPERIENCE_BOTTLE) <= buyBefore) return;
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
        wait = ACTION_DELAY;
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
            startMining(p);
            msg(p, "Huỷ: " + why);
        }
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

    static void msg(LocalPlayer p, String t) {
        p.displayClientMessage(Component.literal("[AutoSand] " + t), false);
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
            int w = 230, h = 18, gap = 21;
            int x = this.width / 2 - w / 2;
            int y = Math.max(6, this.height / 2 - (gap * 11) / 2);

            Button head = Button.builder(Component.literal("=== AutoSand ==="), b -> { })
                .bounds(x, y, w, h).build();
            head.active = false;
            addRenderableWidget(head);
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
