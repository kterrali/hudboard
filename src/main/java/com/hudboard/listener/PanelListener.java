package com.hudboard.listener;

import com.hudboard.HudBoardPlugin;
import com.hudboard.panel.InfoPanelManager;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Security + interaction listener. Handles both {@link ItemFrame} (wall) and
 * {@link ItemDisplay} (floor/ceiling) backing entities.
 *
 * <p>v1.3.7: Shulker collision barriers are no longer spawned by HudBoard,
 * so {@link #isOurs} only matches ItemFrame and ItemDisplay. Legacy Shulker
 * orphans from older versions are still swept by the manager's orphan
 * cleanup but no longer trigger interaction / damage events here.</p>
 */
public class PanelListener implements Listener {

    private final HudBoardPlugin plugin;

    public PanelListener(HudBoardPlugin plugin) { this.plugin = plugin; }

    /**
     * True if `entity` is one of our tile entities: an ItemFrame (wall tile)
     * or an ItemDisplay (floor/ceiling tile), all carrying the HudBoard
     * marker. Shulker barriers are no longer tracked (v1.3.7).
     */
    private boolean isOurs(Entity entity) {
        if (!(entity instanceof ItemFrame) && !(entity instanceof ItemDisplay)) return false;
        return entity.getPersistentDataContainer().has(InfoPanelManager.MARKER, PersistentDataType.STRING);
    }

    /**
     * Player right-clicks a tile entity → open the editor menu (admins).
     * Fires for both ItemFrame (Hanging) and ItemDisplay (regular entity).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent e) {
        Entity clicked = e.getRightClicked();
        if (!isOurs(clicked)) return;
        PersistentDataContainer pdc = clicked.getPersistentDataContainer();
        String name = pdc.get(InfoPanelManager.NAME_KEY, PersistentDataType.STRING);
        if (name == null) return;
        Player p = e.getPlayer();
        e.setCancelled(true);

        if (p.hasPermission("hudboard.edit")) {
            var inst = plugin.getPanelManager().get(name, true);
            if (inst == null) {
                p.sendMessage(plugin.getLang().get("panel.orphan", "%name%", name));
                return;
            }
            new com.hudboard.menu.PanelEditMenu(plugin, inst).open(p);
        }
        // Non-admin: nothing. The map is read-only for them.
    }

    /** Generic hanging break (physics, water flow, etc.). Only applies to
     *  ItemFrame tiles (ItemDisplay isn't a Hanging entity). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (!(e.getEntity() instanceof ItemFrame f)) return;
        if (isOurs(f)) e.setCancelled(true);
    }

    /** A player or mob is trying to break our wall tile.
     *  v2.6.1: silent block — no chat message, no sound, no actionbar.
     *  Even admins can't break panels directly; they use
     *  {@code /hudboard remove <name>} instead. The cancellation is
     *  visible only by the absence of the break action itself, which
     *  is exactly the UX we want for a public install. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent e) {
        if (!(e.getEntity() instanceof ItemFrame f)) return;
        if (isOurs(f)) {
            e.setCancelled(true);
            // No message. No deny sound. Silent.
        }
    }

    /**
     * Direct damage events on any HudBoard tile. Applies to both ItemFrame
     * (ItemFrames also get HangingBreakByEntity, but EntityDamageByEntity is
     * the universal case — arrows, fireworks, /damage command, etc.) and
     * ItemDisplay (ItemDisplays are NOT hanging entities, so they only fire
     * EntityDamageByEntity).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!isOurs(e.getEntity())) return;
        e.setCancelled(true);
    }

    /** Generic damage event (covers damage types without an attacker:
     *  falling, cacti, drowning, etc.). Both ItemFrame and ItemDisplay
     *  fire this when their entity is invulnerable. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGenericDamage(EntityDamageEvent e) {
        if (!isOurs(e.getEntity())) return;
        e.setCancelled(true);
    }

    /** Prevent a player from replacing our map with a new item. Wall only. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (!(e.getEntity() instanceof ItemFrame f)) return;
        if (isOurs(f)) e.setCancelled(true);
    }

    /**
     * Explosions (TNT, creepers, end crystals, beds in nether). Strip the
     * support block of any of our tile entities from the explosion's block
     * list so the panel stays anchored. ItemFrames are invulnerable AND
     * setFixed(true), so even if the support does blow up they float in
     * place. ItemDisplays are invulnerable + no gravity, so they stay put.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(block -> isTileAnchoredTo(block));
    }

    /**
     * Boats and minecarts colliding with item-frames or item-displays can
     * destroy them on some Paper builds. Block that explicitly by detecting
     * nearby HudBoard tiles.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent e) {
        var veh = e.getVehicle();
        for (Entity ent : veh.getNearbyEntities(2, 2, 2)) {
            if (isOurs(ent)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Check if any HudBoard tile (ItemFrame or ItemDisplay) is attached to
     * (i.e. hanging from, or sitting on) the given block. Used by the
     * explosion handler to keep panels anchored.
     */
    private boolean isTileAnchoredTo(Block block) {
        for (Entity ent : block.getWorld().getNearbyEntities(
                block.getLocation().add(0.5, 0.5, 0.5), 1.5, 1.5, 1.5)) {
            if (isOurs(ent)) return true;
        }
        return false;
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        plugin.getPanelManager().removeByWorld(e.getWorld());
    }

    /**
     * Handles "move mode": when an admin has clicked the "Move panel" button
     * in the editor GUI, the next right-click on a block moves the panel there.
     * Sneak to cancel.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onMoveModeClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (!plugin.getPanelManager().hasPendingMove(p.getUniqueId())) return;
        e.setCancelled(true);
        if (p.isSneaking()) {
            plugin.getPanelManager().consumePendingMove(p.getUniqueId());
            p.sendMessage(plugin.getLang().get("move.cancelled"));
            return;
        }
        String name = plugin.getPanelManager().consumePendingMove(p.getUniqueId());
        if (name == null) return;
        var inst = plugin.getPanelManager().get(name, true);
        if (inst == null) {
            p.sendMessage(plugin.getLang().get("panel.not-found", "%name%", name));
            return;
        }
        Block target = e.getClickedBlock();
        BlockFace face = e.getBlockFace();
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            p.sendMessage(plugin.getLang().get("place.face-rejected"));
            return;
        }
        boolean ok = plugin.getPanelManager().moveTo(inst, target, face);
        if (ok) {
            p.sendMessage(plugin.getLang().get("move.done", "%name%", inst.name, "%pos%", inst.world + " " + inst.x + " " + inst.y + " " + inst.z));
        } else {
            p.sendMessage(plugin.getLang().get("move.failed"));
        }
    }
}
