package com.hudboard.groups;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One user-defined placeholder group (Phase 2.1).
 *
 * <p>A group is a named list of placeholder keys that appears as an
 * additional source in the placeholder browser, alongside the built-in
 * categories (server / player / world / …) and the PAPI expansions.
 * Typical use: group {@code top1_kills_name}, {@code top1_kills_value},
 * {@code top2_kills_name}, … under a single {@code "TopPvP"} tab so
 * the admin doesn't have to remember six separate placeholder names.</p>
 *
 * <p>Names must match {@code [a-zA-Z0-9_-]+} to keep them safe to use
 * as a YML key + as a PlaceholderBrowser source label. Reserved names
 * ("server", "player", "world", "economy", "top", "target", "runtime",
 * "hudboard-api", "misc", or any registered PAPI identifier) are
 * rejected at creation time so we don't shadow built-ins.</p>
 */
public final class PlaceholderGroup {

    private final String name;
    private final List<String> keys;

    public PlaceholderGroup(String name, List<String> keys) {
        this.name = name;
        // Defensive copy + unmodifiable so callers can't mutate our state.
        this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
    }

    public String name() { return name; }

    /** Read-only view of the placeholder keys (without surrounding %). */
    public List<String> keys() { return keys; }

    @Override
    public String toString() { return "PlaceholderGroup[" + name + ":" + keys.size() + " keys]"; }
}
