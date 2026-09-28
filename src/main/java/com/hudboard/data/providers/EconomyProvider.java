package com.hudboard.data.providers;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EconomyProvider {
    private EconomyProvider() {}

    private static Object economy() {
        try {
            Class<?> cls = Class.forName("net.milkbowl.vault.economy.Economy");
            @SuppressWarnings("unchecked")
            RegisteredServiceProvider<Object> rsp = Bukkit.getServicesManager().getRegistration((Class) cls);
            return rsp == null ? null : rsp.getProvider();
        } catch (Throwable t) { return null; }
    }

    /** Name of the registered economy provider (e.g. "VireliaEconomy"), for diagnostics. */
    public static String providerName() {
        Object econ = economy();
        return econ == null ? "<none>" : econ.getClass().getName();
    }

    /**
     * Resolve a player's balance by trying every reasonable getBalance signature
     * on the registered economy provider. Returns -1 if no signature worked.
     * Caches the working signature per-class for performance.
     */
    private static final Map<Class<?>, Method> BALANCE_METHOD_CACHE = new LinkedHashMap<>();

    public static String balance(Player p) {
        if (p == null) return "0";
        Object econ = economy();
        if (econ == null) return "0";
        double v = readBalance(econ, p);
        if (v < 0) v = 0; // unknown → 0
        return formatAmount(v);
    }

    private static double readBalance(Object econ, Player p) {
        Class<?> cls = econ.getClass();
        // Cache hit
        Method cached = BALANCE_METHOD_CACHE.get(cls);
        if (cached != null) {
            try {
                Object r = cached.invoke(econ, p);
                if (r instanceof Number n) return n.doubleValue();
                if (r != null) {
                    try { return Double.parseDouble(r.toString()); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) { BALANCE_METHOD_CACHE.remove(cls); }
        }
        // Try every plausible signature, in priority order
        Class<?>[][] paramTypes = {
                {OfflinePlayer.class},
                {Player.class},
                {UUID.class},
                {String.class},
                {OfflinePlayer.class, String.class},
                {String.class, String.class},
        };
        for (Class<?>[] pts : paramTypes) {
            try {
                Method m = cls.getMethod("getBalance", pts);
                Object result;
                if (pts.length == 1) {
                    if (pts[0] == OfflinePlayer.class) result = m.invoke(econ, p);
                    else if (pts[0] == Player.class) result = m.invoke(econ, p);
                    else if (pts[0] == UUID.class) result = m.invoke(econ, p.getUniqueId());
                    else result = m.invoke(econ, p.getName());
                } else if (pts.length == 2) {
                    if (pts[0] == OfflinePlayer.class) result = m.invoke(econ, p, (String) null);
                    else result = m.invoke(econ, p.getName(), (String) null);
                } else continue;
                if (result instanceof Number n) {
                    BALANCE_METHOD_CACHE.put(cls, m);
                    return n.doubleValue();
                }
                if (result != null) {
                    try {
                        double v = Double.parseDouble(result.toString());
                        BALANCE_METHOD_CACHE.put(cls, m);
                        return v;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) { /* try next */ }
        }
        return -1;
    }

    /**
     * Diagnostic: list every method named getBalance on the economy class, with
     * parameter types. Useful to debug custom economy plugins like VireliaEconomy.
     */
    public static List<String> listBalanceSignatures() {
        List<String> out = new ArrayList<>();
        Object econ = economy();
        if (econ == null) { out.add("No economy provider registered."); return out; }
        out.add("Provider: " + econ.getClass().getName());
        boolean any = false;
        for (Method m : econ.getClass().getMethods()) {
            if (!m.getName().equalsIgnoreCase("getBalance")) continue;
            any = true;
            StringBuilder sb = new StringBuilder("  getBalance(");
            Class<?>[] pts = m.getParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(pts[i].getSimpleName());
            }
            sb.append(") -> ").append(m.getReturnType().getSimpleName());
            out.add(sb.toString());
        }
        if (!any) out.add("  (no getBalance method found)");
        return out;
    }

    public static String topPlayer() {
        Map<UUID, Double> top = getTop(1);
        if (top == null || top.isEmpty()) return "N/A";
        UUID id = top.keySet().iterator().next();
        OfflinePlayer off = Bukkit.getOfflinePlayer(id);
        return off.getName() == null ? "?" : off.getName();
    }

    public static String topBalance() {
        Map<UUID, Double> top = getTop(1);
        if (top == null || top.isEmpty()) return "0";
        return formatAmount(top.values().iterator().next());
    }

    public static String currencySymbol() {
        Object econ = economy();
        if (econ == null) return "";
        try { return (String) econ.getClass().getMethod("currencyNamePlural").invoke(econ); }
        catch (Throwable t) { return ""; }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Double> getTop(int n) {
        Object econ = economy();
        if (econ == null) return null;
        try {
            // Try Vault 1.7+ getTop(int) → Map<String, Double>
            Object result = econ.getClass().getMethod("getTop", int.class).invoke(econ, n);
            if (result instanceof Map<?, ?> raw) {
                Map<UUID, Double> out = new LinkedHashMap<>();
                for (var e : raw.entrySet()) {
                    Object k = e.getKey();
                    if (k instanceof UUID id) out.put(id, ((Number) e.getValue()).doubleValue());
                    else if (k instanceof String s) {
                        @SuppressWarnings("deprecation")
                        OfflinePlayer op = Bukkit.getOfflinePlayer(s);
                        if (op.getUniqueId() != null) out.put(op.getUniqueId(), ((Number) e.getValue()).doubleValue());
                    }
                }
                return out;
            }
        } catch (Throwable t) { /* fall through */ }
        // Fallback: iterate known players using the cached getBalance signature
        Map<UUID, Double> out = new LinkedHashMap<>();
        try {
            Method getBalance = BALANCE_METHOD_CACHE.get(econ.getClass());
            if (getBalance == null) {
                // Re-resolve
                for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                    if (op.getUniqueId() == null) continue;
                    double bal = readBalance(econ, op.isOnline() ? op.getPlayer() : null);
                    if (bal < 0) continue;
                    out.put(op.getUniqueId(), bal);
                }
            } else {
                for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                    if (op.getUniqueId() == null) continue;
                    Object r = getBalance.invoke(econ, op);
                    if (r instanceof Number num) out.put(op.getUniqueId(), num.doubleValue());
                }
            }
            // Sort by balance desc, keep top n
            var sorted = new ArrayList<>(out.entrySet());
            sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            Map<UUID, Double> top = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(n, sorted.size()); i++) top.put(sorted.get(i).getKey(), sorted.get(i).getValue());
            return top;
        } catch (Throwable t) { return null; }
    }

    private static String formatAmount(double amount) {
        if (amount == (long) amount) return String.format("%,d", (long) amount);
        return String.format("%,.2f", amount);
    }
}

