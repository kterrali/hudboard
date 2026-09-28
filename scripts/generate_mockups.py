#!/usr/bin/env python3
"""
HudBoard mockup generator.

Produces 4 PNG mockups for use in:
  - README.md (banner)
  - Modrinth/Hangar/Polymart resource pages
  - GitHub repo social card

Outputs go to ./mockups/*.png
"""

from PIL import Image, ImageDraw, ImageFont, ImageFilter
import os
import sys

# Try to find a good monospaced font
FONT_PATHS = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
    "/System/Library/Fonts/Helvetica.ttc",
]
MONO_FONT_PATHS = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
    "/usr/share/fonts/TTF/DejaVuSansMono-Bold.ttf",
]

def load_font(paths, size):
    for p in paths:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

def load_mono(paths, size):
    for p in paths:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

OUT = os.path.join(os.path.dirname(__file__), "..", "mockups")
os.makedirs(OUT, exist_ok=True)

# ─────────────────────────────────────────────────────────────
# Mockup 1: README banner (1200x400) — gradient + plugin name
# ─────────────────────────────────────────────────────────────
def banner():
    W, H = 1200, 400
    img = Image.new("RGB", (W, H), (24, 24, 28))
    px = img.load()
    # Horizontal amber→orange gradient on background
    for x in range(W):
        t = x / W
        r = int(255 * (1 - t) + 255 * t)
        g = int(215 * (1 - t) + 107 * t)
        b = int(0   * (1 - t) + 53  * t)
        for y in range(H):
            # dim toward edges
            fy = 1.0 - abs(y - H/2) / (H/2)
            px[x, y] = (int(r*0.20*fy)+12, int(g*0.20*fy)+12, int(b*0.20*fy)+16)
    d = ImageDraw.Draw(img)
    title_font = load_font(FONT_PATHS, 110)
    sub_font   = load_font(FONT_PATHS, 32)
    # Plugin name in white + subtle gold drop-shadow
    name = "HudBoard"
    tw = d.textlength(name, font=title_font)
    d.text(((W-tw)/2 + 4, 90 + 4), name, font=title_font, fill=(0, 0, 0))
    d.text(((W-tw)/2, 90), name, font=title_font, fill=(255, 215, 0))
    # Tagline
    tagline = "Per-panel info displays for Paper 1.21.11+"
    tw2 = d.textlength(tagline, font=sub_font)
    d.text(((W-tw2)/2, 230), tagline, font=sub_font, fill=(200, 200, 210))
    # Box frame
    for i in range(3):
        d.rectangle([10+i, 10+i, W-10-i, H-10-i], outline=(120, 100, 50))
    img.save(os.path.join(OUT, "01-banner.png"))

# ─────────────────────────────────────────────────────────────
# Mockup 2: Sample "info-hub" panel (384x384) — 3x3 tiles
#            showing placeholders rendered on a map background
# ─────────────────────────────────────────────────────────────
def sample_panel():
    TILE = 128
    W, H = TILE*3, TILE*3
    img = Image.new("RGB", (W, H), (40, 40, 50))
    d = ImageDraw.Draw(img)
    # Simulate a map-style panel with a "wooden frame" look
    # Outer frame
    d.rectangle([0, 0, W-1, H-1], fill=(100, 70, 40), outline=(60, 40, 20))
    d.rectangle([8, 8, W-9, H-9], fill=(50, 50, 60), outline=(80, 80, 100))
    # Crosshair lines between tiles
    for i in range(1, 3):
        d.line([(i*TILE, 8), (i*TILE, H-9)], fill=(70, 70, 80), width=1)
        d.line([(8, i*TILE), (W-9, i*TILE)], fill=(70, 70, 80), width=1)
    # Sample text on each tile
    title_f = load_font(FONT_PATHS, 28)
    sub_f   = load_font(FONT_PATHS, 18)
    small_f = load_font(FONT_PATHS, 14)
    # Tile (0,0): title
    d.text((20, 30),  "<gold>Server", font=title_f, fill=(255, 200, 60))
    d.text((20, 65),  "  Info",   font=title_f, fill=(255, 200, 60))
    # Tile (1,1): data points
    d.text((TILE+20, TILE+15), "Player:", font=sub_f, fill=(180, 180, 190))
    d.text((TILE+90, TILE+15), "Kterrali", font=sub_f, fill=(255, 255, 255))
    d.text((TILE+20, TILE+45), "Balance:", font=sub_f, fill=(180, 180, 190))
    d.text((TILE+90, TILE+45), "$1,250", font=sub_f, fill=(100, 255, 100))
    d.text((TILE+20, TILE+75), "TPS:", font=sub_f, fill=(180, 180, 190))
    d.text((TILE+85, TILE+75), "20.0", font=sub_f, fill=(255, 255, 100))
    # Tile (2,0): scrolling news
    d.text((TILE*2+15, 30), "News:", font=sub_f, fill=(180, 180, 190))
    d.text((TILE*2+15, 55), "v2.7.0 released!", font=small_f, fill=(100, 200, 255))
    img.save(os.path.join(OUT, "02-panel-sample.png"))

# ─────────────────────────────────────────────────────────────
# Mockup 3: In-game Paper Dialog mockup (800x600) — multiline text editor
# ─────────────────────────────────────────────────────────────
def dialog_mockup():
    W, H = 900, 700
    img = Image.new("RGB", (W, H), (32, 32, 40))
    d = ImageDraw.Draw(img)
    # Background dim
    for y in range(H):
        c = int(20 - y/H * 8)
        d.line([(0, y), (W, y)], fill=(c, c, c+8))
    # Dialog box
    BOX_M = 60
    d.rounded_rectangle([BOX_M, BOX_M, W-BOX_M, H-BOX_M],
                        radius=12, fill=(48, 48, 56), outline=(110, 110, 130), width=3)
    # Title bar
    d.rounded_rectangle([BOX_M, BOX_M, W-BOX_M, BOX_M+80], radius=12, fill=(58, 58, 70))
    d.rectangle([BOX_M, BOX_M+60, W-BOX_M, BOX_M+80], fill=(58, 58, 70))
    title_f = load_font(FONT_PATHS, 28)
    sub_f   = load_font(FONT_PATHS, 18)
    small_f = load_font(FONT_PATHS, 14)
    d.text((BOX_M+30, BOX_M+25), "Texte de player", font=title_f, fill=(255, 200, 60))
    # Body
    y = BOX_M + 100
    body_lines = [
        ("Texte affiché sur le panneau.", (180, 180, 190)),
        ("• Placeholders : %server_tps% · %player_name% · %vault_eco_balance%", (140, 200, 255)),
        ("• Couleurs : <red>...</red> <bold>...</bold> <gradient:red:blue>...</gradient>", (140, 200, 255)),
        ("• Saut de ligne : <newline> ou <n> ou <br>", (140, 200, 255)),
        ("• Bouton Dégradé ci-dessous pour insérer un gradient.", (180, 180, 190)),
    ]
    for line, color in body_lines:
        d.text((BOX_M+30, y), line, font=small_f, fill=color)
        y += 28
    y += 14
    # Text input box
    d.rounded_rectangle([BOX_M+30, y, W-BOX_M-30, y+200], radius=6,
                        fill=(20, 20, 26), outline=(180, 180, 180), width=2)
    text_lines = [
        "Bienvenue Kterrali !",
        "Solde : $1,250",
        "Serveur : v2.7.0",
    ]
    for line in text_lines:
        d.text((BOX_M+50, y+12), line, font=sub_f, fill=(220, 220, 220))
        y += 32
    # Cursor
    d.line([(BOX_M+50+185, y-30), (BOX_M+50+185, y-12)], fill=(220, 220, 220), width=2)
    # Buttons
    by = H-BOX_M-60
    # Valider
    d.rounded_rectangle([BOX_M+30, by, BOX_M+220, by+40], radius=4, fill=(50, 130, 60), outline=(70, 200, 90))
    d.text((BOX_M+90, by+10), "Valider", font=sub_f, fill=(255, 255, 255))
    # Dégradé
    d.rounded_rectangle([BOX_M+240, by, BOX_M+430, by+40], radius=4, fill=(140, 80, 160), outline=(200, 140, 220))
    d.text((BOX_M+295, by+10), "Dégradé", font=sub_f, fill=(255, 255, 255))
    # Annuler
    d.rounded_rectangle([W-BOX_M-180, by, W-BOX_M-30, by+40], radius=4, fill=(140, 50, 50), outline=(220, 80, 80))
    d.text((W-BOX_M-145, by+10), "Annuler", font=sub_f, fill=(255, 255, 255))
    img.save(os.path.join(OUT, "03-dialog-editor.png"))

# ─────────────────────────────────────────────────────────────
# Mockup 4: Console banner output (1100x300)
# ─────────────────────────────────────────────────────────────
def console_banner():
    W, H = 1100, 320
    img = Image.new("RGB", (W, H), (12, 12, 16))
    d = ImageDraw.Draw(img)
    mono = load_mono(MONO_FONT_PATHS, 14)
    mono_b = load_mono(MONO_FONT_PATHS, 14)

    # Manually draw the box frame using PIL primitives so it always aligns
    box_x, box_y = 30, 35
    box_w, box_h = 1040, 245
    box_color = (140, 140, 160)

    # Top border
    d.line([(box_x, box_y), (box_x+box_w, box_y)], fill=box_color, width=2)
    d.line([(box_x, box_y+box_h), (box_x+box_w, box_y+box_h)], fill=box_color, width=2)
    d.line([(box_x, box_y), (box_x, box_y+box_h)], fill=box_color, width=2)
    d.line([(box_x+box_w, box_y), (box_x+box_w, box_y+box_h)], fill=box_color, width=2)
    # Corner accents (gold)
    accent = (255, 200, 60)
    for cx, cy in [(box_x, box_y), (box_x+box_w, box_y),
                   (box_x, box_y+box_h), (box_x+box_w, box_y+box_h)]:
        d.rectangle([cx-3, cy-3, cx+3, cy+3], fill=accent)

    lines = [
        (0, "[18:23:26 INFO]: [HudBoard] Enabling HudBoard v2.7.0", (200, 200, 210)),
        (2, "              HudBoard v2.7.0", (255, 215, 0)),
        (3, "     Spigot/Paper HUD panels with PAPI support", (200, 200, 210)),
        (4, "             Paper 1.21.11-R0.1-SNAPSHOT", (120, 120, 140)),
        (6, "  > PAPI: enabled", (90, 255, 90)),
        (7, "  > Profiles: 39 (0 placed)", (90, 255, 90)),
        (8, "  > Groups: 0", (90, 255, 90)),
        (9, "  > Manual keys: 1", (90, 255, 90)),
        (15, "[18:23:26 INFO]: [HudBoard] v2.7.0 enabled in 640ms.", (200, 200, 210)),
    ]
    line_h = 18
    start_y = 8
    for i, text, color in lines:
        y = start_y + i * line_h
        d.text((10, y), text, font=mono, fill=color)
    img.save(os.path.join(OUT, "04-console-banner.png"))

# ─────────────────────────────────────────────────────────────
# Mockup 5: Editor GUI chest mockup — 54-slot chest with FR buttons
# ─────────────────────────────────────────────────────────────
def editor_gui():
    TILE = 64
    W, H = TILE*9 + 40, TILE*6 + 40 + 60  # +60 for bottom buttons hint
    img = Image.new("RGB", (W, H), (60, 60, 70))
    d = ImageDraw.Draw(img)
    title_f = load_font(FONT_PATHS, 14)
    small_f = load_font(FONT_PATHS, 12)
    # Chest frame
    d.rectangle([10, 10, W-10, H-10], outline=(40, 40, 50), fill=(80, 80, 90))
    # Title
    d.text((20, 16), "Éditeur : welcome-1", font=title_f, fill=(100, 255, 100))
    # 54 slots (6x9)
    for row in range(6):
        for col in range(9):
            x = 30 + col * TILE
            y = 50 + row * TILE
            d.rectangle([x, y, x+TILE-4, y+TILE-4], outline=(50, 50, 60), fill=(110, 110, 120))
    # Fill some with mock items
    items = [
        (0, 0, "LIME_STAINED_GLASS_PANE", "welcome-1", (100, 255, 100)),
        (0, 4, "YELLOW_STAINED_GLASS_PANE", "Position", (255, 200, 60)),
        (1, 0, "MAP", "title", (200, 200, 60)),
        (1, 2, "MAP", "player", (200, 200, 60)),
        (1, 4, "MAP", "balance", (200, 200, 60)),
        (1, 6, "MAP", "tps", (200, 200, 60)),
        (1, 8, "LIME_STAINED_GLASS_PANE", "welcome-1", (100, 255, 100)),
        # Action row (row 4)
        (4, 0, "PAINTING", "Couleur globale", (200, 100, 200)),
        (4, 1, "LIME_DYE", "+ Ajouter un point", (100, 255, 100)),
        (4, 2, "RED_DYE", "- Supprimer un point", (255, 80, 80)),
        (4, 3, "BOOKSHELF", "Parcourir placeholders", (100, 200, 255)),
        (4, 4, "REDSTONE", "Rafraîchir", (100, 255, 100)),
        (4, 5, "ENDER_PEARL", "Fréquence : 5s", (100, 200, 255)),
        (4, 6, "NAME_TAG", "Renommer", (255, 255, 100)),
        (4, 7, "LIGHT_GRAY_STAINED_GLASS_PANE", "(undo/redo)", (200, 200, 200)),
        (4, 8, "BARRIER", "Retirer le panneau", (255, 100, 100)),
        # Close
        (5, 4, "BARRIER", "Fermer", (255, 100, 100)),
    ]
    mat_colors = {
        "LIME_STAINED_GLASS_PANE": (100, 200, 100),
        "YELLOW_STAINED_GLASS_PANE": (220, 200, 60),
        "MAP": (200, 180, 130),
        "PAINTING": (180, 100, 180),
        "LIME_DYE": (120, 220, 80),
        "RED_DYE": (220, 80, 80),
        "BOOKSHELF": (180, 130, 60),
        "REDSTONE": (220, 80, 60),
        "ENDER_PEARL": (90, 130, 200),
        "NAME_TAG": (200, 200, 90),
        "LIGHT_GRAY_STAINED_GLASS_PANE": (180, 180, 180),
        "BARRIER": (180, 80, 80),
    }
    for row, col, mat, label, color in items:
        x = 30 + col * TILE
        y = 50 + row * TILE
        base = mat_colors.get(mat, (140, 140, 140))
        d.rectangle([x+4, y+4, x+TILE-8, y+TILE-8], fill=base, outline=(20, 20, 20))
        # Word-wrap the label
        words = label.split()
        lines = []
        cur = ""
        for w in words:
            test = (cur + " " + w).strip()
            if d.textlength(test, font=small_f) < TILE - 8:
                cur = test
            else:
                lines.append(cur)
                cur = w
        if cur: lines.append(cur)
        ly = y + (TILE - 8 * len(lines)) // 2
        for ln in lines:
            lw = d.textlength(ln, font=small_f)
            d.text((x + (TILE-lw)//2, ly), ln, font=small_f, fill=(20, 20, 20))
            ly += 14
    img.save(os.path.join(OUT, "05-editor-gui.png"))

if __name__ == "__main__":
    # v2.7.0: keep only the banner mockup in the repo. The other 4
    # mockups (panel sample, dialog editor, console banner, editor GUI)
    # were useful during development but add ~110 KB to the repo
    # without being referenced anywhere. Regenerate locally with:
    #   python3 scripts/generate_mockups.py --all
    import sys
    if "--all" in sys.argv:
        banner()
        sample_panel()
        dialog_mockup()
        console_banner()
        editor_gui()
    else:
        banner()
    print("Mockups generated in", OUT)
    for f in sorted(os.listdir(OUT)):
        size = os.path.getsize(os.path.join(OUT, f))
        print(f"  {f}  ({size//1024} KB)")
