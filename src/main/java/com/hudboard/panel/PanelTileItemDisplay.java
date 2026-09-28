package com.hudboard.panel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/**
 * {@link PanelTileEntity} backed by a vanilla {@link ItemDisplay}. Used for
 * floor and ceiling placements: the display has no chunky 3D frame model, so
 * the map appears as a flat image on the ground / under the ceiling. This
 * eliminates the "split in two" visual gap that item-frames produced on flat
 * surfaces (the model was ~0.5 block, leaving the floor visible between rows).
 *
 * <p>The display is given a 1.02× scale so adjacent tiles overlap by ~2% and
 * fill the seam — invisible at a glance, no perceptible distortion of the map
 * content (2% on a 128×128 texture).</p>
 *
 * <p>For ceiling panels, the transformation is rotated 180° on the X axis so
 * the map is readable from below.</p>
 *
 * <p>v1.3.7: HudBoard no longer spawns any collision barrier (Shulker or
 * otherwise) in front of its panels — all surfaces are walkable.</p>
 */
public final class PanelTileItemDisplay implements PanelTileEntity {

    /** Scale factor applied to each display so adjacent tiles overlap. 1.02 = 2% overlap. */
    public static final float TILE_SCALE = 1.02f;

    private final ItemDisplay display;
    private final boolean ceiling;

    public PanelTileItemDisplay(ItemDisplay display, boolean ceiling) {
        this.display = display;
        this.ceiling = ceiling;
    }

    public ItemDisplay raw() { return display; }
    public boolean isCeiling() { return ceiling; }

    /**
     * Spawn an ItemDisplay at the given world location with the map already
     * attached and the right transform for floor/ceiling.
     */
    public static PanelTileItemDisplay spawn(Location loc, MapView view, boolean ceiling) {
        ItemDisplay d = (ItemDisplay) Bukkit.getWorld(loc.getWorld().getUID())
                .spawnEntity(loc, EntityType.ITEM_DISPLAY);
        // Build the map stack
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        meta.displayName(null);
        meta.lore(null);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        map.setItemMeta(meta);
        d.setItemStack(map);
        // GROUND transform: item lies flat on the ground, follows the entity's yaw.
        // For ceiling, we keep GROUND but rotate the entity 180° on X so the
        // map faces downward.
        d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.GROUND);
        // Apply scale (1.02 = slight overlap with neighbours) and ceiling flip
        Transformation t = d.getTransformation();
        Vector3f scale = new Vector3f(TILE_SCALE, TILE_SCALE, TILE_SCALE);
        Vector3f translation = t.getTranslation();
        Quaternionf leftRot = t.getLeftRotation();
        if (ceiling) {
            // 180° around the X axis so the map faces down. Quaternionf
            // constructor: (angle, x, y, z) axis-angle.
            leftRot = new Quaternionf((float) Math.PI, 1f, 0f, 0f);
        }
        d.setTransformation(new Transformation(translation, leftRot, scale, new Quaternionf()));
        return new PanelTileItemDisplay(d, ceiling);
    }

    @Override
    public void setMap(MapView view) {
        display.setItemStack(buildMapStack(view));
    }

    @Override
    public boolean isValid() { return display.isValid(); }

    @Override
    public void remove() { display.remove(); }

    @Override
    public Location getLocation() { return display.getLocation(); }

    @Override
    public PersistentDataContainer getPersistentDataContainer() {
        return display.getPersistentDataContainer();
    }

    @Override
    public BlockFace getFacing() {
        // For follow-viewer rotation, only wall panels matter (face in {N,S,E,W}).
        // Flat panels stay as-is, so return null here.
        return null;
    }

    @Override
    public void makeSecure() {
        try { display.setInvulnerable(true); } catch (Throwable ignored) {}
        try { display.setSilent(true); } catch (Throwable ignored) {}
        try { display.setGravity(false); } catch (Throwable ignored) {}
        // Don't call setVisible(false): the entity IS the map; hiding it would
        // hide the map. setVisible is only useful for hiding the chunky 3D
        // model on ItemFrame, which doesn't apply here.
    }

    @Override
    public boolean isFlat() { return true; }

    @Override
    public ItemStack buildMapStack(MapView view) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        meta.displayName(null);
        meta.lore(null);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        map.setItemMeta(meta);
        return map;
    }

    @Override
    public Entity getEntity() { return display; }
}
