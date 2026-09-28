package com.hudboard.listener;

import com.hudboard.HudBoardPlugin;
import com.hudboard.data.providers.PlayerProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {

    private final HudBoardPlugin plugin;

    public PlayerListener(HudBoardPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerProvider.onJoin(p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (plugin.getConfigManager().autoPlaceOnJoin() && p.hasPermission("hudboard.admin")) {
                org.bukkit.block.Block target = p.getLocation().getBlock();
                org.bukkit.block.BlockFace face = p.getFacing().getOppositeFace();
                plugin.getPanelManager().placeAt(p, plugin.getConfigManager().autoPlaceProfile(), target, face);
            }
            // Kick the deferred retry so panels in the same world as the
            // joining player are force-loaded and reattached immediately,
            // without waiting for the 2s periodic loop.
            int retried = plugin.getPanelManager().retryDeferredPlacements();
            if (retried > 0) {
                plugin.getLogger().info("[HudBoard] resolved " + retried + " deferred panel(s) on " + p.getName() + " join");
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PlayerProvider.onQuit(e.getPlayer());
        PlayerProvider.unload(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        if (e.getEntity().getKiller() instanceof Player k) {
            PlayerProvider.addKill(k);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        PlayerProvider.addDeath(e.getEntity());
    }
}
