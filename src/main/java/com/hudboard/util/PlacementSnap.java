package com.hudboard.util;

import com.hudboard.panel.InfoPanel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * v2.4.0: auto-snap a {@code /hudboard place} target to the best matching
 * surface for the profile being placed.
 *
 * <p>If the admin looks at the SIDE of a block but places a profile
 * that's meant to lie flat (face=UP/DOWN), the placement will fail
 * because the adjacent block isn't a flat floor. This helper walks a
 * few blocks in the player's look direction, looking for a block
 * whose face matches the profile's natural orientation. Falls back to
 * the original target if nothing better is found.</p>
 *
 * <p>The "natural orientation" is computed from the profile's
 * {@code tilesW/tilesH} — if a profile is wider than tall, the natural
 * face is NORTH/SOUTH/EAST/WEST (wall). If it has equal width and
 * height, UP/DOWN is also acceptable. The actual mapping happens at
 * the placement site — this just picks a better target block when the
 * exact one is incompatible.</p>
 */
public final class PlacementSnap {

    private PlacementSnap() {}

    public static class SnappedPlace {
        public final Block block;
        public final BlockFace face;
        public SnappedPlace(Block block, BlockFace face) {
            this.block = block;
            this.face = face;
        }
    }

    /**
     * @param target  the block the admin looked at
     * @param face    the block face the admin targeted
     * @param profile the profile being placed
     * @param maxBlocks how many blocks in the look direction to search (0 = disabled)
     */
    public static SnappedPlace snap(Block target, BlockFace face,
                                     InfoPanel profile, int maxBlocks) {
        if (maxBlocks <= 0) return new SnappedPlace(target, face);
        // Decide if the targeted face is OK for this profile.
        boolean isFlat = profile.tilesH != profile.tilesW; // rough heuristic
        boolean isWallFace = face == BlockFace.NORTH || face == BlockFace.SOUTH
                || face == BlockFace.EAST || face == BlockFace.WEST;
        boolean isUpDown = face == BlockFace.UP || face == BlockFace.DOWN;
        // If the targeted face is appropriate for this profile, use it.
        if (isWallFace || (isFlat && isUpDown)) return new SnappedPlace(target, face);
        // Otherwise search the line of sight for a block whose TOP face
        // is exposed (UP) or BOTTOM face is exposed (DOWN). Walking along
        // the player's look direction (yaw/pitch) is approximated here by
        // a vertical-only search because the admin is usually looking up
        // or down when the face is wrong.
        Block candidate = target;
        // Search up to maxBlocks ABOVE (covers DOWN→UP cases)
        for (int i = 1; i <= maxBlocks; i++) {
            Block above = candidate.getRelative(BlockFace.UP);
            if (above == null) break;
            Block below = above.getRelative(BlockFace.DOWN);
            if (above.getType().isAir() && below != null && below.getType().isSolid()) {
                return new SnappedPlace(below, BlockFace.UP);
            }
            candidate = above;
        }
        // Search below (covers UP→DOWN cases)
        candidate = target;
        for (int i = 1; i <= maxBlocks; i++) {
            Block below = candidate.getRelative(BlockFace.DOWN);
            if (below == null) break;
            Block aboveBelow = below.getRelative(BlockFace.UP);
            if (below.getType().isAir() && aboveBelow != null && aboveBelow.getType().isSolid()) {
                return new SnappedPlace(aboveBelow, BlockFace.DOWN);
            }
            candidate = below;
        }
        // v2.5.0: horizontal wall-search. If the profile is wider than
        // tall (wall-mount) and the admin looked at UP/DOWN, search the
        // current Y level in the 4 cardinal directions for the closest
        // solid block that exposes a wall face (i.e. the air block
        // adjacent to it is on the side we'd attach to).
        if (!isFlat) {
            BlockFace[] horizontal = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
            for (BlockFace dir : horizontal) {
                for (int dist = 1; dist <= maxBlocks; dist++) {
                    Block air = candidate.getRelative(dir, dist);
                    if (air == null || !air.getType().isAir()) break;
                    // The block BEHIND the air block is the wall anchor.
                    Block wallAnchor = air.getRelative(opposite(dir));
                    if (wallAnchor != null && wallAnchor.getType().isSolid()) {
                        return new SnappedPlace(wallAnchor, dir);
                    }
                }
            }
        }
        return new SnappedPlace(target, face);
    }

    private static BlockFace opposite(BlockFace face) {
        switch (face) {
            case NORTH: return BlockFace.SOUTH;
            case SOUTH: return BlockFace.NORTH;
            case EAST: return BlockFace.WEST;
            case WEST: return BlockFace.EAST;
            case UP: return BlockFace.DOWN;
            case DOWN: return BlockFace.UP;
            default: return face;
        }
    }
}
