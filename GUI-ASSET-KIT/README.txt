RECRUITS: RTS CAMERA - GUI ASSET KIT (0.10.7-server.1)

This folder contains the current editable/source-resolution Commander art.
It deliberately excludes documentation/debug clutter.

CRITICAL PIXEL RULE
- Do NOT destructively resize authored sprite sheets.
- 32x32 source cells stay 32x32. Render-time nearest-neighbour scaling is fine.
- Banner emblem cells stay 64x128 and are already aligned to the supplied banner canvases.

CURRENT UNIT CARD ICONS
unit_cards/UnitIcons_BlackTrim.png: 32x256, eight 32x32 rows:
0 Sword
1 Spear(s)
2 Bow
3 Crossbow
4 Horse / cavalry
5 Horse Bow / horse archer
6 Ship
7 Catapult
Shield infantry intentionally reuses row 0 Sword because the supplied set omits a separate shieldman icon.

LEGACY/REFERENCE UNIT SHEET
unit_cards/UnitIconsSprite.png is retained as an art/reference asset from the earlier layout.
The 0.10.7 runtime uses UnitIcons_BlackTrim.png for card classification.

BANNER EMBLEMS
banner_emblems/coloredUnitIcons_SilverTrim.png
banner_emblems/coloredUnitIcons_GrayTrim.png
banner_emblems/coloredUnitIcons_GoldTrim.png
Each is 64x1024: eight rows of 64x128, with the same row order as the card icons.
The runtime layers the matching emblem directly over the static banner trim/cloth.

BANNERS
Pointed, Rounded and Square each retain their 16-row cloth-color sheet and 3-row trim sheet.
Trim rows: 0 silver, 1 dark/black, 2 gold.
Current banners remain static 2D. Red retains the intentional gold-trim mapping.

SHIELD STATE ROWS (32x32 each)
0 shield down
1 shield up

CHEVRONS (32x32 each)
0 three gold
1 two gold
2 one gold
3 three silver
4 two silver
5 one silver
6 three copper
7 two copper
8 one copper

SELECTION MARKERS
selection_markers/selection_marker_circle.png and selection_marker_square.png are 32x64 sheets:
row 0 (top 32x32) = hollow / hover
row 1 (bottom 32x32) = filled / selected
They are white masks tinted Commander yellow at runtime and can be freely redrawn while preserving the two-row layout.

COMMANDS
Contains the current replaceable bottom-bar command icons, including Bodyguard Follow and Enemy Banners.
