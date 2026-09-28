package com.hudboard.panel;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * {@link PanelTileEntity} backed by a vanilla {@link ItemFrame}. Used for wall
 * placements where the wooden frame around the map is part of the charm.
 *
 * <p>v1.3.7: the invisible Shulker collision barrier that used to sit in
 * front of wall tiles has been removed. Players can now walk through panels
 * (same behaviour as floor / ceiling). Existing Shulker barriers from older
 * versions are still swept by HudBoard's orphan cleanup on startup so they
 * don't accumulate as ghost collision blocks.</p>
 */
public final class PanelTileItemFrame implements PanelTileEntity {

    private final ItemFrame frame;

    public PanelTileItemFrame(ItemFrame frame) { this.frame = frame; }

    public ItemFrame raw() { return frame; }

    @Override
    public void setMap(MapView view) {
        frame.setItem(buildMapStack(view));
    }

    @Override
    public boolean isValid() { return frame.isValid(); }

    @Override
    public void remove() { frame.remove(); }

    @Override
    public Location getLocation() { return frame.getLocation(); }

    @Override
    public PersistentDataContainer getPersistentDataContainer() {
        return frame.getPersistentDataContainer();
    }

    @Override
    public BlockFace getFacing() { return frame.getFacing(); }

    @Override
    public void makeSecure() {
        frame.setFixed(true);
        try { frame.setInvulnerable(true); } catch (Throwable ignored) {}
        try { frame.setVisible(false); } catch (Throwable ignored) {}
        try { frame.setSilent(true); } catch (Throwable ignored) {}
    }

    @Override
    public boolean isFlat() { return false; }

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
    public Entity getEntity() { return frame; }
}
