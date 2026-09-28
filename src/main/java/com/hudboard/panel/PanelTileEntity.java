package com.hudboard.panel;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * Common abstraction for the entity that displays one tile of a HudBoard panel.
 *
 * <p>Two implementations exist:</p>
 * <ul>
 *   <li>{@link PanelTileItemFrame} — for wall placements, where the wooden
 *       frame around the map is part of the aesthetic.</li>
 *   <li>{@link PanelTileItemDisplay} — for floor / ceiling placements, where
 *       a 3D frame model would create a visible "gap" between rows when
 *       viewed at any angle other than straight-down.</li>
 * </ul>
 *
 * <p>The rest of HudBoard (placement, repair, render scheduling, event
 * protection, the panel editor) talks only to this interface, so swapping
 * the backing entity type is transparent to callers.</p>
 */
public interface PanelTileEntity {

    /** Set the map this tile displays. Implementation wraps the view into
     *  the appropriate ItemStack shape for the backing entity. */
    void setMap(MapView view);

    /** True while the entity is still loaded in its world. */
    boolean isValid();

    /** Permanently remove this entity from the world. */
    void remove();

    /** World location of the entity. */
    Location getLocation();

    /** Persistent data container for the HudBoard marker tags. */
    PersistentDataContainer getPersistentDataContainer();

    /** World direction the visible side faces. Returns null for entities
     *  that don't have a single cardinal facing (ItemDisplay). */
    BlockFace getFacing();

    /** Apply HudBoard's security flags: invulnerable, silent, gravity off,
     *  visible. Implementations also disable any 3D model decorations. */
    void makeSecure();

    /** True for flat (floor/ceiling) panels — disables follow-viewer rotation. */
    boolean isFlat();

    /** Build the ItemStack (filled map) used by {@link #setMap(MapView)}.
     *  Kept on the interface so all callers go through one place. */
    ItemStack buildMapStack(MapView view);

    /** Convenience: get the raw Bukkit entity if a caller really needs it
     *  (e.g. world scan for repair). */
    Entity getEntity();
}
