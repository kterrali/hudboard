package com.hudboard.nms;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends raw {@code ClientboundMapItemDataPacket}s to players, bypassing
 * Bukkit's slow map render cycle. This is HudBoard's "own ProtocolLib" —
 * we use the server's own NMS classes (Paper 1.21.11 Mojang-mapped) to
 * inject a fresh map data packet at 20 Hz.
 *
 * <h2>Why?</h2>
 * <p>Minecraft only re-renders a map item (e.g. one in an item-frame) when
 * the underlying {@code MapView} tells it to. That happens every
 * 250-500ms by default. For an animated GIF, that's choppy.</p>
 *
 * <p>This class builds and sends the packet directly via the player's
 * network connection, forcing a redraw within ~50ms. We never touch the
 * disk-backed {@code MapItemSavedData} (which would persist changes
 * between restarts and fight with concurrent renders).</p>
 *
 * <h2>Reflection-based for safety</h2>
 * <p>The NMS API has shifted between Paper versions (1.20 used {@code int mapId},
 * 1.21+ uses {@code MapId}). We use reflection to find the right classes at
 * runtime so the plugin can survive minor Mojang mapping changes. If
 * anything goes wrong, {@link #isAvailable()} returns {@code false} and the
 * caller falls back to Bukkit's slow but reliable canvas rendering.</p>
 */
public final class MapDirectSender {

    private static volatile boolean available = false;
    private static volatile String failReason = null;

    // Cached reflection
    private static Constructor<?> ctorPacket;     // (MapId, byte, boolean, Collection, MapPatch)
    private static Constructor<?> ctorMapPatch;   // (int, int, int, int, byte[])
    private static Constructor<?> ctorMapId;      // (int) or similar
    private static Method getConnection;          // ServerPlayer -> ServerGamePacketListenerImpl (1.21.1-1.21.3)
    private static java.lang.reflect.Field fieldConnection;  // ServerPlayer.connection (1.21.4+)
    private static Method sendPacket;             // ServerGamePacketListenerImpl -> Packet
    private static Class<?> packetClass;
    private static Class<?> mapPatchClass;
    private static Class<?> mapIdClass;

    static {
        try {
            // Class names (Mojang mappings on Paper 1.21.11)
            packetClass   = Class.forName("net.minecraft.network.protocol.game.ClientboundMapItemDataPacket");
            mapPatchClass = Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData$MapPatch");
            // MapId moved package in 1.21.4+: was net.minecraft.world.component.MapId,
            // now net.minecraft.world.level.saveddata.maps.MapId. Try both.
            String[] mapIdPaths = {
                "net.minecraft.world.level.saveddata.maps.MapId",       // 1.21.4+
                "net.minecraft.world.component.MapId"                     // 1.21.1 - 1.21.3
            };
            for (String path : mapIdPaths) {
                try {
                    mapIdClass = Class.forName(path);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }
            if (mapIdClass == null) throw new RuntimeException("MapId class not found in any known path");

            // Find the ctor we want. There are two on 1.21.11:
            //   (MapId, byte, boolean, Collection<MapDecoration>, MapPatch)
            //   (MapId, byte, boolean, Optional<List<MapDecoration>>, Optional<MapPatch>)
            for (Constructor<?> c : packetClass.getDeclaredConstructors()) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length == 5
                        && params[0] == mapIdClass
                        && params[1] == byte.class
                        && params[2] == boolean.class
                        && !params[3].getName().startsWith("java.util.Optional")
                        && params[4] == mapPatchClass) {
                    c.setAccessible(true);
                    ctorPacket = c;
                    break;
                }
            }
            if (ctorPacket == null) throw new RuntimeException("No matching ClientboundMapItemDataPacket ctor found");

            // MapPatch ctor: (int startX, int startZ, int width, int height, byte[] colors)
            ctorMapPatch = mapPatchClass.getDeclaredConstructor(int.class, int.class, int.class, int.class, byte[].class);
            ctorMapPatch.setAccessible(true);

            // MapId ctor: typically (int)
            for (Constructor<?> c : mapIdClass.getDeclaredConstructors()) {
                if (c.getParameterCount() == 1 && c.getParameterTypes()[0] == int.class) {
                    c.setAccessible(true);
                    ctorMapId = c;
                    break;
                }
            }
            if (ctorMapId == null) throw new RuntimeException("No (int) ctor on MapId");

            // ServerPlayer.connection — in 1.21.4+ it's a public field, before
            // that it was a no-arg method. Try both.
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            java.lang.reflect.Field connField = null;
            try { connField = serverPlayer.getField("connection"); }
            catch (NoSuchFieldException ignored) {}
            if (connField != null) {
                // Field access: use a wrapper MethodHandle that reads the field
                connField.setAccessible(true);
                java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
                java.lang.invoke.MethodHandle fieldGetter = lookup.unreflectGetter(connField);
                // We can't easily cache a MethodHandle in a Method variable, so
                // store it as a Field and reflect-read in sendPatch
                // For simplicity, fall through to using the field directly in sendPatch
                fieldConnection = connField;
            } else {
                getConnection = serverPlayer.getMethod("connection");
            }
            // ServerGamePacketListenerImpl.send — find the single-arg send method
            Class<?> connClass = connField != null ? connField.getType() : getConnection.getReturnType();
            Class<?> packetBase = Class.forName("net.minecraft.network.protocol.Packet");
            sendPacket = connClass.getMethod("send", packetBase);

            available = true;
        } catch (Throwable t) {
            available = false;
            failReason = t.toString();
            // Not a critical error: the plugin still works via Bukkit's slower
            // canvas rendering. Logged at INFO (not WARNING) since this is
            // expected on some Paper versions and not actionable by the user.
            Logger.getLogger("HudBoard").log(Level.INFO,
                    "[HudBoard] MapDirectSender NMS not available (" + t.getClass().getSimpleName() + "): "
                            + t.getMessage() + " — using Bukkit canvas rendering. "
                            + "GIFs will play at the natural map render rate (~5Hz) instead of 20Hz. "
                            + "Run /hudboard debug nms for details.");
        }
    }

    private MapDirectSender() {}

    public static boolean isAvailable() { return available; }
    public static String getFailReason() { return failReason; }

    /**
     * Send a 128x128 patch of map data to one player. Cheap enough to call
     * every animation frame (≤ 20 Hz) for every viewer.
     *
     * @param player    the target viewer
     * @param mapId     the integer map id (from {@code MapView.getId()})
     * @param pixels    {@code byte[128*128]} of map colour indices (one per pixel)
     */
    public static void sendPatch(Player player, int mapId, byte[] pixels) {
        if (!available || pixels == null) return;
        if (pixels.length < 128 * 128) return;
        try {
            // Build a 128x128 patch
            Object patch = ctorMapPatch.newInstance(0, 0, 128, 128, pixels);
            Object mapIdObj = ctorMapId.newInstance(mapId);
            Object packet = ctorPacket.newInstance(
                    mapIdObj, (byte) 0, false, Collections.emptyList(), patch);

            // Send via the player's connection
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object connection;
            if (fieldConnection != null) {
                // 1.21.4+: public field
                connection = fieldConnection.get(handle);
            } else {
                // 1.21.1 - 1.21.3: no-arg method
                connection = getConnection.invoke(handle);
            }
            sendPacket.invoke(connection, packet);
        } catch (Throwable t) {
            // One-shot disable to avoid log spam
            available = false;
            failReason = "Runtime failure: " + t;
            Logger.getLogger("HudBoard").log(Level.WARNING,
                    "[HudBoard] MapDirectSender send failed, disabling: " + t);
        }
    }
}
