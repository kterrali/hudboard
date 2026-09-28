# 🟧 HudBoard

> **Per-panel info displays for Paper 1.21.11+. Drop an image, map data points to any PlaceholderAPI placeholder, edit in-game.**

[![Java](https://img.shields.io/badge/Java-21%2B-orange)](#)
[![Paper](https://img.shields.io/badge/Paper-1.21.11%2B-blue)](#)
[![License](https://img.shields.io/badge/License-Apache--2.0-green)](LICENSE)
[![Tests](https://img.shields.io/badge/Tests-91%2F91-success)](#)
[![PAPI](https://img.shields.io/badge/PAPI-required-yellow)](#)

---

## What is HudBoard?

HudBoard lets you place **per-player info panels** anywhere in your Minecraft world. Each panel is a PNG/GIF/JPG image you supply, with **data points** that resolve to PlaceholderAPI placeholders — so `%player_name%`, `%vault_eco_balance%`, `%server_tps%` all render live for every viewer.

Think of it as a **map-with-text** plugin, designed for server lobbies, hubs, dating apps, mini-games — anywhere you want to display custom info.

---

## ✨ Features

### Panels
- **PNG / GIF / JPG support** — drop an image, drop a sidecar yml, done
- **Up to 10×10 tiles** (configurable) per panel — map rendering scales to fit
- **Auto-fit text** — long strings shrink automatically; cross-tile background tags
- **20Hz GIF rendering** with NMS direct map sender (smooth animation)
- **5 positional animations** : `bob` (vertical oscillation), `scroll` (ticker), `typewriter` (reveal char-by-char), `glitch` (TV-static teleport), `pulse` (breathing scale)

### Placeholders
- **Full PlaceholderAPI integration** — every `%placeholder%` PAPI supports works
- **Custom placeholder groups** — curate your own lists per PAPI expansion (`/hudboard group ...`)
- **Manual placeholder entry** for legacy-hook expansions (`/hudboard manual ...`)
- **In-game placeholder browser** — see every available placeholder with its live resolved value, copy with one click

### Editing
- **In-game editor GUI** — right-click any panel to open it
- **Paper Dialogs** for every input (1.21+ feature, no chat prompts)
- **Per-data-point** x/y/size/color/base-color/animation/text/bg/align/padding
- **Bulk base-color** — retheme every data point on a panel at once
- **Undo/Redo** with 32-step history
- **Templates** — `/hudboard template list|show` for player/tps/balance/time/alert/blank

### Other
- **Live preview** in chat (`/hudboard preview <profile>`)
- **Repair command** — `/hudboard panel repair <name>|all` re-spawns missing frames
- **Backups** auto-snapshot every 30 min (configurable), 10 retained
- **Public API** (`com.hudboard.api.HudBoardAPI`) — drive HudBoard from your own plugin
- **Multi-language** — `lang.yml` is FR by default, all dialogs and messages localized

---

## 📦 Installation

1. **Install PlaceholderAPI** (required, hard dependency) :
   ```
   /papi ecloud download all
   ```
2. **Drop `HudBoard-X.X.X.jar`** into your `plugins/` folder
3. **Restart** your server
4. (Optional) Edit `plugins/HudBoard/config.yml`
5. (Optional) Edit `plugins/HudBoard/lang.yml` to translate messages

---

## 🚀 Quick start

```mcfunction
# 1. Create a 3x3 PNG panel named "welcome" — drops into plugins/HudBoard/panels/png/welcome.png
#    + a sidecar yml: plugins/HudBoard/panels/png/welcome.yml

# 2. Look at a wall and type:
/hudboard panel place welcome

# 3. Right-click the panel to open the in-game editor
#    Click "Browse placeholders" to see available PAPI placeholders
#    Click any data point to edit it (text opens a Paper Dialog)

# 4. To place the same profile in 4 spots:
/hudboard panel place welcome     # creates "welcome-1"
/hudboard panel place welcome     # creates "welcome-2"
/hudboard panel place welcome     # creates "welcome-3"
/hudboard panel place welcome     # creates "welcome-4"

# 5. To move one:
/hudboard move welcome-3          # look at the new spot

# 6. To remove all:
/hudboard nuke
```

---

## 📚 Commands

### Panel management
| Command | Description |
|---------|-------------|
| `/hudboard panel create <name> <png\|gif\|jpg> <w>x<h>` | Create a blank profile |
| `/hudboard panel place <profile>` | Place at your look target (auto wall/floor/ceiling/in-air) |
| `/hudboard panel edit <name>` | Open the editor GUI |
| `/hudboard panel info <name>` | Show panel state |
| `/hudboard panel list` | List placed panels |
| `/hudboard panel undo <name>` | Rollback last edit (32-step history) |
| `/hudboard panel redo <name>` | Replay an undone edit |
| `/hudboard panel repair <name\|all>` | Re-spawn missing item-frames |

### Quick actions on placed panels (top-level)
| Command | Description |
|---------|-------------|
| `/hudboard list` | List profiles |
| `/hudboard panels` | List placed panels |
| `/hudboard move <name>` | Move a placed panel to your look target |
| `/hudboard tp <name>` | Teleport to a placed panel |
| `/hudboard rename <old> <new>` | Rename a placed panel |
| `/hudboard remove <name\|all>` | Remove a placed panel (or all) |
| `/hudboard nuke [radius]` | Remove every panel within radius |

### Custom placeholders
| Command | Description |
|---------|-------------|
| `/hudboard group <sub>` | list/create/add/remove/delete/export/import |
| `/hudboard manual <sub>` | list/add/remove/clear (per-PAPI source) |
| `/hudboard template <sub>` | list/show (player, tps, balance, time, alert, blank) |

### Plugin meta
| Command | Description |
|---------|-------------|
| `/hudboard help` | Full command list |
| `/hudboard info` | Version + status |
| `/hudboard stats` | Global placeholders + render perf |
| `/hudboard preview <profile>` | Render data points in chat (live preview) |
| `/hudboard reload` | Hot-reload profiles + config |
| `/hudboard debug papi` | PAPI diagnostic |
| `/hudboard debug nms` | Map sender status |
| `/hudboard debug log <level>` | Set runtime log level (`off|warning|info|fine|all`) |

---

## ⚙️ Configuration

`plugins/HudBoard/config.yml` :

```yaml
# Maximum tiles per side (W or H)
max-tiles-per-side: 10

# Worlds where panels are disabled
disabled-worlds: []

# Cooldown between placements (ms)
place-cooldown-ms: 3000

# Auto-snap blocks for `/hudboard panel place`
auto-snap-max-blocks: 4

# Backup interval (minutes), 0 = disabled
backup-interval-minutes: 30

# View distance — players further than this won't see the panel
panel-view-distance: 48

# Sounds (set empty to disable)
sound-menu-click: UI_BUTTON_CLICK
sound-menu-deny: ENTITY_VILLAGER_NO
sound-menu-open: ""
sound-on-remove: ENTITY_ITEM_PICKUP

# v2.6.4: GitHub URL shown in the startup banner (empty = hidden)
github-url: ""
```

---

## 🎨 Data point format

```yaml
# plugins/HudBoard/panels/png/welcome.yml

name: "&6Welcome to the server"
description: "Lobby info panel"
tiles-w: 3
tiles-h: 3
permission: ""
user-placeholders:
  rank: "&7VIP"

data-points:
  title:
    tile-x: 0
    tile-y: 0
    x: 64
    y: 16
    size: 22
    text: "<gold><bold>Welcome</bold></gold>"

  player:
    tile-x: 1
    tile-y: 1
    x: 8
    y: 32
    size: 14
    text: "<gray>Player: </gray><white>%player_name%</white>"

  balance:
    tile-x: 1
    tile-y: 1
    x: 8
    y: 60
    size: 12
    text: "<gray>Balance:</gray> <green>$%vault_eco_balance%</green>"
    base-color: "<green>"
```

### Animation example
```yaml
  ticker:
    tile-x: 0
    tile-y: 2
    x: 0
    y: 8
    size: 12
    text: "Latest news: <gold>...</gold>"
    animation: "scroll"
    anim-ms: 8000
    anim-color: "<gold>"
```

### Available animations
| Name | Effect |
|------|--------|
| `none` | Static (default) |
| `bob` | Vertical bob (±3px sin) |
| `scroll` | Horizontal ticker |
| `typewriter` | Reveal char-by-char |
| `glitch` | TV-static teleport (±4px / 80ms) |
| `pulse` | Breathing scale (1.0 ↔ 1.12) |

---

## 🌍 Translations

HudBoard ships with **French** as the default `lang.yml`. To translate :

1. Open `plugins/HudBoard/lang.yml`
2. Edit the keys you want (or copy `lang_en.yml` from the GitHub repo)
3. `/hudboard reload`

Supported strings cover all admin chat messages, all GUI dialogs, all Paper Dialog hints.

---

## 🛠 Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `hudboard.use` | `true` | View panels |
| `hudboard.place` | `op` | Place / remove panels |
| `hudboard.edit` | `op` | Edit data points in-game |
| `hudboard.reload` | `op` | Hot-reload profiles + config |
| `hudboard.admin` | `op` | Full access (children: use/place/edit/reload) |

---

## 🧪 For developers

`com.hudboard.api.HudBoardAPI` is the public entry point:

```java
import com.hudboard.api.HudBoardAPI;

HudBoardAPI api = HudBoardAPI.get();

// Place a panel programmatically
String instanceName = api.placePanel(player, "welcome");

// Resolve a placeholder without showing the panel
String balance = api.resolvePlaceholder(player, "%vault_eco_balance%");

// Re-render a panel
api.refresh(instanceName);
```

Build with **Java 21+**, target **Paper 1.21.11+**. The plugin uses the Adventure API for chat components, Paper Dialogs (1.21+) for input flows, and direct NMS access for fast map sending.

---

## 🐛 Troubleshooting

**Panel doesn't show** : check `disabled-worlds` in config.yml, and that the chunk is loaded (`/hudboard panel repair <name>` force-loads it).

**Placeholders show as `%…%`** : PAPI is not installed or the expansion isn't registered. Run `/hudboard debug papi` for diagnostics.

**GIF flickers** : the NMS direct map sender isn't available on your server. Run `/hudboard debug nms` — fallback is the slower vanilla map render.

**Console spammed** : `/hudboard debug log warning` to suppress INFO logs.

---

## 📜 License

Apache License 2.0 — see [LICENSE](LICENSE).

---

## 🙏 Credits

- **Mavis** — primary development
- **PlaceholderAPI** team — the placeholder engine this plugin consumes
- **Paper team** — Paper Dialogs API + NMS access
- **madgag/animated-gif-lib** — Java GIF decoder (Apache-2.0)
