package autosand; // <-- ĐỔI cho trùng package của mod bạn (cùng thư mục với file mod chính)

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * AutoSandOrders (Fabric 1.21.11)
 *
 * Luồng: đào cát (Baritone + Nuker) -> đầy túi -> dừng đào -> mở /orders
 *        -> bấm đơn ĐẦU TIÊN (ô 0) -> shift-click hết cát vào -> đóng GUI
 *        -> bấm nút XÁC NHẬN -> bật lại Baritone + Nuker.
 *
 * Trong game: /autosand          bật / tắt
 *             /autosand cmd <lệnh>  đổi lệnh mở orders (không có dấu /)
 */
public class AutoSandOrders implements ClientModInitializer {

    // ===================== CẤU HÌNH (sửa ở đây) =====================
    /** Lệnh mở danh sách đơn hàng (không có dấu "/"). Đổi trong game bằng /autosand cmd ... */
    static String ORDER_COMMAND = "orders sand";

    /** Chạy trước khi đi giao: dừng Baritone + tắt Nuker. Mặc định theo Meteor Client (dấu "." và "#"). */
    static final List<String> PAUSE_CMDS = List.of("#stop", ".toggle nuker off");

    /** Chạy sau khi giao xong (hoặc khi bật mod): bật Nuker + cho Baritone đào cát. */
    static final List<String> RESUME_CMDS = List.of(".toggle nuker on", "#mine sand");

    /** Số tick chờ giữa các thao tác (20 tick = 1 giây). Server hay kick thì tăng lên. */
    static final int ACTION_DELAY = 4;
    /** Quá số tick này mà 1 bước không xong thì huỷ và quay lại đào. */
    static final int STEP_TIMEOUT = 200;
    /** Nghỉ sau mỗi lần giao để khỏi bị kích hoạt lại ngay. */
    static final int COOLDOWN_AFTER = 60;
    /** Ô nút XÁC NHẬN (màn hình 3 hàng) - theo ảnh của bạn là ô 16. Mod ưu tiên tìm theo tên "xác nhận". */
    static final int CONFIRM_SLOT = 16;
    // =================================================================

    enum S { MINING, WAIT_ORDERS, WAIT_DELIVER, DEPOSIT, WAIT_CONFIRM, FINISH }

    static S state = S.MINING;
    static boolean enabled = false;
    static int wait = 0, timeout = 0, cooldown = 0, lastSync = -1, clicks = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoSandOrders::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("autosand")
                .executes(ctx -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    enabled = !enabled;
                    state = S.MINING;
                    cooldown = 0;
                    wait = 0;
                    if (mc.player != null) {
                        if (enabled) run(mc.player, RESUME_CMDS);
                        else run(mc.player, PAUSE_CMDS);
                    }
                    ctx.getSource().sendFeedback(Text.literal("[AutoSand] " + (enabled ? "BẬT" : "TẮT")));
                    return 1;
                })
                .then(ClientCommandManager.literal("cmd")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String t = StringArgumentType.getString(ctx, "text").trim();
                            if (t.startsWith("/")) t = t.substring(1);
                            ORDER_COMMAND = t;
                            ctx.getSource().sendFeedback(Text.literal("[AutoSand] Lệnh orders: /" + ORDER_COMMAND));
                            return 1;
                        })))));
    }

    // ----------------------------- TICK -----------------------------
    static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (!enabled || p == null || mc.interactionManager == null) return;
        if (wait > 0) { wait--; return; }

        if (state == S.MINING) {
            if (cooldown > 0) { cooldown--; return; }
            if (mc.currentScreen == null && isFull(p) && countSand(p) > 0) {
                run(p, PAUSE_CMDS);
                send(p, "/" + ORDER_COMMAND);
                state = S.WAIT_ORDERS;
                timeout = STEP_TIMEOUT;
                clicks = 0;
                wait = ACTION_DELAY;
            }
            return;
        }

        if (--timeout <= 0) { abort(mc, "hết thời gian chờ ở bước " + state); return; }

        GenericContainerScreenHandler h = container(mc);

        switch (state) {
            case WAIT_ORDERS -> {
                // Danh sách đơn hàng: 6 hàng, ô 0 = đơn đầu tiên
                if (h == null || h.getRows() != 6) return;
                ItemStack first = h.slots.get(0).getStack();
                if (first.isEmpty()) return; // chưa load xong
                if (!first.isOf(Items.SAND)) { abort(mc, "ô đầu tiên không phải đơn cát"); return; }
                mc.interactionManager.clickSlot(h.syncId, 0, 0, SlotActionType.PICKUP, p);
                lastSync = h.syncId;
                state = S.WAIT_DELIVER;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case WAIT_DELIVER -> {
                // GUI "bỏ vật phẩm vào để giao": cũng 6 hàng nhưng là cửa sổ mới
                if (h == null || h.syncId == lastSync || h.getRows() != 6) return;
                lastSync = h.syncId;
                state = S.DEPOSIT;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case DEPOSIT -> {
                if (h == null || h.syncId != lastSync) { abort(mc, "mất GUI giao hàng"); return; }
                if (clicks < 120) {
                    for (Slot s : h.slots) {
                        if (s.inventory == p.getInventory() && s.getStack().isOf(Items.SAND)) {
                            mc.interactionManager.clickSlot(h.syncId, s.id, 0, SlotActionType.QUICK_MOVE, p);
                            clicks++;
                            wait = 1; // mỗi 2 tick shift-click 1 ô
                            return;
                        }
                    }
                }
                // hết cát (hoặc GUI đầy) -> đóng để server mở màn hình xác nhận
                p.closeHandledScreen();
                state = S.WAIT_CONFIRM;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case WAIT_CONFIRM -> {
                // Màn hình "đơn hàng -> xác nhận giao hàng": 3 hàng
                if (h == null || h.syncId == lastSync || h.getRows() != 3) return;
                int slot = CONFIRM_SLOT;
                for (Slot s : h.slots) {
                    if (s.id < 27 && !s.getStack().isEmpty()) {
                        String n = s.getStack().getName().getString().toLowerCase(Locale.ROOT);
                        if (n.contains("xác nhận") || n.contains("xac nhan")) { slot = s.id; break; }
                    }
                }
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, p);
                state = S.FINISH;
                wait = ACTION_DELAY * 3;
            }
            case FINISH -> {
                if (mc.currentScreen != null) p.closeHandledScreen();
                run(p, RESUME_CMDS);
                state = S.MINING;
                cooldown = COOLDOWN_AFTER;
                msg(p, "Đã giao xong, tiếp tục đào.");
            }
            default -> { }
        }
    }

    // ----------------------------- HELPERS -----------------------------
    static GenericContainerScreenHandler container(MinecraftClient mc) {
        if (mc.currentScreen instanceof HandledScreen<?> hs
                && hs.getScreenHandler() instanceof GenericContainerScreenHandler g) {
            return g;
        }
        return null;
    }

    /** Đầy = không còn ô trống và không còn stack cát nào chưa đủ 64. */
    static boolean isFull(ClientPlayerEntity p) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (st.isEmpty()) return false;
            if (st.isOf(Items.SAND) && st.getCount() < st.getMaxCount()) return false;
        }
        return true;
    }

    static int countSand(ClientPlayerEntity p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (st.isOf(Items.SAND)) n += st.getCount();
        }
        return n;
    }

    static void run(ClientPlayerEntity p, List<String> cmds) {
        for (String c : cmds) send(p, c);
    }

    /** "/xxx" gửi như lệnh server; "#xxx" / ".xxx" sẽ bị Baritone / Meteor chặn ở client. */
    static void send(ClientPlayerEntity p, String s) {
        if (s.startsWith("/")) p.networkHandler.sendChatCommand(s.substring(1));
        else p.networkHandler.sendChatMessage(s);
    }

    static void msg(ClientPlayerEntity p, String t) {
        p.sendMessage(Text.literal("[AutoSand] " + t), false);
    }

    static void abort(MinecraftClient mc, String why) {
        ClientPlayerEntity p = mc.player;
        if (p != null) {
            if (mc.currentScreen != null) p.closeHandledScreen();
            run(p, RESUME_CMDS);
            msg(p, "Huỷ lượt giao: " + why);
        }
        state = S.MINING;
        cooldown = 200;
    }
}
