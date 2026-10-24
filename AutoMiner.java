package autosand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bộ tự đào cát có sẵn trong mod (thay Baritone).
 *
 *  - Tự tìm cát lộ thiên gần nhất, tìm đường bằng A* (đi ngang, chéo, bước lên 1 ô, rơi tối đa 3 ô,
 *    tự đào xuyên cát / đất chắn đường), né lava, nước, xương rồng, lửa, hố sâu.
 *  - Tự nhặt cát rơi (bán kính lấy từ cấu hình "Nhặt cát" trong GUI).
 *  - Không thấy cát thì đi thăm dò sang chỗ khác.
 *  - Chết: ghi nhớ toạ độ chết -> tự hồi sinh -> /home cat -> đi về chỗ chết -> nhặt hết đồ rơi
 *    -> đưa xẻng về thanh công cụ, cát về túi, trả đồ về đúng ô cũ -> đào tiếp.
 *
 * Mọi thứ chạy trên client tick của AutoSandOrders (xem AutoSandOrders.tick).
 */
final class AutoMiner {
    private AutoMiner() { }

    // ===================== CẤU HÌNH (lưu trong autosand.properties) =====================
    static String homeCmd = "home cat";     // lệnh quay lại sau khi chết (không có dấu /)
    static boolean deathRecover = true;     // chết -> về chỗ chết nhặt đồ
    static boolean autoRespawn = true;      // tự bấm "Hồi sinh"
    static boolean digThrough = true;       // cho phép đào xuyên cát / đất chắn đường
    static int scanRadius = 16;             // bán kính tìm cát
    static final int[] SCAN_LEVELS = {8, 12, 16, 24};

    // ===================== HẰNG SỐ =====================
    static final double REACH = 4.3;        // tầm với khi đào (vanilla 4.5, chừa dư)
    static final double EYE_H = 1.62;
    static final int MAX_NODES = 2500;      // số ô tối đa A* duyệt mỗi lần tìm đường
    static final int MAX_RANGE = 48;        // A* không đi xa hơn bấy nhiêu ô so với chỗ xuất phát
    static final int FALL_MAX = 3;          // rơi tối đa bấy nhiêu ô (3 ô = không mất máu)
    static final double DIG_COST = 5.0;     // "giá" của việc đào 1 khối khi đi
    static final int MINE_TIMEOUT = 160;    // đào 1 khối quá bấy nhiêu tick thì bỏ qua
    static final int TRAVEL_TIMEOUT = 500;  // đi 1 đoạn quá bấy nhiêu tick thì bỏ
    static final int MAX_DEPTH = 6;         // không đào sâu hơn mức bắt đầu quá bấy nhiêu ô
    static final int COLLECT_R = 12;        // bán kính nhặt đồ quanh chỗ chết
    static final int RECOVER_TIMEOUT = 9000; // 7.5 phút: quá thì bỏ nhặt đồ

    /** Chỉ những khối này mới được đào xuyên khi đi (tránh phá công trình người khác). */
    static final Set<Block> DIGGABLE = Set.of(
        Blocks.SAND, Blocks.RED_SAND, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.GRAVEL);

    // ===================== TRẠNG THÁI ĐÀO =====================
    static final int P_IDLE = 0, P_MOVE = 1, P_MINE = 2, P_ITEM = 3, P_EXPLORE = 4;
    static final int RUN = 0, DONE = 1, FAIL = 2;

    static boolean active = false;
    static int ph = P_IDLE, phTicks = 0, stuck = 0, scanCool = 0, noTarget = 0, exploreCount = 0, ignoreAge = 0;
    static int baseY = -1000000;
    static BlockPos target = null;
    static ItemEntity itemTarget = null;
    static List<BlockPos> path = null;
    static int pi = 0;
    static Vec3 lastPos = null;
    static double exploreYaw = 0;
    static long failKey = Long.MIN_VALUE;
    static int failCount = 0;
    static final Set<Long> ignoreBlocks = new HashSet<>();
    static final Set<Integer> ignoreItems = new HashSet<>();

    // ===================== TRẠNG THÁI HỒI SINH / NHẶT ĐỒ =====================
    static final int R_NONE = 0, R_WAIT = 1, R_HOME = 2, R_WALK = 3, R_COLLECT = 4, R_ORG = 5;
    static int rec = R_NONE, recTimer = 0, recTicks = 0, recStep = 0, homeTries = 0;
    static int walkNoProg = 0, walkFails = 0, blindTicks = 0, collectIdle = 0, collectTicks = 0, itemWait = 0;
    static double walkBest = Double.MAX_VALUE;
    static boolean deathRecorded = false, shovelWarned = false;
    static BlockPos deathPos = null;
    static Object deathDim = null;
    static int deadTicks = 0;
    static Vec3 homeFrom = null, itemPlanPos = null;
    static String lastDeathText = null;

    // Bố cục thanh công cụ lúc còn sống: để chết xong trả đồ về đúng ô cũ
    static final Item[] snap = new Item[9];
    static final boolean[] snapShovel = new boolean[9];
    static int snapTick = 0;

    // ----------------------------- ĐIỀU KHIỂN TỪ AutoSandOrders -----------------------------
    static void start(LocalPlayer p) {
        active = true;
        int y = p.blockPosition().getY();
        if (y > baseY) baseY = y;
        if (rec == R_NONE) { path = null; target = null; itemTarget = null; ph = P_IDLE; phTicks = 0; stuck = 0; }
    }

    static void stop(Minecraft mc) {
        active = false;
        path = null;
        target = null;
        itemTarget = null;
        ph = P_IDLE;
        phTicks = 0;
        release(mc);
    }

    /** Tắt hẳn (người dùng bấm TẮT / BẬT): xoá luôn việc đang nhặt đồ. */
    static void reset(Minecraft mc) {
        stop(mc);
        rec = R_NONE;
        deathRecorded = false;
        baseY = -1000000;
        noTarget = 0;
        exploreCount = 0;
        ignoreBlocks.clear();
        ignoreItems.clear();
    }

    static boolean recovering() { return rec != R_NONE; }

    static void release(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyAttack.setDown(false);
    }

    // ----------------------------- CHẾT / HỒI SINH -----------------------------
    /** Gọi mỗi tick khi người chơi đang chết. */
    static void onDead(Minecraft mc, LocalPlayer p) {
        release(mc);
        if (!deathRecorded) {
            deathRecorded = true;
            deadTicks = 0;
            deathPos = p.blockPosition();
            deathDim = mc.level != null ? mc.level.dimension() : null;
            lastDeathText = deathPos.getX() + " " + deathPos.getY() + " " + deathPos.getZ();
            AutoSandOrders.msg(p, "Đã chết tại " + lastDeathText + " (đã ghi nhớ toạ độ).");
        }
        if (autoRespawn && ++deadTicks % 20 == 10) p.respawn();
    }

    /** Gọi khi LocalPlayer mới xuất hiện sau khi chết / vào lại thế giới. */
    static void onRespawn(Minecraft mc, LocalPlayer p) {
        path = null;
        target = null;
        itemTarget = null;
        ph = P_IDLE;
        release(mc);
        baseY = -1000000;
        if (deathRecorded && deathRecover && deathPos != null) {
            rec = R_WAIT;
            recTimer = 40;
            recTicks = 0;
            recStep = 0;
            homeTries = 0;
            walkNoProg = 0;
            walkFails = 0;
            blindTicks = 0;
            walkBest = Double.MAX_VALUE;
            collectIdle = 0;
            shovelWarned = false;
            ignoreItems.clear();
            AutoSandOrders.msg(p, "Đã hồi sinh. Chuẩn bị /" + homeCmd + " rồi quay lại nhặt đồ.");
        } else {
            deathRecorded = false;
        }
    }

    static String deathText() {
        return lastDeathText == null ? "Chưa chết lần nào" : "Chết ở " + lastDeathText;
    }

    static String recoverText() {
        return switch (rec) {
            case R_WAIT -> "Vừa hồi sinh, chuẩn bị về home";
            case R_HOME -> "Chờ /" + homeCmd + " dịch chuyển";
            case R_WALK -> "Đang đi về chỗ chết";
            case R_COLLECT -> "Đang nhặt đồ rơi lúc chết";
            case R_ORG -> "Đang xếp xẻng / đồ lên thanh công cụ";
            default -> "Đang đào cát";
        };
    }

    // ----------------------------- TICK CHÍNH -----------------------------
    static void tick(Minecraft mc, LocalPlayer p) {
        ClientLevel lv = mc.level;
        if (lv == null) return;
        if (rec != R_NONE) { tickRecovery(mc, p, lv); return; }
        if (!active) return;
        snapshot(p);
        phTicks++;
        if (scanCool > 0) scanCool--;
        switch (ph) {
            case P_IDLE -> chooseAction(mc, p, lv);
            case P_MOVE, P_ITEM, P_EXPLORE -> tickTravel(mc, p, lv);
            case P_MINE -> tickMine(mc, p, lv);
            default -> ph = P_IDLE;
        }
    }

    /** Ghi lại bố cục thanh công cụ (chỉ khi đang có xẻng trên đó, để khỏi ghi nhầm lúc đang sửa xẻng). */
    static void snapshot(LocalPlayer p) {
        if (deathRecorded || rec != R_NONE) return;
        if (++snapTick < 40) return;
        snapTick = 0;
        boolean has = false;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(ItemTags.SHOVELS)) has = true;
        }
        if (!has) return;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            snap[i] = st.isEmpty() ? null : st.getItem();
            snapShovel[i] = st.is(ItemTags.SHOVELS);
        }
    }

    // ----------------------------- CHỌN VIỆC -----------------------------
    static void chooseAction(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        release(mc);
        if (scanCool > 0) return;
        if (ignoreBlocks.size() > 200 || ++ignoreAge > 1200) {
            ignoreBlocks.clear();
            ignoreItems.clear();
            ignoreAge = 0;
        }

        // 1) cát rơi gần đó -> đi nhặt
        if (AutoSandOrders.pickupRadius > 0 && !AutoSandOrders.isFull(p)) {
            ItemEntity it = findDrop(lv, p, p.position(), AutoSandOrders.pickupRadius, true);
            if (it != null) {
                Vec3 ip = it.position();
                List<BlockPos> r = findPath(lv, p.blockPosition(), ip,
                    q -> Math.hypot(q.getX() + 0.5 - ip.x, q.getZ() + 0.5 - ip.z) < 0.9 && Math.abs(q.getY() - ip.y) < 2.0,
                    1200, false);
                if (r != null && !r.isEmpty()) {
                    itemTarget = it;
                    path = r;
                    pi = 0;
                    stuck = 0;
                    ph = P_ITEM;
                    phTicks = 0;
                    return;
                }
                ignoreItems.add(it.getId());
            }
        }

        // 2) khối cát để đào
        if (planToSand(p, lv)) {
            noTarget = 0;
            exploreCount = 0;
            return;
        }

        // 3) không còn cát quanh đây -> đi thăm dò chỗ khác
        baseY = p.blockPosition().getY();
        if (++exploreCount <= 40 && planExplore(lv, p)) return;
        scanCool = 40;
        if (++noTarget >= 10 || exploreCount > 40) {
            AutoSandOrders.msg(p, "Không tìm thấy cát quanh đây và không thăm dò thêm được. Tắt AutoSand.");
            AutoSandOrders.setEnabled(mc, false);
        }
    }

    static boolean planToSand(LocalPlayer p, ClientLevel lv) {
        List<BlockPos> cands = scanSand(lv, p);
        if (cands.isEmpty()) return false;
        Vec3 eye = p.getEyePosition();

        // đang trong tầm với: đào luôn, khỏi đi
        for (BlockPos c : cands) {
            if (canMineFromEye(lv, p, eye, c)) {
                target = c;
                path = null;
                ph = P_MINE;
                phTicks = 0;
                return true;
            }
        }
        BlockPos start = p.blockPosition();
        int tries = 0;
        for (BlockPos c : cands) {
            if (tries++ >= 5) break;
            Vec3 ct = Vec3.atCenterOf(c);
            List<BlockPos> r = findPath(lv, start, ct, q -> {
                if (colBlocked(q, c)) return false;
                Vec3 e = new Vec3(q.getX() + 0.5, q.getY() + EYE_H, q.getZ() + 0.5);
                return canMineFromEye(lv, p, e, c);
            }, MAX_NODES, false);
            if (r != null) {
                target = c;
                path = r;
                pi = 0;
                stuck = 0;
                phTicks = 0;
                ph = r.isEmpty() ? P_MINE : P_MOVE;
                return true;
            }
            ignoreBlocks.add(c.asLong());
        }
        return false;
    }

    static boolean planExplore(ClientLevel lv, LocalPlayer p) {
        BlockPos start = p.blockPosition();
        double[] offs = {0, 90, -90, 180, 45, -45};
        for (double off : offs) {
            double ang = Math.toRadians(exploreYaw + off);
            double tx = p.getX() - Math.sin(ang) * 26, tz = p.getZ() + Math.cos(ang) * 26;
            Vec3 t = new Vec3(tx, p.getY(), tz);
            List<BlockPos> r = findPath(lv, start, t,
                q -> Math.hypot(q.getX() + 0.5 - tx, q.getZ() + 0.5 - tz) < 3.0, MAX_NODES, true);
            if (r != null && r.size() >= 4) {
                exploreYaw += off;
                path = r;
                pi = 0;
                stuck = 0;
                ph = P_EXPLORE;
                phTicks = 0;
                return true;
            }
        }
        exploreYaw = Math.random() * 360.0;
        return false;
    }

    // ----------------------------- QUÉT CÁT -----------------------------
    static final class Cand {
        final BlockPos pos;
        final double score;
        Cand(BlockPos pos, double score) { this.pos = pos; this.score = score; }
    }

    /** Tối đa 12 khối cát lộ thiên gần nhất, đã loại các khối nguy hiểm (kề nước / lava, ngay dưới chân, trên đầu). */
    static List<BlockPos> scanSand(ClientLevel lv, LocalPlayer p) {
        BlockPos o = p.blockPosition();
        int r = scanRadius;
        List<Cand> list = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -MAX_DEPTH; dy <= 5; dy++) {
            int y = o.getY() + dy;
            if (y < baseY - MAX_DEPTH) continue;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(o.getX() + dx, y, o.getZ() + dz);
                    if (!lv.getBlockState(m).is(Blocks.SAND)) continue;
                    if (ignoreBlocks.contains(m.asLong())) continue;
                    if (underOrOver(p, m)) continue;
                    boolean open = false, bad = false;
                    for (Direction d : Direction.values()) {
                        BlockPos nb = m.relative(d);
                        BlockState ns = lv.getBlockState(nb);
                        if (!ns.getFluidState().isEmpty()) { bad = true; break; }
                        if (ns.getCollisionShape(lv, nb).isEmpty()) open = true;
                    }
                    if (bad || !open) continue;
                    list.add(new Cand(m.immutable(), dx * dx + dz * dz + 3.0 * dy * dy));
                }
            }
        }
        list.sort(Comparator.comparingDouble((Cand c) -> c.score));
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < list.size() && i < 12; i++) out.add(list.get(i).pos);
        return out;
    }

    /** Khối nằm ngay dưới chân (đào là rơi) hoặc trên đầu (cát đổ xuống đè) -> không đào. */
    static boolean underOrOver(LocalPlayer p, BlockPos b) {
        if (b.getX() + 1 <= p.getX() - 0.35 || b.getX() >= p.getX() + 0.35) return false;
        if (b.getZ() + 1 <= p.getZ() - 0.35 || b.getZ() >= p.getZ() + 0.35) return false;
        int fy = p.blockPosition().getY();
        return b.getY() == fy - 1 || b.getY() >= fy + 2;
    }

    static boolean colBlocked(BlockPos stand, BlockPos t) {
        return t.getX() == stand.getX() && t.getZ() == stand.getZ()
            && (t.getY() == stand.getY() - 1 || t.getY() >= stand.getY() + 2);
    }

    /** Từ mắt tại eye nhìn thẳng tới khối t: trong tầm với và không bị khối khác che. */
    static boolean canMineFromEye(ClientLevel lv, LocalPlayer p, Vec3 eye, BlockPos t) {
        Vec3 c = Vec3.atCenterOf(t);
        if (eye.distanceToSqr(c) > REACH * REACH) return false;
        BlockHitResult r = lv.clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(t);
    }

    // ----------------------------- ĐỒ RƠI -----------------------------
    static ItemEntity findDrop(ClientLevel lv, LocalPlayer p, Vec3 c, double r, boolean sandOnly) {
        AABB box = new AABB(c.x - r, c.y - 5, c.z - r, c.x + r, c.y + 5, c.z + r);
        List<ItemEntity> list = lv.getEntitiesOfClass(ItemEntity.class, box,
            e -> !e.isRemoved() && (!sandOnly || e.getItem().is(Items.SAND)) && !ignoreItems.contains(e.getId()));
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (ItemEntity e : list) {
            double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
            double d = dx * dx + dz * dz;
            if (sandOnly && d < 1.2 * 1.2 && Math.abs(e.getY() - p.getY()) < 1.5) continue;   // quá gần: tự nhặt được
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    // ----------------------------- ĐI / ĐÀO -----------------------------
    static void tickTravel(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        if (phTicks > TRAVEL_TIMEOUT) { failTravel(mc); return; }
        if (ph == P_ITEM) {
            if (itemTarget == null || itemTarget.isRemoved() || AutoSandOrders.isFull(p)) { endTravel(mc); return; }
        } else if (ph == P_MOVE) {
            if (target == null || !lv.getBlockState(target).is(Blocks.SAND)) { endTravel(mc); return; }
        }
        int r = followPath(mc, p, lv);
        if (r == RUN) return;
        release(mc);
        if (r == FAIL) { failTravel(mc); return; }
        if (ph == P_MOVE) {                                  // tới nơi -> đào
            path = null;
            ph = P_MINE;
            phTicks = 0;
            return;
        }
        if (ph == P_ITEM && itemTarget != null && !itemTarget.isRemoved()) ignoreItems.add(itemTarget.getId());
        endTravel(mc);
    }

    static void failTravel(Minecraft mc) {
        if (ph == P_MOVE && target != null) ignoreBlocks.add(target.asLong());
        if (ph == P_ITEM && itemTarget != null) ignoreItems.add(itemTarget.getId());
        endTravel(mc);
    }

    static void endTravel(Minecraft mc) {
        release(mc);
        path = null;
        itemTarget = null;
        target = null;
        ph = P_IDLE;
        phTicks = 0;
        scanCool = 2;
    }

    static void tickMine(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        release(mc);
        BlockPos t = target;
        if (t == null || !lv.getBlockState(t).is(Blocks.SAND)) {        // đào xong
            target = null;
            ph = P_IDLE;
            phTicks = 0;
            scanCool = 0;
            return;
        }
        if (phTicks > MINE_TIMEOUT || underOrOver(p, t)) {
            ignoreBlocks.add(t.asLong());
            target = null;
            ph = P_IDLE;
            phTicks = 0;
            scanCool = 2;
            return;
        }
        if (!canMineFromEye(lv, p, p.getEyePosition(), t)) {            // lệch vị trí / bị che -> tính lại, 3 lần liền thì bỏ
            if (t.asLong() == failKey) failCount++;
            else { failKey = t.asLong(); failCount = 1; }
            if (failCount >= 3) { ignoreBlocks.add(t.asLong()); failCount = 0; }
            target = null;
            ph = P_IDLE;
            phTicks = 0;
            scanCool = 2;
            return;
        }
        digBlock(mc, p, t);
    }

    static void digBlock(Minecraft mc, LocalPlayer p, BlockPos b) {
        Vec3 c = Vec3.atCenterOf(b);
        aimAt(p, c);
        ensureShovel(p);
        Vec3 e = p.getEyePosition();
        Direction face = Direction.getApproximateNearest(e.x - c.x, e.y - c.y, e.z - c.z);
        mc.gameMode.continueDestroyBlock(b, face);
        p.swing(InteractionHand.MAIN_HAND);
    }

    static void aimAt(LocalPlayer p, Vec3 t) {
        Vec3 e = p.getEyePosition();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    /** Cầm xẻng trên thanh công cụ (nếu đang cầm thứ khác). */
    static void ensureShovel(LocalPlayer p) {
        if (p.getInventory().getItem(AutoSandOrders.getSelected(p)).is(ItemTags.SHOVELS)) return;
        int hs = hotbarShovel(p);
        if (hs >= 0) AutoSandOrders.setSelected(p, hs);
    }

    /** Đi theo path[pi...]. Đào khối chắn đường nếu được phép. */
    static int followPath(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        if (path == null) return DONE;
        Vec3 pos = p.position();
        while (pi < path.size()) {
            BlockPos q = path.get(pi);
            double qx = q.getX() + 0.5 - pos.x, qz = q.getZ() + 0.5 - pos.z, qy = pos.y - q.getY();
            if (Math.hypot(qx, qz) < 0.45 && qy < 1.2 && qy > -1.2) { pi++; stuck = 0; } else break;
        }
        if (pi >= path.size()) { release(mc); return DONE; }

        BlockPos n = path.get(pi);
        double dx = n.getX() + 0.5 - pos.x, dz = n.getZ() + 0.5 - pos.z;
        double dist = Math.hypot(dx, dz);
        if (dist > 6.0 || Math.abs(pos.y - n.getY()) > 4.0) return FAIL;     // bị đẩy lệch đường -> tính lại

        // ô cần trống để đi vào n
        BlockPos from = p.blockPosition();
        BlockPos blocker = null;
        if (!AutoSandOrders.passable(lv, n)) blocker = n;
        else if (!AutoSandOrders.passable(lv, n.above())) blocker = n.above();
        else if (n.getY() > from.getY() && !AutoSandOrders.passable(lv, from.above(2))) blocker = from.above(2);
        if (blocker != null) {
            Block bk = lv.getBlockState(blocker).getBlock();
            if (!digThrough || !DIGGABLE.contains(bk)) return FAIL;
            if (p.getEyePosition().distanceToSqr(Vec3.atCenterOf(blocker)) > REACH * REACH) return FAIL;
            release(mc);
            digBlock(mc, p, blocker);
            stuck = 0;
            return RUN;
        }

        if (dist > 0.12) p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot(0f);
        boolean dropAhead = n.getY() < pos.y - 1.0;                          // sắp rơi xuống: dừng ở mép cho rơi thẳng
        mc.options.keyUp.setDown(!(dropAhead && dist < 0.5));
        boolean jump = (n.getY() > pos.y + 0.3 && dist < 1.7) || (p.horizontalCollision && p.onGround());
        mc.options.keyJump.setDown(jump);
        mc.options.keySprint.setDown(dist > 1.2 && !jump && !dropAhead);

        if (lastPos != null && pos.distanceToSqr(lastPos) < 0.0016) {
            if (++stuck > 40) return FAIL;
        } else {
            stuck = 0;
        }
        lastPos = pos;
        return RUN;
    }

    // ----------------------------- TÌM ĐƯỜNG (A*) -----------------------------
    static boolean hazardState(BlockState s) {
        return s.is(Blocks.CACTUS) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.MAGMA_BLOCK)
            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.POWDER_SNOW)
            || s.is(Blocks.COBWEB) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
            || s.getFluidState().is(FluidTags.LAVA);
    }

    /** -1 = chặn (đá, nước, lava...), 0 = trống đi qua được, DIG_COST = phải đào mới qua. */
    static double cellCost(ClientLevel lv, BlockPos c) {
        BlockState s = lv.getBlockState(c);
        if (hazardState(s) || !s.getFluidState().isEmpty()) return -1;
        if (s.getCollisionShape(lv, c).isEmpty()) return 0;
        if (digThrough && DIGGABLE.contains(s.getBlock())) return DIG_COST;
        return -1;
    }

    /** Khối đứng được: đặc, cao tối đa 1 ô, không phải khối nguy hiểm / chất lỏng. */
    static boolean solidGround(ClientLevel lv, BlockPos b) {
        BlockState s = lv.getBlockState(b);
        if (hazardState(s) || !s.getFluidState().isEmpty()) return false;
        var sh = s.getCollisionShape(lv, b);
        return !sh.isEmpty() && sh.max(Direction.Axis.Y) <= 1.0001;
    }

    static boolean lavaNear(ClientLevel lv, BlockPos f) {
        if (AutoSandOrders.lavaAt(lv, f.below())) return true;
        for (Direction d : AutoSandOrders.HORIZ) {
            BlockPos n = f.relative(d);
            if (AutoSandOrders.lavaAt(lv, n) || AutoSandOrders.lavaAt(lv, n.above())) return true;
        }
        return false;
    }

    static final class Move {
        final BlockPos pos;
        final double cost;
        Move(BlockPos pos, double cost) { this.pos = pos; this.cost = cost; }
    }

    static void expand(ClientLevel lv, BlockPos p, List<Move> out) {
        BlockPos head2 = p.above(2);
        for (Direction d : AutoSandOrders.HORIZ) {
            BlockPos n = p.relative(d);
            double c1 = cellCost(lv, n), c2 = cellCost(lv, n.above());

            // đi ngang, hoặc đi ra mép rồi rơi xuống (tối đa FALL_MAX ô)
            if (c1 >= 0 && c2 >= 0) {
                if (solidGround(lv, n.below())) {
                    if (!lavaNear(lv, n)) out.add(new Move(n, 1.0 + c1 + c2));
                } else if (c1 == 0 && c2 == 0) {
                    for (int k = 1; k <= FALL_MAX; k++) {
                        BlockPos m = n.below(k);
                        if (cellCost(lv, m) != 0) break;
                        if (solidGround(lv, m.below())) {
                            if (!lavaNear(lv, m)) out.add(new Move(m, 1.5 + 0.6 * k));
                            break;
                        }
                    }
                }
            }

            // bước lên 1 ô (nhảy)
            if (solidGround(lv, n)) {
                BlockPos up = n.above();
                double a = cellCost(lv, head2), b = cellCost(lv, up), c = cellCost(lv, up.above());
                if (a >= 0 && b >= 0 && c >= 0 && !lavaNear(lv, up)) out.add(new Move(up, 1.8 + a + b + c));
            }
        }

        // đi chéo (chỉ khi cả 2 ô kề và ô đích đều thông thoáng, có nền)
        for (int i = 0; i < 4; i++) {
            Direction a = AutoSandOrders.HORIZ[i], b = AutoSandOrders.HORIZ[(i + 1) % 4];
            BlockPos pa = p.relative(a), pb = p.relative(b), dn = pa.relative(b);
            if (cellCost(lv, pa) == 0 && cellCost(lv, pa.above()) == 0
                && cellCost(lv, pb) == 0 && cellCost(lv, pb.above()) == 0
                && cellCost(lv, dn) == 0 && cellCost(lv, dn.above()) == 0
                && solidGround(lv, pa.below()) && solidGround(lv, pb.below()) && solidGround(lv, dn.below())
                && !lavaNear(lv, dn)) {
                out.add(new Move(dn, 1.42));
            }
        }
    }

    static final class Nd {
        final BlockPos pos;
        final Nd parent;
        final double g, f;
        Nd(BlockPos pos, Nd parent, double g, double f) { this.pos = pos; this.parent = parent; this.g = g; this.f = f; }
    }

    static double heur(BlockPos n, Vec3 t) {
        double dx = n.getX() + 0.5 - t.x, dz = n.getZ() + 0.5 - t.z, dy = n.getY() - t.y;
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.7;
    }

    /**
     * A* từ start tới ô thoả goal. Trả về danh sách ô cần đi qua (không gồm ô xuất phát), rỗng nếu đã ở đích,
     * null nếu không có đường. partial = true: không tới được đích thì đi tới ô gần đích nhất đã duyệt.
     */
    static List<BlockPos> findPath(ClientLevel lv, BlockPos start, Vec3 target, Predicate<BlockPos> goal,
                                   int maxNodes, boolean partial) {
        PriorityQueue<Nd> open = new PriorityQueue<>(Comparator.comparingDouble((Nd x) -> x.f));
        Map<Long, Double> bestG = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Nd s = new Nd(start, null, 0, heur(start, target) * 1.3);
        open.add(s);
        bestG.put(start.asLong(), 0.0);
        Nd best = s;
        double bestH = heur(start, target);
        int expanded = 0;
        List<Move> mv = new ArrayList<>();
        while (!open.isEmpty() && expanded < maxNodes) {
            Nd cur = open.poll();
            if (!closed.add(cur.pos.asLong())) continue;
            expanded++;
            if (goal.test(cur.pos)) return build(cur);
            double h = heur(cur.pos, target);
            if (h < bestH) { bestH = h; best = cur; }
            mv.clear();
            expand(lv, cur.pos, mv);
            for (Move m : mv) {
                BlockPos np = m.pos;
                if (Math.abs(np.getX() - start.getX()) > MAX_RANGE || Math.abs(np.getZ() - start.getZ()) > MAX_RANGE) continue;
                long k = np.asLong();
                if (closed.contains(k)) continue;
                double ng = cur.g + m.cost;
                Double old = bestG.get(k);
                if (old != null && old <= ng) continue;
                bestG.put(k, ng);
                open.add(new Nd(np, cur, ng, ng + heur(np, target) * 1.3));
            }
        }
        if (partial && best != s) return build(best);
        return null;
    }

    static List<BlockPos> build(Nd end) {
        ArrayList<BlockPos> r = new ArrayList<>();
        for (Nd n = end; n.parent != null; n = n.parent) r.add(n.pos);
        Collections.reverse(r);
        return r;
    }

    // ----------------------------- NHẶT ĐỒ SAU KHI CHẾT -----------------------------
    static Vec3 deathCenter() {
        return new Vec3(deathPos.getX() + 0.5, deathPos.getY(), deathPos.getZ() + 0.5);
    }

    static void tickRecovery(Minecraft mc, LocalPlayer p, ClientLevel lv) {
        if (deathPos == null || ++recTicks > RECOVER_TIMEOUT) {
            endRecovery(mc, p, "quá thời gian, bỏ qua");
            return;
        }
        Vec3 dc = deathCenter();
        switch (rec) {
            case R_WAIT -> {
                release(mc);
                if (--recTimer > 0) return;
                if (homeCmd == null || homeCmd.isBlank()) { rec = R_WALK; recTimer = 0; path = null; return; }
                homeFrom = p.position();
                AutoSandOrders.send(p, "/" + homeCmd);
                homeTries++;
                rec = R_HOME;
                recTimer = 160;
            }
            case R_HOME -> {
                release(mc);                                    // đứng yên chờ dịch chuyển (nhiều server huỷ nếu di chuyển)
                if (homeFrom != null && p.position().distanceToSqr(homeFrom) > 16.0) {
                    AutoSandOrders.msg(p, "Đã dịch chuyển, đi tới chỗ chết.");
                    rec = R_WALK;
                    recTimer = 30;                              // chờ chunk tải
                    path = null;
                    return;
                }
                if (--recTimer <= 0) {
                    double far = Math.hypot(p.getX() - dc.x, p.getZ() - dc.z);
                    if (homeTries < 2 && far > 80) {
                        homeFrom = p.position();
                        AutoSandOrders.send(p, "/" + homeCmd);
                        homeTries++;
                        recTimer = 160;
                    } else {
                        rec = R_WALK;
                        recTimer = 0;
                        path = null;
                    }
                }
            }
            case R_WALK -> {
                if (recTimer > 0) { recTimer--; release(mc); return; }
                if (deathDim != null && !deathDim.equals(lv.dimension())) {
                    endRecovery(mc, p, "đang ở chiều không gian khác với chỗ chết");
                    return;
                }
                double hd = Math.hypot(p.getX() - dc.x, p.getZ() - dc.z);
                if (hd <= 3.0 && Math.abs(p.getY() - dc.y) <= 4.0) {
                    release(mc);
                    rec = R_COLLECT;
                    collectIdle = 0;
                    collectTicks = 0;
                    itemWait = 0;
                    itemTarget = null;
                    path = null;
                    AutoSandOrders.msg(p, "Tới chỗ chết, bắt đầu nhặt đồ.");
                    return;
                }
                if (recTicks % 100 == 0) {                      // 5 giây một lần kiểm tra có tiến gần hơn không
                    double d = p.position().distanceTo(dc);
                    if (d > walkBest - 2.0) walkNoProg++; else walkNoProg = 0;
                    walkBest = Math.min(walkBest, d);
                    if (walkNoProg >= 6 || walkFails > 12) {
                        endRecovery(mc, p, "không đi tới được toạ độ chết");
                        return;
                    }
                }
                if (path == null || pi >= path.size()) {
                    path = null;
                    List<BlockPos> rp = findPath(lv, p.blockPosition(), dc,
                        q -> Math.hypot(q.getX() + 0.5 - dc.x, q.getZ() + 0.5 - dc.z) <= 2.5 && Math.abs(q.getY() - dc.y) <= 3,
                        MAX_NODES, true);
                    if (rp == null || rp.isEmpty()) {
                        if (++blindTicks > 600) { endRecovery(mc, p, "không tìm được đường tới chỗ chết"); return; }
                        blindWalk(mc, p, dc);
                        return;
                    }
                    path = rp;
                    pi = 0;
                    stuck = 0;
                }
                int r = followPath(mc, p, lv);
                if (r == FAIL) { path = null; stuck = 0; walkFails++; }
                else if (r == DONE) path = null;
            }
            case R_COLLECT -> {
                if (++collectTicks > 2400) { toOrganize(mc); return; }
                if (itemTarget == null || itemTarget.isRemoved()) {
                    itemTarget = findDrop(lv, p, dc, COLLECT_R, false);
                    path = null;
                    itemWait = 0;
                    if (itemTarget == null) {
                        release(mc);
                        if (++collectIdle >= 60) toOrganize(mc);
                        return;
                    }
                    collectIdle = 0;
                }
                ItemEntity it = itemTarget;
                Vec3 ip = it.position();
                if (path == null) {
                    itemPlanPos = ip;
                    List<BlockPos> rp = findPath(lv, p.blockPosition(), ip,
                        q -> Math.hypot(q.getX() + 0.5 - ip.x, q.getZ() + 0.5 - ip.z) < 0.9 && Math.abs(q.getY() - ip.y) < 2.0,
                        MAX_NODES, false);
                    if (rp == null) { ignoreItems.add(it.getId()); itemTarget = null; return; }
                    path = rp;
                    pi = 0;
                    stuck = 0;
                }
                int r = followPath(mc, p, lv);
                if (r == FAIL) {
                    ignoreItems.add(it.getId());
                    itemTarget = null;
                    path = null;
                    release(mc);
                } else if (r == DONE) {
                    release(mc);
                    if (itemPlanPos != null && ip.distanceToSqr(itemPlanPos) > 2.25) { path = null; itemWait = 0; return; }
                    if (++itemWait > 50) {                      // đứng cạnh mà chưa nhặt được (đầy túi / chờ delay) -> bỏ món này
                        ignoreItems.add(it.getId());
                        itemTarget = null;
                        path = null;
                    }
                }
            }
            case R_ORG -> {
                release(mc);
                if (--recTimer > 0) return;
                if (recStep++ > 40) { endRecovery(mc, p, null); return; }
                if (organize(mc, p)) recTimer = 4; else endRecovery(mc, p, null);
            }
            default -> rec = R_NONE;
        }
    }

    static void toOrganize(Minecraft mc) {
        release(mc);
        rec = R_ORG;
        recStep = 0;
        recTimer = 5;
        path = null;
        itemTarget = null;
    }

    /** Không có đường: quay mặt về phía chỗ chết, đi thẳng và nhảy khi đụng vật cản. */
    static void blindWalk(Minecraft mc, LocalPlayer p, Vec3 t) {
        double dx = t.x - p.getX(), dz = t.z - p.getZ();
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot(0f);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
    }

    static void endRecovery(Minecraft mc, LocalPlayer p, String why) {
        rec = R_NONE;
        deathRecorded = false;
        release(mc);
        path = null;
        itemTarget = null;
        target = null;
        ph = P_IDLE;
        scanCool = 0;
        baseY = p.blockPosition().getY();
        ignoreItems.clear();
        active = true;
        AutoSandOrders.msg(p, (why == null ? "Đã nhặt xong đồ, xếp lại thanh công cụ." : "Dừng nhặt đồ: " + why + ".") + " Đào tiếp.");
    }

    // ----------------------------- XẾP LẠI THANH CÔNG CỤ -----------------------------
    static int hotbarShovel(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(ItemTags.SHOVELS)) return i;
        }
        return -1;
    }

    /** Xẻng tốt nhất (còn nhiều độ bền nhất) trong túi (ô 9-35); -1 nếu không có. */
    static int bestShovelInBackpack(LocalPlayer p) {
        int best = -1;
        double bp = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty() || !st.is(ItemTags.SHOVELS)) continue;
            double pct = AutoSandOrders.remainingPct(st);
            if (pct > bp) { bp = pct; best = i; }
        }
        return best;
    }

    /** Ô thanh công cụ xẻng từng nằm trước khi chết (mặc định ô đầu tiên). */
    static int prefShovelSlot() {
        for (int i = 0; i < 9; i++) {
            if (snapShovel[i]) return i;
        }
        return 0;
    }

    static int freeHotbar(LocalPlayer p, int pref) {
        if (p.getInventory().getItem(pref).isEmpty()) return pref;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).isEmpty()) return i;
        }
        return pref;
    }

    /** Mỗi lần gọi làm 1 thao tác; trả về true nếu còn việc phải làm tiếp. */
    static boolean organize(Minecraft mc, LocalPlayer p) {
        var inv = p.getInventory();

        // 1) xẻng đang ở tay trái -> về thanh công cụ
        if (p.getOffhandItem().is(ItemTags.SHOVELS)) {
            AutoSandOrders.swapOffhand(mc, p, freeHotbar(p, prefShovelSlot()));
            return true;
        }
        // 2) xẻng nằm trong túi -> đưa lên thanh công cụ
        if (hotbarShovel(p) < 0) {
            int idx = bestShovelInBackpack(p);
            if (idx >= 9) {
                AutoSandOrders.swapHotbar(mc, p, idx, freeHotbar(p, prefShovelSlot()));
                return true;
            }
            if (!shovelWarned) {
                shovelWarned = true;
                AutoSandOrders.msg(p, "Không tìm thấy xẻng nào để đặt lại thanh công cụ.");
            }
        }
        // 3) trả các món khác (cát, bình XP, đồ ăn...) về đúng ô cũ, chỉ lấp ô đang trống
        for (int i = 0; i < 9; i++) {
            if (snap[i] == null || snapShovel[i] || !inv.getItem(i).isEmpty()) continue;
            for (int j = 9; j < 36; j++) {
                ItemStack st = inv.getItem(j);
                if (!st.isEmpty() && st.getItem() == snap[i]) {
                    AutoSandOrders.swapHotbar(mc, p, j, i);
                    return true;
                }
            }
        }
        // 4) cầm xẻng lên tay
        int hs = hotbarShovel(p);
        if (hs >= 0 && AutoSandOrders.getSelected(p) != hs) {
            AutoSandOrders.setSelected(p, hs);
            return true;
        }
        return false;
    }
}
