package com.hudboard.panel;

import com.hudboard.HudBoardPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One placed panel: 1..N tiles in a grid on a wall, floor or ceiling, all
 * sharing the same profile.
 *
 * <p>Each tile is wrapped in a {@link PanelTileEntity} so the rest of the
 * codebase doesn't care whether the underlying entity is an {@link ItemFrame}
 * (wall) or an {@link ItemDisplay} (floor/ceiling).</p>
 */
public class InfoPanelInstance {

    final HudBoardPlugin plugin;
    final InfoPanelManager mgr;
    public String name;
    public final InfoPanel profile;
    public MapView[] views;
    public final PanelTileEntity[] tiles;
    public int refreshSec = 1;
    public String world;
    public int x, y, z;
    public String face;
    // --- v2.3.0: undo/redo stack for the per-instance data-point edits.
    // Each entry is a deep copy of the profile's dataPoints BEFORE an edit.
    // Bounded to UNDO_HISTORY_SIZE so memory can't blow up if the admin
    // makes 10k changes. Redo stack lives alongside; cleared on every new
    // edit (classic undo/redo semantics).
    private static final int UNDO_HISTORY_SIZE = 32;
    private final java.util.Deque<java.util.List<InfoPanel.DataPoint>> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<java.util.List<InfoPanel.DataPoint>> redoStack = new java.util.ArrayDeque<>();

    /** Snapshot the current data-points list so a future {@link #undo()} can restore it. */
    public void snapshotForUndo() {
        java.util.List<InfoPanel.DataPoint> snap = deepCopyDps(profile.dataPoints);
        undoStack.push(snap);
        if (undoStack.size() > UNDO_HISTORY_SIZE) undoStack.removeLast();
        // New edit invalidates the redo path — the user branched away
        // from the previous timeline.
        redoStack.clear();
    }

    /** Restore the previous data-points list. Returns true if there was
     *  something to undo. The current state is pushed to the redo stack
     *  so the user can come back to it with {@link #redo()}. */
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        java.util.List<InfoPanel.DataPoint> current = deepCopyDps(profile.dataPoints);
        java.util.List<InfoPanel.DataPoint> previous = undoStack.pop();
        profile.dataPoints.clear();
        profile.dataPoints.addAll(previous);
        redoStack.push(current);
        if (redoStack.size() > UNDO_HISTORY_SIZE) redoStack.removeLast();
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        java.util.List<InfoPanel.DataPoint> current = deepCopyDps(profile.dataPoints);
        java.util.List<InfoPanel.DataPoint> next = redoStack.pop();
        profile.dataPoints.clear();
        profile.dataPoints.addAll(next);
        undoStack.push(current);
        if (undoStack.size() > UNDO_HISTORY_SIZE) undoStack.removeLast();
        return true;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** Deep-copy a list of data-points. We only need primitive fields
     *  (String, int) plus the nested fields added in v2.3.0 (bg, align,
     *  padding, animColor, animMs) — no shared references. */
    private static java.util.List<InfoPanel.DataPoint> deepCopyDps(java.util.List<InfoPanel.DataPoint> src) {
        java.util.List<InfoPanel.DataPoint> out = new java.util.ArrayList<>(src.size());
        for (InfoPanel.DataPoint dp : src) {
            InfoPanel.DataPoint copy = new InfoPanel.DataPoint();
            copy.key = dp.key;
            copy.tileX = dp.tileX;
            copy.tileY = dp.tileY;
            copy.x = dp.x;
            copy.y = dp.y;
            copy.text = dp.text;
            copy.size = dp.size;
            copy.color = dp.color;
            copy.baseColor = dp.baseColor;
            copy.animation = dp.animation;
            copy.animColor = dp.animColor;
            copy.animMs = dp.animMs;
            copy.bg = dp.bg;
            copy.align = dp.align;
            copy.padding = dp.padding;
            copy.alignMode = dp.alignMode;
            out.add(copy);
        }
        return out;
    }

    public InfoPanelInstance(InfoPanelManager mgr, HudBoardPlugin plugin, String name,
                             InfoPanel profile, MapView[] views) {
        this.mgr = mgr;
        this.plugin = plugin;
        this.name = name;
        this.profile = profile;
        this.views = views;
        this.tiles = new PanelTileEntity[profile.tilesW * profile.tilesH];
    }

    /** Replace the map views after construction (used when the renderer needs the instance). */
    public void setViews(MapView[] newViews) { this.views = newViews; }

    /** Clear the cached tile slots (used by force-repair before
     *  re-attaching to the real frames at the new position). */
    public void clearTiles() {
        for (int i = 0; i < tiles.length; i++) tiles[i] = null;
    }

    /** Legacy alias used by some older callers. */
    public void clearFrames() { clearTiles(); }

    /**
     * Try to re-attach to existing tagged entities (ItemFrame or ItemDisplay)
     * in the panel's grid. Used on server restart to avoid wiping and
     * re-spawning (which creates a window of invisibility). Looks in a
     * tilesW × tilesH block region around `origin`, matching by proximity to
     * expected tile positions.
     *
     * <p>v1.3.7: HudBoard no longer tracks any collision barrier — Shulker
     * orphans from older versions (still tagged with our marker) are
     * detected and removed during the reattach pass so they don't linger
     * as ghost blocks after the upgrade.</p>
     */
    public int reattachFromWorld(World w, Block origin, boolean flat) {
        if (origin == null) return 0;
        this.world = w.getName();
        this.x = origin.getX();
        this.y = origin.getY();
        // Gather every tagged HudBoard entity within tilesW × tilesH of origin.
        // ItemFrames + ItemDisplays are tile candidates; leftover Shulker
        // barriers from pre-v1.3.7 installs are killed here so they don't
        // accumulate as ghost collision blocks.
        int searchRadius = Math.max(profile.tilesW, profile.tilesH) + 2;
        java.util.List<Entity> nearby = new java.util.ArrayList<>();
        java.util.List<org.bukkit.entity.Shulker> leftoverBarriers = new java.util.ArrayList<>();
        for (var e : w.getNearbyEntities(
                new Location(w, origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5),
                searchRadius, searchRadius, searchRadius)) {
            if (!(e instanceof ItemFrame || e instanceof ItemDisplay || e instanceof org.bukkit.entity.Shulker)) continue;
            if (!e.getPersistentDataContainer().has(InfoPanelManager.MARKER,
                    org.bukkit.persistence.PersistentDataType.STRING)) continue;
            String n = e.getPersistentDataContainer().get(InfoPanelManager.NAME_KEY,
                    org.bukkit.persistence.PersistentDataType.STRING);
            if (!name.equals(n)) continue;
            if (e instanceof org.bukkit.entity.Shulker s) leftoverBarriers.add(s);
            else nearby.add(e);
        }
        // Remove pre-v1.3.7 barrier Shulkers immediately. They were spawned
        // for the now-removed collision barrier feature and aren't tracked
        // anywhere else, so deleting them on reattach is the right cleanup.
        for (var b : leftoverBarriers) {
            if (b != null && b.isValid()) b.remove();
        }
        if (nearby.isEmpty()) return 0;
        org.bukkit.block.BlockFace parsedFace = org.bukkit.block.BlockFace.NORTH;
        try {
            parsedFace = org.bukkit.block.BlockFace.valueOf(this.face);
        } catch (Throwable ignored) {}
        final org.bukkit.block.BlockFace face = parsedFace;
        final int ax = origin.getX();
        final int ay = origin.getY();
        final int az = origin.getZ();
        final int tilesH = profile.tilesH;
        final int tilesW = profile.tilesW;
        final boolean isFlat = flat;
        nearby.sort((a, b) -> {
            int[] at = mapToTile(a.getLocation().getBlockX(), a.getLocation().getBlockY(), a.getLocation().getBlockZ(),
                    ax, ay, az, face, isFlat, tilesW, tilesH);
            int[] bt = mapToTile(b.getLocation().getBlockX(), b.getLocation().getBlockY(), b.getLocation().getBlockZ(),
                    ax, ay, az, face, isFlat, tilesW, tilesH);
            int tc = Integer.compare(at[1], bt[1]);
            if (tc != 0) return tc;
            return Integer.compare(at[0], bt[0]);
        });
        int n = Math.min(nearby.size(), profile.tilesW * profile.tilesH);
        for (int i = 0; i < n; i++) {
            Entity ent = nearby.get(i);
            int tx = i % profile.tilesW;
            int ty = i / profile.tilesW;
            PanelTileEntity tile = wrap(ent);
            tile.setMap(views[ty * profile.tilesW + tx]);
            tag(tile);
            tile.makeSecure();
            tiles[ty * profile.tilesW + tx] = tile;
            org.bukkit.map.MapView v = views[ty * profile.tilesW + tx];
            try {
                java.lang.reflect.Method m = v.getClass().getMethod("render");
                m.invoke(v);
            } catch (Throwable ignored) {}
        }
        final int finalN = n;
        final InfoPanelInstance captured = this;
        org.bukkit.Bukkit.getScheduler().runTaskLater(org.bukkit.Bukkit.getPluginManager().getPlugin("HudBoard"), () -> {
            for (int i = 0; i < finalN; i++) {
                org.bukkit.map.MapView v = views[i];
                if (v == null) continue;
                try {
                    java.lang.reflect.Method m = v.getClass().getMethod("render");
                    m.invoke(v);
                } catch (Throwable ignored) {}
            }
            if (com.hudboard.nms.MapDirectSender.isAvailable()) {
                for (org.bukkit.map.MapView v : views) {
                    if (v == null) continue;
                    for (var r : v.getRenderers()) {
                        if (r instanceof com.hudboard.panel.InfoPanelRenderer ipr) {
                            ipr.forceFrameToViewers();
                        }
                    }
                }
            }
        }, 20L);
        org.bukkit.Bukkit.getScheduler().runTaskLater(org.bukkit.Bukkit.getPluginManager().getPlugin("HudBoard"), () -> {
            if (!com.hudboard.nms.MapDirectSender.isAvailable()) return;
            for (org.bukkit.map.MapView v : views) {
                if (v == null) continue;
                for (var r : v.getRenderers()) {
                    if (r instanceof com.hudboard.panel.InfoPanelRenderer ipr) {
                        ipr.forceFrameToViewers();
                    }
                }
            }
        }, 1L);
        return n;
    }

    /**
     * Wrap an entity found in the world into the right {@link PanelTileEntity}
     * implementation based on its type. Used by reattach + repair.
     */
    private PanelTileEntity wrap(Entity e) {
        if (e instanceof ItemFrame f) return new PanelTileItemFrame(f);
        if (e instanceof ItemDisplay d) {
            boolean ceiling = "DOWN".equals(this.face);
            return new PanelTileItemDisplay(d, ceiling);
        }
        throw new IllegalStateException("Unknown tile entity: " + e.getType());
    }

    /**
     * Place this panel on the wall behind `target` (the clicked block).
     * The panel's bottom-left tile sits at target.getRelative(face) and extends
     * along right + up. `face` is the wall's normal (pointing toward the admin).
     * Wall placements use {@link ItemFrame} (the wooden frame is part of the look).
     */
    public int placeAt(Block target, BlockFace face, Player by) {
        if (target == null || face == null) return 0;
        final Vector wallNormal = new Vector(face.getModX(), face.getModY(), face.getModZ());
        final Vector up = new Vector(0, 1, 0);
        final Vector right = up.clone().crossProduct(wallNormal);
        if (right.lengthSquared() < 1e-6) right.setX(1);
        right.normalize();
        final int rx = (int) Math.signum(right.getX());
        final int rz = (int) Math.signum(right.getZ());
        return placeOn(target, face, face.name(), (tx, ty) -> {
            int dx = rx * tx;
            int dy = profile.tilesH - 1 - ty;
            int dz = rz * tx;
            return target.getRelative(dx, dy, dz);
        }, by);
    }

    /**
     * Place this panel flat on a horizontal surface (floor or ceiling).
     * For ceiling=true, item-displays hang DOWN from `target` and face DOWN.
     * For ceiling=false, item-displays sit UP on `target` and face UP.
     * Flat placements use {@link ItemDisplay} (no chunky frame model, so no
     * visible gap between rows).
     */
    public int placeFlat(Block target, boolean ceiling, Player by) {
        if (target == null) return 0;
        final BlockFace frameFace = ceiling ? BlockFace.DOWN : BlockFace.UP;
        final int dy = ceiling ? -1 : 1;
        return placeOnFlat(target, frameFace, frameFace.name(), (tx, ty) -> {
            int dz = profile.tilesH - 1 - ty;
            return target.getRelative(tx, 0, dz);
        }, (tx, ty, attachAt) -> attachAt.getRelative(0, dy, 0), ceiling, by);
    }

    /**
     * Shared placement engine for wall panels (ItemFrame-based).
     */
    private int placeOn(Block target, BlockFace frameFace, String faceName,
                        java.util.function.BiFunction<Integer, Integer, Block> attachBlockFor,
                        Player by) {
        return placeOn(target, frameFace, faceName, attachBlockFor,
                (tx, ty, attachAt) -> attachAt.getRelative(frameFace), false, by);
    }

    /**
     * Shared placement engine that handles every panel-agnostic step:
     * chunk load, "on player" check, world/x/y/z/face bookkeeping,
     * entity spawn + security + map item + tag, anchor PDC marking.
     * For wall panels the air block is offset by `frameFace`; for flat
     * panels the caller fully controls the air block via {@code airBlockFor}.
     */
    private int placeOnFlat(Block target, BlockFace frameFace, String faceName,
                            java.util.function.BiFunction<Integer, Integer, Block> attachBlockFor,
                            TriBlockResolver airBlockFor,
                            boolean flat,
                            Player by) {
        return placeOn(target, frameFace, faceName, attachBlockFor, airBlockFor, flat, by);
    }

    private int placeOn(Block target, BlockFace frameFace, String faceName,
                        java.util.function.BiFunction<Integer, Integer, Block> attachBlockFor,
                        TriBlockResolver airBlockFor,
                        boolean flat,
                        Player by) {
        if (target == null || frameFace == null) return 0;
        World w = target.getWorld();
        try { w.getChunkAt(target.getLocation()).load(); } catch (Throwable ignored) {}
        if (by != null) {
            Location playerBlock = by.getLocation().getBlock().getLocation();
            if (target.getX() == playerBlock.getBlockX() && target.getY() == playerBlock.getBlockY() && target.getZ() == playerBlock.getBlockZ()) {
                return -1;
            }
        }
        this.world = w.getName();
        this.x = target.getX();
        this.y = target.getY();
        this.z = target.getZ();
        this.face = faceName;
        int placed = 0;
        for (int ty = 0; ty < profile.tilesH; ty++) {
            for (int tx = 0; tx < profile.tilesW; tx++) {
                Block attachAt = attachBlockFor.apply(tx, ty);
                Block airBlock = airBlockFor.resolve(tx, ty, attachAt);
                if (!airBlock.getType().isAir() && !airBlock.isPassable()) continue;
                Location frameLoc = airBlock.getLocation().add(0.5, 0.5, 0.5);
                PanelTileEntity tile;
                if (flat) {
                    boolean ceiling = frameFace == BlockFace.DOWN;
                    tile = PanelTileItemDisplay.spawn(frameLoc, views[ty * profile.tilesW + tx], ceiling);
                } else {
                    ItemFrame f = w.spawn(frameLoc, ItemFrame.class);
                    applyFrameSecurity(f, frameFace);
                    ItemStack mapItem = buildMapItem(views[ty * profile.tilesW + tx]);
                    f.setItem(mapItem);
                    tile = new PanelTileItemFrame(f);
                }
                tile.makeSecure();
                tag(tile);
                if (ty == profile.tilesH - 1 && tx == 0) {
                    tile.getPersistentDataContainer().set(
                            com.hudboard.panel.InfoPanelManager.ANCHOR_KEY,
                            org.bukkit.persistence.PersistentDataType.STRING, "1");
                }
                tiles[ty * profile.tilesW + tx] = tile;
                placed++;
            }
        }
        // For wall panels, re-assert direction once after spawn so every frame
        // faces the EXACT same BlockFace (item-frames can rotate 8-way, this
        // normalizes them to a single cardinal direction). ItemDisplay with
        // GROUND transform doesn't need this.
        if (!flat) {
            for (PanelTileEntity tile : tiles) {
                if (tile == null) continue;
                if (tile.getEntity() instanceof ItemFrame f && f.isValid()) {
                    f.setFacingDirection(frameFace);
                }
            }
        }
        return placed;
    }

    /** Resolver for the air block the tile entity spawns in. */
    @FunctionalInterface
    private interface TriBlockResolver {
        Block resolve(int tx, int ty, Block attachAt);
    }

    /**
     * Apply security flags to a newly spawned item-frame (wall tile). For
     * ItemDisplay tiles, security is applied by PanelTileItemDisplay itself.
     */
    private void applyFrameSecurity(ItemFrame f, BlockFace face) {
        f.setFacingDirection(face);
        f.setFixed(true);
        try { f.setInvulnerable(true); } catch (Throwable ignored) {}
        try { f.setVisible(false); } catch (Throwable ignored) {}
        try { f.setSilent(true); } catch (Throwable ignored) {}
    }

    public int remove() {
        int n = 0;
        for (PanelTileEntity tile : tiles) {
            if (tile != null && tile.isValid()) { tile.remove(); n++; }
        }
        return n;
    }

    public void tag(PanelTileEntity tile) {
        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        pdc.set(InfoPanelManager.MARKER, PersistentDataType.STRING, "1");
        pdc.set(InfoPanelManager.NAME_KEY, PersistentDataType.STRING, name);
        pdc.set(InfoPanelManager.PROFILE_KEY, PersistentDataType.STRING, profile.id);
    }

    public Player nearestPlayer() { return nearestPlayer(Integer.MAX_VALUE); }

    public Player nearestPlayer(int maxDist) {
        return nearestPlayer(maxDist, Bukkit.getOnlinePlayers().toArray(new Player[0]));
    }

    public Player nearestPlayer(int maxDist, Player[] players) {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        Location center = new Location(w, x + 0.5, y + 0.5, z + 0.5);
        double bestSq = (double) maxDist * maxDist;
        Player nearest = null;
        for (Player p : players) {
            if (!p.getWorld().equals(w)) continue;
            double d = p.getLocation().distanceSquared(center);
            if (d < bestSq) { bestSq = d; nearest = p; }
        }
        return nearest;
    }

    private ItemStack buildMapItem(MapView view) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        meta.displayName(null);
        meta.lore(null);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        map.setItemMeta(meta);
        return map;
    }

    private static int[] mapToTile(int fx, int fy, int fz,
                                   int ax, int ay, int az,
                                   org.bukkit.block.BlockFace face, boolean flat,
                                   int tilesW, int tilesH) {
        int ty;
        if (flat) {
            ty = (az + tilesH - 1) - fz;
        } else {
            ty = (ay + tilesH - 1) - fy;
        }
        int tx;
        if (flat) {
            tx = fx - ax;
        } else {
            switch (face) {
                case NORTH: tx = ax - fx; break;
                case EAST:  tx = az - fz; break;
                case WEST:  tx = fz - az; break;
                case SOUTH:
                default:    tx = fx - ax; break;
            }
        }
        if (ty < 0) ty = 0;
        if (ty >= tilesH) ty = tilesH - 1;
        if (tx < 0) tx = 0;
        if (tx >= tilesW) tx = tilesW - 1;
        return new int[]{tx, ty};
    }

    // ---- legacy accessors (used by code paths we haven't migrated yet) ----

    /**
     * @deprecated Use {@link #tiles} directly. Kept as a transitional alias so
     *             older callers keep compiling during the migration.
     */
    @Deprecated
    public ItemFrame[] frames() {
        ItemFrame[] out = new ItemFrame[tiles.length];
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] instanceof PanelTileItemFrame pf) out[i] = pf.raw();
        }
        return out;
    }
}
