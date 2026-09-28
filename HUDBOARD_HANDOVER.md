# HudBoard — Fiche de passation v2.5.0

## ⚠️ Notes de version

- **PAPI est désormais une dépendance dure** (`depend:` dans plugin.yml). Le plugin refuse de charger si PAPI est absent.
- **Pas de collision barriers** (depuis v1.3.7). Plus aucun Shulker. Tous les panneaux (murs, sol, plafond) sont traversables. Les Shulkers résiduels d'anciennes versions sont nettoyés au startup.
- **Console silencieuse par défaut** (depuis v1.3.15). Les anciens logs `Discovery: modern=9` ont été désactivés — disponibles via `/hudboard debug papi`.
- **Groupes de placeholders custom** (depuis v1.4.0) — l'admin peut créer des catégories `group:foo` qui apparaissent dans le browser comme sources, mais ne peuvent PAS shadower un id PAPI ou un built-in (réservation dynamique).
- **Bulk-edit base-color** (depuis v1.4.0) — un seul dialogue applique une couleur à TOUS les data points d'un panel.
- **SFX menus** (depuis v1.4.1) — clic + open + deny joués sur config (`sound-menu-click/open/deny`).
- **Color preview dans le dialogue** (depuis v1.4.1) — l'admin voit un sample rendu avec la couleur courante avant Save.
- **YAML import/export des groupes** (depuis v1.4.1) — `/hudboard group export|import`.
- **Bulk base-color + add gradient button** (depuis v2.3.0).
- **Pre-bake cache 20Hz** (depuis v2.4.0) — 20Hz tick devient O(1) hashmap lookup.
- **Auto-fit cross-tile** (depuis v2.4.1) — texte déborde sur tiles multiples au lieu de shrink.
- **On-demand panel repair** (depuis v2.5.0) — `/hudboard panel repair <name>|all` avec chunk force-load.

## Projet

Plugin Minecraft Paper 1.21.11-132 / Java 21 / Adventure / MiniMessage. Pose des
panneaux info sur item-frames ou item-displays : image + data points texte animés.
HudBoard résout n'importe quel placeholder PAPI d'autres plugins (Armor,
Vault, Jobs, PlayerPoints, PlaceholderAPI ships, etc.).

**Workspace** : `/workspace/HudBoard/`
**JAR livré** : `/workspace/HudBoard-2.5.0.jar` (buildé, prêt à déployer)
**Stack clé** : Paper 1.21.11, NMS direct pour les map packets, PAPI en depend (obligatoire), Vault en soft-dep, MiniMessage, Paper Dialog API (1.21.7+).

## Versioning

Numérotation `1.4.X` depuis la v1.4.0.

- `1.3.0` à `1.3.4` : refonte PAPI (DataManager.discoverPapiIdentifiers, browser 2-level, manual entry chat)
- `1.3.5` : manual placeholder entry via chat conversation
- `1.3.6` : Shulker +1Y fix défensif (retiré dans la version suivante)
- `1.3.7` : Shulker supprimé complètement
- `1.3.8` : fix crash `Disallowed chat character '§'` dans messages chat
- `1.3.9` : lookup installed PAPI via reflection + Paper Dialog
- `1.3.10` : `PlaceholderExpansion.getName()` reflection (vrai nom exact)
- `1.3.11` : multi-casing fallback + auto-refresh sur manual insert
- `1.3.12` : `CloudExpansionManager.findCloudExpansionByName()` reflection
- `1.3.13` : unwrap Optional (bug réel : `findCloudExpansionByName` retourne `Optional<CloudExpansion>`)
- `1.3.14` : pagination 36 slots/page
- `1.3.15` : silent console (Discovery logs)
- `1.3.16` : persistance des manuels + pagination tests + HANDOFF
- `1.4.0` : Custom placeholder groups (admin CRUD, persists in `groups.yml`) + Bulk-edit base-color
- `1.4.1` : SFX menus + color preview in dialogs + YAML group import/export + mock PAPI integration tests + fix collision PAPI/group
- `1.4.2` : Per-player live preview (text dialog shows your own resolved text + base-color) + throttled "chunk not loaded" log
- `2.0.0` : 20Hz tick distance filter (computation only for visible players) + world-skip on tickGifFrames
- `2.0.1` : Persistent manual placeholder keys (`plugins/HudBoard/manual-keys.yml`) — survives restarts
- `2.1.0` : 2× render buffer (256×256 → bilinear 128×128) + drop shadow (+1,+1) + Dialog Bold font
- `2.1.1` : Real TextLayout.getAdvance() replaces buggy approxCharWidth — fixes kerning gaps (Kt → Kterrali)
- `2.3.0` : Batch A+B — bg opaque, auto-fit text, center/right align, padding, palette cache, /preview, gradient button, templates, undo/redo
- `2.4.0` : Batch C+D — backup auto (30min), per-panel disabled-worlds, multi-tile chunk check, auto-snap placement (vertical), pre-bake pixels (20Hz → 0Hz CPU)
- `2.4.1` : Auto-fit fix — text overflows naturally on multi-tile panels (maxWidth = tilesW × 128). New `dp.alignMode` field ('tile'|'panel') for panel-wide alignment
- `2.5.0` : `/hudboard panel repair [name|all]` with chunk force-load, auto-snap extended horizontally, pre-bake cache LRU cap (64 entries/panel max = ~1MB/panel). #20 gradient cross-tile verified not-a-bug (test added).

## Build & deploy

```bash
# Sandbox resets every ~30min → réinstaller JDK 21 + Maven + TrustStore
apt-get update && apt-get install -y maven
cd /tmp && curl -sL \
  "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz" \
  -o jdk21.tar.gz && tar -xzf jdk21.tar.gz -C /opt/
export JAVA_HOME=/opt/jdk-21.0.5+11 && export PATH=$JAVA_HOME/bin:$PATH

# Truststore : cacerts JDK + ISRG + chain proxy (sandbox intercepts TLS)
cp $JAVA_HOME/lib/security/cacerts /tmp/cacerts.full
for f in isrgrootx1.pem isrg-root-x2.pem; do
  curl -sLo /tmp/$f "https://letsencrypt.org/certs/$f"
done
for f in /tmp/isrgrootx1.pem /tmp/isrg-root-x2.pem; do
  keytool -noprompt -import -trustcacerts -alias "$(basename $f .pem)" \
    -file $f -keystore /tmp/cacerts.full -storepass changeit
done
keytool -noprompt -import -trustcacerts -alias sandbox-gw \
  -file /etc/ssl/certs/agent-identity/sandbox-gateway-ca.crt \
  -keystore /tmp/cacerts.full -storepass changeit
echo "QUIT" | openssl s_client -servername repo.maven.apache.org \
  -connect repo.maven.apache.org:443 -showcerts 2>/dev/null > /tmp/srv.pem
csplit -z -f /tmp/cert- -b "%02d.pem" /tmp/srv.pem '/-----BEGIN CERTIFICATE-----/' '{*}'
for f in /tmp/cert-01.pem /tmp/cert-02.pem; do
  keytool -noprompt -import -trustcacerts -alias "proxy-$(basename $f)" \
    -file $f -keystore /tmp/cacerts.full -storepass changeit
done

# Mirror Maven Central via Aliyun (repo.maven.apache.org renvoie 503)
mkdir -p ~/.m2
cat > ~/.m2/settings.xml <<'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <name>aliyun maven</name>
      <url>https://maven.aliyun.com/repository/public/</url>
      <mirrorOf>*,!papermc</mirrorOf>
    </mirror>
  </mirrors>
</settings>
EOF

# Build + test
cd /workspace/HudBoard
mvn -B test \
  -Djavax.net.ssl.trustStore=/tmp/cacerts.full \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -Dmaven.wagon.http.ssl.trustStore=/tmp/cacerts.full \
  -Dmaven.wagon.http.ssl.trustStorePassword=changeit
mvn -B package -DskipTests \
  -Djavax.net.ssl.trustStore=/tmp/cacerts.full \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -Dmaven.wagon.http.ssl.trustStore=/tmp/cacerts.full \
  -Dmaven.wagon.http.ssl.trustStorePassword=changeit
cp target/HudBoard-2.5.0.jar /workspace/HudBoard-2.5.0.jar
```

## Architecture

```
com.hudboard/
├── HudBoardPlugin.java        # Bootstrap, refreshGroupReservations(), getManualKeys(), backup task (v2.4.0)
├── api/HudBoardAPI.java       # Public API for external plugins
├── command/                   # /hudboard and /hudb commands
│   ├── HudBoardCommand.java   # + handleGroup, handleManual, handleTemplate, handlePreview, handlePanelHistory, handlePanelRepair
│   └── HudBoardTabCompleter.java
├── config/ConfigManager.java  # + sound-menu-click/open/deny + panelViewDistance + autoSnapMaxBlocks + backupIntervalMinutes
├── data/
│   ├── DataManager.java       # PAPI resolution + cache + setPapiClassForTest()
│   ├── ManualKeysStorage.java # v2.0.1: CRUD YAML pour les manuels persistants
│   ├── PapiECloud.java        # CloudExpansion API reflection
│   └── providers/             # Server, Player, Economy providers
├── groups/                    # v1.4.0: custom placeholder groups
│   ├── PlaceholderGroup.java
│   └── PlaceholderGroupManager.java   # CRUD + YAML + isReservedIncludingPapi() + import/export
├── lang/                      # Lang (i18n)
├── listener/                  # Event listeners
├── menu/                      # All GUI classes (chest + Paper Dialogs)
│   ├── DataPointEditorMenu.java
│   ├── DialogInputBridge.java # + add gradient button (v2.3.0) + GRADIENT_HELPER session + buildTextPreview
│   ├── MenuSfx.java           # v1.4.1: helper SFX statique
│   ├── PanelEditMenu.java     # + bulkBaseColorButton() slot 36 (v2.3.0)
│   ├── PlaceholderBrowser.java # + manualKeysCache seedé depuis ManualKeysStorage
│   └── RemoveDataPointMenu.java
├── util/                      # v2.4.0: PlacementSnap
│   └── PlacementSnap.java
└── panel/                     # Core panel rendering
    ├── GifAnimation.java
    ├── GifSequence.java
    ├── InfoPanel.java         # + DataPoint fields: bg, align, padding (v2.3.0), alignMode (v2.4.1)
    ├── InfoPanelInstance.java # + snapshotForUndo/undo/redo (v2.3.0), depth LRU cache
    ├── InfoPanelManager.java  # + per-panel disabled-worlds, multi-tile chunk, panel repair
    ├── InfoPanelRenderer.java # Map rendering, 2× buffer (v2.1.0), TextLayout (v2.1.1), bg/align/padding (v2.3.0), pre-bake cache with LRU cap (v2.4.0 + v2.5.0)
    ├── PanelTileEntity.java
    ├── PanelTileItemDisplay.java
    └── PanelTileItemFrame.java
```

## PAPI siphoning — pipeline complet

1. **`DataManager.discoverPapiIdentifiers()`** — combine modern (`PlaceholderAPI.getRegisteredIdentifiers()`) + legacy (`getPlaceholders().keySet()`) via reflection
2. **`PapiECloud.getInstalledExpansionNames()`** — reflective walk sur `LocalExpansionManager.getExpansions()` → `getName()` pour chaque `PlaceholderExpansion`. Cache 5min, refreshed sur slot 51.
3. **`PapiECloud.fetchPlaceholders(expansionId)`** — résout le nom exact via lookup case-insensitive, puis `CloudExpansionManager.findCloudExpansionByName(exactName)` → `.get().getPlaceholders()`. Case-insensitive côté PAPI, donc le raw id suffit en général.
4. **`PlaceholderBrowser`** — combine base (hardcoded OU local hook OU group custom) + eCloud-fetched templates + manual entries, paginé 36 par page.

### PAPI reflection — piège critique

**Toutes les calls reflection sur PAPI doivent utiliser `((Class<?>) papi).getMethod(...)`** PAS `papi.getClass().getMethod(...)`. C'est exactement le bug qui a fait perdre 1 journée entière à la v1.3.3 — le second ne trouve pas les méthodes de PAPI (il cherche sur java.lang.Class).

### v1.4.1: Mock PAPI pour tests d'intégration

`DataManager.setPapiClassForTest(Class<?>)` permet aux tests d'injecter une classe Java arbitraire avec une méthode statique `getRegisteredIdentifiers()` retournant une `Collection<String>`. Les tests `DataManagerPapiMockTest` et `PapiBrowserIntegrationTest` exercent le pipeline sans Paper. Les `FakePapi`/`FakePapi2` sont des classes réelles dans `src/test/java`.

## PlaceholderBrowser — état final

- **2 modes** : SOURCES (papers = built-in + groupes custom + PAPI) / PLACEHOLDERS (papers = placeholders de la source)
- **3 couches** mergées dans `placeholdersForCurrentSource()` : base (hardcoded OU local hook OU group custom) + eCloud-fetched templates + manual entries
- **Pagination** : 36 slots/page, slots 50/52 = prev/next, header slot 4 = "Page X/Y · N total"
- **Persistance manuelle** : `manualKeysBySource` Map persiste les manuels à travers Back + re-click
- **Source badge** : "▸ fetched from PAPI eCloud" ou "▸ added manually (persists across navigation)"
- **SOURCES count** reflète hardcoded + manual (sinon 0 alors qu'on a des manuels)
- **Reset currentPage** sur : changement de source, Fetch eCloud, manual insert, Back
- **v1.4.0** : sources `group:<name>` rendues entre built-in et PAPI (glass pane violet)

## Custom placeholder groups (v1.4.0)

Persistance dans `plugins/HudBoard/groups.yml` (`Map<String, List<String>>`, full snapshot à chaque mutation).

```bash
/hudboard group list
/hudboard group create <name>
/hudboard group add <name> <key...>
/hboard group remove <name> <key...>
/hudboard group delete <name>
/hudboard group export         # dump YAML au chat
/hudboard group import <yaml>  # merge (skip reserved, merge keys for existing names)
```

Validations :
- Name : `[a-zA-Z0-9_-]{1,32}` (lowercase normalisé)
- Rejette les noms qui matchent built-in (`server`, `player`, ...) ou un id PAPI installé (`isReservedIncludingPapi`)
- Rejette les noms préfixés `group:` (réservé au browser label)
- Keys : strip `%`, lowercase, dedup
- Empty list OK à la création (admin ajoute après)

## Bulk-edit base-color (v1.4.0)

Slot 36 dans `PanelEditMenu` (PAINTING, "Bulk base-color (all)") → dialogue `BULK_BASECOLOR` → applique une couleur à tous les data points du panel d'un coup. Auto-wrap comme le single-edit (`red` → `<red>`, `#FF8800` → `<#FF8800>`).

## SFX menus (v1.4.1)

`com.hudboard.menu.MenuSfx` — helper statique `click/deny/open`. Configurable dans `config.yml` :
```yaml
sound-menu-click: UI_BUTTON_CLICK
sound-menu-deny:  ENTITY_VILLAGER_NO   # fallback si non configuré
sound-menu-open:  ""                   # vide = off
```

Tous les `open()` des 4 menus jouent le `sound-menu-open`. Tous les `handleClick()` jouent le `sound-menu-click`. Une erreur de config (nom de son invalide pour cette version MC) est silently ignorée.

## Color preview in dialogs (v1.4.1)

Limitée par l'API Paper : `TextDialogInput.Builder` ne fournit PAS de keystroke callback. Solution : un paragraphe preview en body du dialogue, calculé à l'ouverture. Après Save, le dialogue est rouvert via `reopenEditor()` avec le nouveau tag → le preview reflète le nouveau rendu.

Constructeur `DialogInputBridge.buildColorPreview(tag, sample)` :
- Tag vide → sample en blanc + "(none)"
- Tag `<red>` → sample rendu en rouge via MiniMessage
- Tag invalide → "(invalid tag — will not be applied)" en rouge (catch Throwable)

Appliqué à `openBaseColorChoice` (sample = "Hello, <key>!") et `openBulkBaseColorChoice` (sample = "All data points will look like this.").

## Animations (5)

Toutes positionnelles (modifient le rendu, pas la couleur) :
1. `bob` — sin vertical ±3px
2. `scroll` — ticker horizontal (texte défile)
3. `typewriter` — apparition char par char
4. `glitch` — téléport aléatoire ±4px toutes les 80ms (effet TV-static)
5. `pulse` — breathing scale 1.0↔1.12

Edit: slot `animation` → dialog Paper avec 3 inputs (type, speed, color).

## Commandes principales

| Commande | Description |
|---|---|
| `/hudb place <profile>` | Place un panel sur le bloc visé |
| `/hudb place <profile> <name>` | Place avec un nom custom |
| `/hudb here` | Place flat au sol/plafond sous le joueur |
| `/hudb move <name>` | Déplace (renomme, garde le profil) |
| `/hudb setpos <name>` | Repositionne SANS renommer (utilisé pour réparer) |
| `/hudb rename <name> <new>` | Renomme un panel placé |
| `/hudb remove <name>` | Supprime un panel placé |
| `/hudb list` | Liste tous les panels placés |
| `/hudb info <name>` | Infos d'un panel |
| `/hudb reload` | Recharge les profiles depuis disk + refresh group reservations |
| `/hudb repair` | Force-repair : re-scan le monde, repositionne les panels mal placés |
| `/hudb purge-orphans [radius]` | Purge les item-frames orphelins |
| `/hudb nuke [radius]` | Nuke tous les panels dans un rayon |
| `/hudb clone <name>` | Clone un panel à la position de visée |
| `/hudb copy <src> <dst>` | Copie un profile (panel) |
| `/hudb panel create <name> <fmt> <wxh>` | Crée un profile vide |
| `/hudb panel place <name>` | Place un profile existant |
| `/hudb tp <name>` | TP au panel |
| `/hudb placeholders` | Liste tous les placeholders |
| `/hudboard group list|create|add|remove|delete|export|import` | Custom placeholder groups |
| `/hudboard manual list|add|remove|clear` | v2.0.1: manual placeholder keys (persistent) |
| `/hudboard template list|show` | v2.3.0: data-point templates (player/tps/balance/time/alert/blank) |
| `/hudboard preview <profile>` | v2.3.0: render preview in chat (per-player values) |
| `/hudboard panel undo|redo <name>` | v2.3.0: undo/redo the last data-point edit |
| `/hudboard panel repair <name>|all` | v2.5.0: on-demand repair with chunk force-load |
| `/hudboard debug papi` | Diagnostic PAPI verbose (Discovery logs, etc.) |
| `/hudb eco` / `stats` / `help` | Divers |

## GUI hierarchy

1. **Right-click sur item-frame/item-display** → ouvre `PanelEditMenu` (54 slots)
2. Clic sur un data point → ouvre `DataPointEditorMenu` (45 slots)
3. Clic sur "? placeholders" → ouvre `PlaceholderBrowser` (54 slots, 2-level)
4. Clic sur un bouton property → ouvre une **Dialog** (Paper Dialog API)
5. Save dans une dialog → re-render immédiat via NMS direct

## Bugs FIXÉS

1. ✅ `Discovery: modern=9` spam console → `discoverPapiIdentifiers()` default false (v1.3.15)
2. ✅ `Optional.getPlaceholders()` NoSuchMethod → unwrap de l'Optional (v1.3.13)
3. ✅ `Bukkit.dispatchCommand` ne déclenche pas les handlers PAPI → API reflection directe (v1.3.12)
4. ✅ Reflection sur `LocalExpansionManager.getName()` silencieuse quand API PAPI différente → logging warnings (v1.3.11)
5. ✅ PAPI case-sensitive (`armor` vs `Armor`) → lookup case-insensitive dans CloudExpansion (v1.3.10)
6. ✅ Crash `Disallowed chat character '§'` → styling Adventure pur sans § dans raw text (v1.3.8)
7. ✅ Placeholders manuels perdus sur Back + re-click → `manualKeysBySource` map (v1.3.16)
8. ✅ 76 templates pas de place pour 36 slots → pagination slots 50/52 (v1.3.14)
9. ✅ Shulker +1Y bug → fix défensif sur `expectedAirCenter` puis suppression complète du système (v1.3.6/7)
10. ✅ `BlockFace.UNKNOWN` crash → validation via `BlockFace.valueOf` avec default NORTH
11. ✅ Throttled time-based placeholders (TTL=1s)
12. ✅ Force-load chunks dans `/hudb repair`
13. ✅ Refacto `placeAt`/`placeFlat` → `placeOn()` + `TriBlockResolver`
14. ✅ Reflection bug sur PAPI : `((Class<?>) papi).getMethod(...)` au lieu de `papi.getClass().getMethod(...)`
15. ✅ **Collision `group:armor` qui shadow le vrai PAPI `Armor`** (v1.4.1) — `create()` appelait `isReserved()` au lieu de `isReservedIncludingPapi()`. Trouvé par `PapiBrowserIntegrationTest.cannotCreateGroupNamedAfterInstalledPapi`.
16. ✅ **20Hz tick wasting CPU sur panels distants** (v2.0.0) — filtre distance² (config `panel.view-distance`) + skip world vide dans `tickGifFrames()`. Pas de changement de comportement client.
17. ✅ **Manual placeholder keys perdus au restart** (v2.0.1) — nouvelle classe `ManualKeysStorage` persistée en `plugins/HudBoard/manual-keys.yml`.
18. ✅ **Texte pixelisé sur maps Minecraft** (v2.1.0) — render 2× buffer avec downsample bilinear + drop shadow + Dialog Bold font.
19. ✅ **Gaps "Kt errali" entre lettres** (v2.1.1) — approxCharWidth remplacé par TextLayout.getAdvance() réel, cache hash-collision bug aussi corrigé.
20. ✅ **Auto-fit regression panels 2×3** (v2.4.1) — maxWidth passé de `128-padding` à `tilesW*128-padding`, ajout du champ `dp.alignMode` pour align panel-wide.
21. ✅ **Pre-bake cache RAM unbounded** (v2.5.0) — LinkedHashMap access-order avec cap à 64 entrées/panel (~1MB max). GIF cache cap global à 64 entrées.

## Tests

- `InfoPanelAnimationsTest` (7 tests) : bob, scroll, typewriter, glitch, pulse
- `InfoPanelInstanceFlatPlacementTest` (2 tests) : 2×2 floor grid tight
- `InfoPanelRendererSliceTest` (17 tests) : slice logic cross-tile
- `PapiECloudTest` (8 tests) : parseCaptured, case-insensitive lookup, Armor example
- `PlaceholderBrowserPaginationTest` (9 tests) : pageSlice math
- `PlaceholderGroupManagerTest` (22 tests) : normalisation, dédup, reserved, import/export round-trip
- `DataManagerPapiMockTest` (3 tests) : mock PAPI class reflection
- `PapiBrowserIntegrationTest` (5 tests) : end-to-end DataManager → GroupManager
- `ManualKeysStorageTest` (13 tests) : normalisation, dédup, auto-clean empty source, unmodifiable snapshot
- `TextLayoutWidthTest` (3 tests) : kerning pairs (Kt, fa, ph) — regression guard for approxCharWidth bug
- `GradientCrossTileTest` (2 tests) : verifies MiniMessage gradient parses once across full text (debunks #20)
- **Total : 91/91 passent**

## Fichiers clés pour comprendre le code

- `PlaceholderBrowser.java` (~720 lignes) — pagination, sources, manual persistence cache seeded from ManualKeysStorage
- `InfoPanelRenderer.java` (~1300 lignes) — render + 2× buffer + TextLayout + bg/align/padding + pre-bake cache LRU
- `InfoPanelManager.java` (~1800 lignes) — tickGifFrames world-skip + multi-tile chunk + per-panel disabled-worlds + on-demand repair
- `InfoPanelInstance.java` (~600 lignes) — placement, reattach, snapshotForUndo/undo/redo
- `InfoPanel.java` (~250 lignes) — profile + DataPoint (bg, align, padding, alignMode)
- `DialogInputBridge.java` (~900 lignes) — Paper Dialog wrapper + GRADIENT_HELPER + color preview + buildTextPreview
- `DataManager.java` — résolution PAPI + discoverPapiIdentifiers + setPapiClassForTest()
- `PapiECloud.java` — CloudExpansion API reflection
- `PlaceholderGroupManager.java` (~350 lignes) — CRUD groupes + YAML + reservation logic + import/export
- `ManualKeysStorage.java` (~150 lignes) — CRUD YAML pour les manuels persistants
- `MenuSfx.java` — helper SFX statique
- `PlacementSnap.java` (~100 lignes) — auto-snap placement (v2.4.0 + v2.5.0)

## Notes importantes

- **Toujours tester avec un panel 1x1 d'abord** quand on touche au renderer
- **Le sandbox reset** les JDK/Maven/TrustStore → prévoir le script de réinstall
- **Maven Central + Paper repo 503 souvent** → mirror aliyun dans `~/.m2/settings.xml`
- **Le user est français** → réponses en français
- **Les panels sont 1-10x1-10 tiles** (limite dans `createBlankProfile`)
- **Le user préfère les gradients et animations sur les data points**
- **Couleurs préférées** : `<red>`, `<#FF8800>`, `<gradient:red:blue>`, `<gradient:#CB0DFC:#0EEB7D>`
- **PAPI expansions du user** : armor (legacy hook), player (legacy hook), playerlist, resourcepack, server, servertime, statistic, translatefont, vault
- **Convention eCloud** : PAPI renvoie du TitleCase ("Armor"), les hooks locaux utilisent lowercase ("armor"). CloudExpansion.findCloudExpansionByName est case-INsensitive côté PAPI, donc pas besoin de tester 3 casses.
- **Per-player visibility (v2.0) : REFUSÉ par user** — les panels sont pour tous, pas de filtrage par joueur/world/permission prévu.
- **Editor web (v2.0+) : DEFERRED** — projet séparé, pas dans HudBoard.

## Bugs connus / pas résolus

*Aucun bug connu actif. Le projet est stable en v2.5.0.*

Les edge cases restants (auto-snap horizontal incomplet pour murs, /repair nécessite world loadé) sont des cas limites documentés dans la commande elle-même.

## Prochaines étapes (si on continue)

1. ✅ **HANDOFF v2.5.0** — ce doc, état stable final
2. **Test live** (user) — l'user lance le JAR sur son serveur et remonte les bugs
3. **Web editor pour édition à distance** (projet séparé — `HudBoard-Web/`)

Optionnel, jamais engagé :
- Search/filter dans la liste des placeholders
- `/hudboard validate` (scan profiles, warn sur placeholders inconnus)
- French lang strings dans `lang.yml`
- Version command avec changelog

## Contact / contexte user

- User : Virelia (serveur Minecraft, "Couronnes" currency, plugins VireliaCore/Economy/Villages)
- Style : très direct, veut des fixes clairs, teste manuellement
- Toujours décrire ce qui a été fait dans le message de livraison
