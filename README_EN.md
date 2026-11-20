# Province

> This README comes in two languages: **English (this page)** and [简体中文 → README.md](README.md).
> 本 README 有两个语言版本：**英文（本页）**，以及 [简体中文 → README.md](README.md).

Draw your own administrative divisions over a Minecraft world and see them as a layer of vector colour on Xaero's world map and minimap.

Fully client-side: nothing is sent to the server, and nothing is written into chunk data. Regions live in a separate vector document.

## What you need

| Requirement | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.19.3 or newer |
| Java | 21 |
| Fabric API | the 1.21.1 line (tested on 0.116.17+1.21.1) |
| Xaero's World Map | 1.47.0 (the 1.21.1 line; world map overlay and map export) |
| Xaero's Minimap | 26.6.0 (the 1.21.1 line; minimap overlay) |

Xaero is optional to launch the game. Without it you simply won't see regions on the map, and the editor won't open.

Drop `Province-0.2.4+1.21.1.jar` into `.minecraft/mods`. The part after `+` is **the Minecraft version this jar is built for** (the usual Fabric convention). The download page carries one jar per game line while the mod's own version number stays the same, so pick the one that matches your game. Installing the wrong one stops the game at startup instead of quietly misbehaving.

The mod ships with **简体中文** and **English**. For 繁體中文, Français, Русский, Español, 日本語 or 한국어, add **Province Translation** (`ProvinceTranslation-0.2.4.0.jar`). It contains text only, no code: drop it in `mods` next to the main mod, one file covers every Minecraft version.

## Using it

### Opening the editor

Open Xaero's world map and press **P** to enter the editor; press it again to leave. You can rebind P in Options → Controls → Key Binds, under the "Province" category — the editor is keyboard-only, there is no button on the map.

In the same screen, **R** toggles the whole region layer on and off, so you don't have to dig through settings just to look at the terrain. R is rebindable too.

Once the editor is open the map still pans and zooms as usual, with a panel down the left side. **P** is the only switch for the editor: one press in, one press out.

### Making a selection

The top two columns of the panel are the tools:

| Tool | What it does |
| --- | --- |
| Brush | Hold and drag; everything the brush touches gets selected |
| Line | Drag a straight selection between two points (hold Shift to lock to the axes) |
| Rectangle | Drag out a solid rectangle (hold Shift for a square) |
| Circle | Drag out a solid ellipse (hold Shift for a circle) |
| Eraser | Remove whatever is currently selected |
| Magic wand | Click once to select the whole area enclosed around the click |

The brush and eraser use the "Brush N px" value below, one pixel per block, from 2 to 48. The other tools ignore it.

Selections always land on blocks (see "Snapping boundaries to blocks" below): the brush starts from whatever cell the cursor is over and steps along cell edges, so a boundary always runs along the edges of blocks. Diagonals come out stair-stepped and circles are assembled cell by cell.

**Hold Shift while dragging** and the Line, Rectangle and Circle tools snap to regular shapes: the line stays horizontal or vertical (whichever direction you dragged further), the rectangle becomes a square, the ellipse becomes a circle. Press or release Shift mid-drag and it just follows the current state; letting go of the mouse locks it in. Shift isn't a rebindable shortcut here, it's only a drag modifier.

The magic wand treats three things as walls: what you have already selected, the parent's boundary, and the space taken by siblings. It only selects the piece enclosed by those walls that contains the click; if it can't close a loop it does nothing rather than swallowing the entire parent.

Selections are **temporary**: press Save to commit them to the region, Cancel to throw them away.

### Tiers

"Tier L n" in the panel is the level you're editing, L1 through L5.

- Tier 1 is the top (provinces, states and the like), tier 5 the finest.
- A tier-N region can only be drawn inside a tier-(N-1) region, and siblings cannot overlap.
- Pick the wrong tier and nothing gets drawn — tier 1 has no parent, so its regions can only be laid out next to each other.

### Creating a region

Click "Region tree (structure / delete)" in the editor panel:

- "＋ New top-level region" in the footer creates a tier-1 region.
- The **checkbox** at the start of each row controls whether that region shows on the map. By default it follows "Which tier to show" in settings: pick tier 2 there and every tier-2 region gets ticked, the rest don't. Once you tick or untick a row yourself, that sticks until you touch "Which tier to show" again — at which point the whole batch resets to the default. Newly created regions follow the same rule right away.
- The **small triangle** (next to the checkbox, ▼ expanded / ▶ collapsed) only appears on regions that have children: click to fold the subtree, click again to unfold. This is purely how the tree looks — **nothing on the map changes**. Collapsed state is saved to the settings file and restored next time.
- "＋" creates a child region underneath this one.
- "⚙" renames it, recolours it, changes its tier.
- "✕" deletes the region and everything under it.
- "≡" drags a row up or down to reorder it **among its siblings**, children coming along. This only changes the order in the tree, never what's drawn on the map; dropping it under a different parent isn't accepted.
- Clicking a row selects it and takes you back to the map to edit its extent.

Creating a region opens the naming screen straight away: native name, English name, tier, and a representative colour picked from the 16 dye colours. Both name fields are always there — switch the interface to English and the first field is still your native name and the second the English one, so there's no "which field do I leave empty" to remember.

The tier list holds a hundred-odd historical and modern administrative names. The last entry is **"(no suffix)"**, which displays the bare region name. Two small buttons sit on the right of the footer:

- **"＋ Custom…"** (the last cell of the tier list, or the "+" in the footer) adds your own tier name. The native name is required — that's what the cell identifies it by — and leaving the English name blank makes it follow the native one.
- **"-"** only deletes tiers you added yourself (the ones with a gold border); built-in entries are refused. Regions that already use a deleted suffix keep it; they just fall back to the default "Province" when displayed.

Many countries have their own version of the same institution, so duplicated names in the list carry a parenthesised qualifier, kept as short as possible: modern countries get a single character — **县（中）**, **県（日）**, **County（US）**, **Powiat（PL）** — while Switzerland and the Arab world are spelled out. Chinese historical units (都, 郡, 州, 道, 路, 府, 军, 厅, 卫, 所, 里, 邑, 乡 …) are all grouped as **（古中国）** regardless of dynasty. The Japanese set is transliterated instead: 都 To, 道 Do, 府 Fu, 県 Ken, 郡 Gun, 市 Shi, 区 Ku, 町 Machi, 村 Mura. These qualifiers only appear in the tier cell and the "Tier:" line above it — **names on the map stay clean, e.g. "XX县"**. The **（县级）市** and **（地级）市** tiers append only "市" when composed into a name: "A市", not "A（县级）市", and English appends only "City".

To the left of the page buttons "◀ ▶" sits a small **"中 / EN"** toggle (shown as "EN / 中" when the interface is English): it switches the names in the cells between native and English. It's display only — the same tier stays selected, and entries without an English name fall back to the native one. English names are often much longer, so a cell that can't fit one shows just the beginning and **slowly scrolls left** while the cursor rests on it, then starts over; moving away resets it. The qualifiers scroll along with the name.

### Exporting a map

Three export buttons at the bottom of the editor panel: **Export current view** takes what's on screen right now, **Export the whole world map** takes the entire map, and **Export a coordinate range…** asks for four corners — X1/X2 and Z1/Z2 in block coordinates, inclusive, negatives fine — and exports exactly that rectangle. Reversed coordinates are handled: the smaller value becomes the start, the larger the end. One side is capped at 4096 blocks.

The PNG lands in `province/exports/` inside your save folder, and the folder opens when it's done. The screen shows "Exporting…" first, then the file name.

The image is what Xaero itself would export, plus the regions: fills, borders and place names are all redrawn at the export resolution. Names use a system font (one that renders Chinese correctly in the image), so they match what you see on screen.

**If a patch of colour is in the image, it has a name.** When a large region is sliced by the edge of the rectangle, the old "anchor at the bounding-box centre" rule failed because the centre fell outside the picture; now it falls back to a point inside the part that is still visible. If a name really does collide with another one it gets drawn anyway — a slightly crowded name beats a nameless patch.

### Settings

"Settings" in the editor panel:

- **Interface**: display language (base mod: 简体中文 / English; with the translation mod installed there's also "Follow the system" plus the other languages)
- **Display**: show regions on the world map / on the minimap / which tier to show / show region names / how far a name may spill over its region (slider, −12 to +12) / name format / fill regions / fill opacity / smart fill precision / smart border precision / square-kiloblock units / serial marks / legend corner
- **Automatic border**: border style (solid / dashed / dash-dot) / border thickness
- **Map export**: include regions in exports
- **Region entry prompt**: enable title / enable subtitle / trigger tier / prompt format / fade-in / hold / fade-out

Fill opacity defaults to 50%, and the +/- buttons step by 5%.

**Square-kiloblock units** is off by default. Turn it on and regions larger than one million square blocks are shown in **square kiloblocks** with three decimals (`1234.567 sq km`), while anything smaller stays in square blocks. Areas in the region tree and the "Selection N" readout in the editor panel follow the same rule.

### Interface language

Under Settings → Interface → Display language. Switching takes effect immediately, no restart. The base mod only carries Simplified Chinese and English, so there are exactly two choices here: a Chinese system language lands on 简体中文, everything else on English. With only the base mod installed there is no "Follow the system" entry, because both languages can be named directly. For more languages, install **Province Translation** (`ProvinceTranslation-0.2.4.0.jar`).

With the translation mod installed the language list gains a **"Follow the system"** entry, and only then can you pick the 繁體中文, Français, Русский, Español, 日本語 and 한국어 it brings. If the system language matches one that has text, that entry reads "Follow the system"; if it doesn't (German, Portuguese…), it reads "English (Default)" instead of offering you an option whose name doesn't match anything.

The translation mod is code-free: keep it in `mods` next to the main mod, one file covers every Minecraft version, and removing it drops you back to the two built-in languages. While it isn't installed, a small **?** sits to the left of the language row: hover it or click it and a bilingual note tells you where to download the pack.

How game languages are mapped: Classical Chinese (`lzh`) counts as Traditional Chinese — the classics are written in Traditional anyway; Traditional variants (`zh_tw` / `zh_hk` / `zh_mo` / `zh_hant`) share one set; Simplified and the remaining Chinese variants go to Simplified; English variants (`en_us` / `en_gb`) and Spanish variants (`es_es` / `es_mx`) fold into English and Spanish respectively. Anything else (German, Portuguese…) falls back to Simplified Chinese or English depending on whether the system language is Chinese, when only the base mod is installed.

The interface language and the two name columns of a region are independent. **With "Native + tier suffix" as the name format, the suffix follows the interface language** — a Chinese interface gives 静冈县, an English one gives Shizuoka Prefecture, and a French one also gets the English Prefecture (tier suffixes only exist in Chinese and English so far). No "Paris省" hybrids.

English runs longer than Chinese, so buttons and setting rows don't try to squeeze it in: they show the beginning and scroll left slowly while hovered (starting after 0.6 s at 30 px/s), resetting when the cursor leaves.

### Where names go

Names are only constrained **horizontally**: if the bounding box can't fit the whole string, the region is skipped and the serial marks take over. Whether it fits is decided by the "How far a name may spill" slider — 0 by default (don't draw if the text is wider than the region), +12 lets a line stick out 12 pixels, and −12 demands the region be wider than the text. Height is not constrained: thin regions would otherwise never get a name.

Names are drawn in a **final pass** after every fill and border, so an earlier region's name can't be covered by a later neighbour's fill. Serial marks are one layer later still, always on top.

The anchor must have its **centre inside the region**, and then the position that both fits and sits near the centre wins: the bounding-box centre is tried first, and if it doesn't fit (it may fall into a notch), the whole region is searched on a grid, with **"fits" ranked above "near the centre"**. Odd shapes therefore push their name towards the roomy end instead of insisting on the middle; if nothing fits anywhere, the serial mark takes over. Each exclave is treated separately.

**Very odd shapes can leave a name looking awkward.** In a U-shape, a thin crescent, a region with a hole, or a ring, the text may end up pushed to an edge, over the border, or tucked into a corner of an exclave. Only positions the shape allows are considered, and when the outline offers nothing better, that's what you get. The direct fix is to draw a tidier shape.

#### Serial marks

Off by default. Turn it on and regions whose **names never fit** are no longer blank: a ①②③… is drawn on them, with a legend in a screen corner saying which number is which region. The numbers are circled digits drawn exactly like any other place name — no box, no shadow.

- The trigger is the fitting rule above: a region gets a number only if its name never got drawn once (each exclave counts separately, so a region with at least one drawn name doesn't qualify).
- Numbers are handed out in region-tree order, up to 20 — ①②…⑳ is all the circled digits there are, and more than that stops being readable.
- **Only regions on screen get a number.** Off-screen regions don't consume numbers, and they can't fill up the legend either.
- Serial marks and the legend count as map content like names: they follow the map zoom, whether or not the editor panel is open.
- The legend corner setting (top-left / top-right / bottom-left / bottom-right) decides where it sits; top-left automatically steps around the editor panel.
- It only makes sense with "Show region names" on: hiding all names and then numbering every region would just be noise.

Settings save immediately to `config/mc-province/settings.nbt`. Custom tier names live separately in `config/mc-province/levels.nbt` — the built-in entries are in code and never enter that file, so deleting it only loses the ones you added.

#### Smart fill precision / smart border precision

Two switches in the Display group, both **off** by default.

Keeping them separate makes sense: fill cost grows with **area**, border cost grows with **outline length**, and those are different things to save. A huge but simple region only needs the fill switch off-turned; a ragged coastline of a region with modest area only needs the border one. They default to off because the exact mode looks best; turn them on when you start dropping frames, independently if you like.

**Since 0.2.3, either switch also snaps boundaries to a coarser grid.** Previously the smart mode only skipped scanline rows and merged short edges, which still meant projecting hundreds of thousands of single-block steps once you zoomed out. Now the boundary itself is pulled onto a coarser lattice, so the edge count drops by multiples as the grid coarsens. There are four steps, gated on how many screen pixels one grid cell still occupies:

| Screen pixels per block | Snapped to | When you'll see it |
|---|---|---|
| ≥ 1.0 | single block (no snapping) | normal viewing, boundaries hug the blocks exactly |
| ≥ 0.125 | 4×4 blocks | single-block edges are no longer visible |
| ≥ 0.03 | one chunk (16×16) | a block is a few hundredths of a pixel, only chunk outlines remain |
| below that | 4×4 chunks (64×64) | a block is under 0.03 px, thirty-odd blocks share one pixel |

0.2.4 lowered both thresholds by roughly half: 16→64 went from 0.0625 to 0.03, and 4×4 from 0.25 to 0.125 — snapping costs precision, so take the extra step while you can still see it.

- **Fill**: scanlines skip rows as needed. The more outline edges and the more zoomed-out the map, the wider the row spacing. The colour still joins up; edges just get a little rougher at small scales.
- **Borders**: edges too short to see (under 0.35 px) are merged into the previous one, saving dots that could never be sampled. On a region zoomed to fill the screen, more than half of its boundary segments are shorter than that.
- **Editing snaps too**: what you see while drawing uses the same grid as when viewing, coarser the further out you are — WYSIWYG. Zoom back in with the wheel to see precise boundaries.

**With both switches off, every boundary is drawn exactly at every scale**: no edge merging, no snapping, one sample per screen pixel. Cleanest picture, at the cost of very bad stutter once the outline is complicated — a ragged region can eat hundreds of thousands of dots.

Smart mode in 0.2.2 and earlier only skipped rows and merged short edges, with no grid snapping. Turn both switches off to get that feel back.

### Region entry prompt

When you walk into a new region in game, its name flashes up in the middle of the screen in vanilla title style. Off by default; enable it in settings. Fade-in / hold / fade-out default to 10 / 40 / 10 ticks.

## Where the data lives

- Single-player: `<save folder>/province/<dimension>.nbt`, one file per dimension, named `minecraft_overworld.nbt` / `minecraft_the_nether.nbt` / `minecraft_the_end.nbt`.
- On a server: `config/province/worlds/<server address or default>/<dimension>.nbt`.
- Exported images: `<save folder>/province/exports/`.

The data is entirely client-side: it survives moving to another machine or another modpack, but the server never sees it and neither do other players.

**Old data migrates automatically.** Boundaries saved in 0.2.0 and earlier were continuous coordinates (diagonals, curves); 0.2.1 re-snaps them onto block edges when loading, then writes them back (the `version` field in the file header goes from 1 to 2). This happens once per old file and never again. The regrown shape follows the same "keep the block if its centre is inside" rule, so edges may shift by a block or so; position, tier, names and colours are untouched.

**To copy regions to another map**, copy the `province` folder (or a single dimension's `.nbt`) over. Regions store absolute world coordinates and vector outlines, independent of terrain, and the file names contain no save name or seed, so position, shape, names, tiers and colours all carry over. Three caveats:

- **Quit the game first.** The mod compares against the current world every tick and writes its in-memory copy back on a world change, so overwriting while it runs gets clobbered.
- **Match the dimension.** The Overworld file goes to the Overworld.
- After changing the seed, "same position" only means the same coordinates; the terrain there may be different.

## How it works

### Shapes are vectors, not bitmaps

A region's shape is a `core/VectorShape` wrapping a `java.awt.geom.Area`: it can be intersected, unioned, subtracted, point-tested, measured, bounded and walked as polylines.

Vectors rather than per-block cells because the map zooms out so far: shrink a province to a few dozen pixels and a bitmap turns to mush while the vector is still that same edge.

### Snapping boundaries to blocks

Since 0.2.1, **a shape's boundary always lies on block edges**: all vertices are integers, edges run along block faces, and nothing cuts diagonally through a cell. The constraint comes from `core/BlockSnap`, which turns any shape into a set of blocks with one rule — block `(i, j)` belongs to the new shape if and only if its centre `(i+0.5, j+0.5)` lies inside the original. Scanlines collect them row by row, and identical adjacent rows merge into a single rectangle.

The editing side does its part:

- mouse positions are floored to cell coordinates, strokes only record cells, and adjacent cells are joined into steps;
- brush width becomes a block radius (`radius = (brush - 1) / 2`, rounded down), stamping a disc of blocks along the stroke, always at least one cell wide;
- rectangles, ellipses and wand selections all go through the same snapping.

So dragging diagonally gives stair-stepped block edges and circles are assembled cell by cell. The upside is that region boundaries align with the terrain by construction, with no half-block gaps even at one pixel per block. All coordinates are block coordinates (doubles); scale is only applied when rendering.

### Spatial index

`core/ProvinceStore` buckets regions by 256-block cells so a point query only touches a few of them. Very large regions (more than 4096 cells) skip the buckets and live in a global list instead — otherwise a province covering half the map would appear in every bucket.

### The editing mask

While editing a tier-N region, every change to the working shape goes through the mask:

```
result = working shape ∩ parent extent − other regions of the same tier
```

The parent extent comes from `ProvinceStore.parentBound(parentId)`, and the siblings are unioned by `ProvinceStore.siblingUnion(tier, selfId)`. Tier 1 has no parent, so `parentBound` returns `null` and nothing is restricted.

### Magic wand

There are three kinds of fence, and missing one lets the selection spill: the strokes you drew, the outline of what is already selected, and the area the current tier may occupy (parent boundary plus siblings). **The first two are walls that already exist — you can click without drawing anything.** Tier 1 has no parent, so if there are siblings nearby a temporary box is built at the click point as the outer boundary; with no siblings at all there are no edges, and the wand refuses to act.

`core/FenceFaces` snaps endpoints to a 1e-4 grid, joins segments that share endpoints into a planar graph, then finds faces — only lines that actually connect count as enclosing something, and a hairline gap means they don't. Input segments are first culled by bounding box and duplicate edges removed (two coincident directed edges would form a two-edge fake loop and drop the whole face). Pairwise intersection is quadratic; a single click tops out at a few thousand edges and is truncated by priority beyond that.

**Fence walls only sit on block edges and never cut through the middle of a cell.** Already-selected shapes, parent boundaries and sibling outlines are all built from blocks, with vertices on integer lattice points and axis-aligned edges between them, so walking the boundary segment by segment just works. The line you drew yourself has its endpoints rounded to the grid, but the **connecting line is left intact**: split a diagonal drawn with the line tool and it lands exactly on two other lines, collapsing the triangle it enclosed. Closing edges get at most 32 blocks; with too many free ends they're truncated in order rather than skipped entirely.

The click point is **the centre of its cell** (`gx + 0.5, gz + 0.5`), not a corner — walls sit on integer lines, so a corner click has a 50/50 chance of landing on a wall, and `Area` doesn't define which side a boundary point is on, which is how you end up selecting the neighbouring face.

**Take the shortcut whenever possible.** The walls are always the same few things (the selection, the siblings, the surrounding boundary), so subtracting the selection and siblings from the outer boundary and picking the connected component containing the click is enough (`EditSession.quickFill`). The planar graph does quadratic intersections, and a big open area reaching the parent boundary has a thousand-plus outline segments; once the fence exceeds its budget and gets truncated, the face can never close — which shows up as "I clicked a huge empty area and the wand did nothing". So the shortcut is tried first. If you drew lines, faces are found from those lines first (that's the only way to enclose a shape the walls alone don't describe), and it still falls back to the shortcut. The segment budget is also split between the three outlines and your lines, so none of them starves the others.

If no face is found, nothing happens — no falling back to "select the entire parent", which is exactly what "one wand click recolours everything" used to be. The return value reports how much the selection **actually** changed, and clicking already-selected ground honestly reports zero.

### World map overlay

Since 0.2.2 the mod no longer injects into Xaero's world map at all. `GuiMap.render` is over five thousand instructions long, and the loader's bytecode rewriter throws `Too many operations` the moment it tries to write it back, so the class is left untouched: Fabric's `ScreenEvents.AFTER_INIT` recognises `xaero.map.gui.GuiMap` and a `afterForeground` hook draws a layer after it each frame. Camera position and zoom are read reflectively (`cameraX` / `cameraZ` / `scale` / `screenScale`), with a snapshot kept for export.

The overlay is attached once per screen init. Coming back from a sub-screen such as the region tree makes Xaero re-init, so it has to be re-attached every time — and it's attached to that particular screen instance.

`client/render/ProvinceRenderer` does the actual drawing: fills via scanline, borders by subdividing along edges. This is where frame time goes, so:

- **Each region's outline is projected once per frame** for the border, and separately for the fill (the fill can't reuse the screen-clipped copy, see below).
- Projecting **merges connected edges that are too short to see into the previous one**. On a region zoomed to fill the screen, many segments aren't even a third of a pixel long; merging them cuts the scanline's active edge list substantially.
- The fill scanline **skips rows according to outline edge count and zoom step**: more edges and a smaller map mean wider spacing. The colour still joins up, and the scanline does a fraction of the work. "Smart fill precision" in settings is its switch.
- At the same step, **the border merges short edges more aggressively** as you zoom out; fill and border are different costs with separate switches.
- When stroking, **consecutive pixels that are genuinely adjacent on the same row are accumulated into one run**. An 800-pixel straight edge used to be 800 `fill` calls and is now one — this is the main source of the frame-rate gain. Only strictly adjacent pixels merge, so two runs separated by a gap never become one bar.
- A region whose bounding box is entirely off-screen is skipped; scanline rows entirely outside the viewport submit no draw calls.
- The fill's edge list is **clipped top and bottom only, not left and right**. Scanline filling needs the x where each edge crosses the row, and that x being off-screen is fine — it gets clamped before submission. Clipping left and right makes off-screen edges vanish entirely, an intersection pair goes missing, and a horizontal seam appears on screen — the bigger the region, the more stripes.

In smart mode the stroke has a total sampling budget; exceeding it **uniformly** widens the sample spacing: lines get sparser but the whole outline still gets drawn. That widening is capped — past a few pixels the dots stop connecting and the border looks dashed, or vanishes entirely. **Turning smart mode off disables the budget altogether**: spacing is always 1 pixel, merging thresholds go to zero, no edge is skipped.

Rendering doesn't depend on any particular draw API; it receives a `ProvinceRenderer.Sink`:

```java
void fill(int x0, int y0, int x1, int y1, int argb);
```

The screen, the world map and the minimap each wrap it in a lambda.

### Minimap overlay

The minimap can't just be drawn over like the world map — it's a circular texture. So `mixin/MinimapElementRendererHandlerMixin` injects `render(...)` and draws regions into the minimap's own FBO only when `location == IN_MINIMAP`; `mixin/MinimapElementMapRendererHandlerMixin` injects `prepareRender(DDDF)` to grab this frame's rotation, zoom and view radius.

The coordinate transform matches Xaero's own:

```
translateX = ps * offx * zoom - pc * offz * zoom
translateY = pc * offx * zoom + ps * offz * zoom
```

where `ps` / `pc` are the sine and cosine of the facing angle.

The clipping rectangle is derived by back-projecting all four corners into a bounding box, not just two edges — with south-up, a two-edge calculation yields `minZ > maxZ` and the entire map's regions get clipped away.

### Export

`client/MapExporter` reuses Xaero's own `PNGExporter`: export the map to PNG first, then overlay the regions with Java2D. The covered block range comes from Xaero's chunk selection, so the overlay lines up with the base image.

0.2.3 fixed three "export doesn't match the screen" issues:

- **Border thickness**: width used to be `pixels per block ÷ 16`, which shrinks to a single pixel on a full-map export, far thinner than the preview. It now uses the same factor as font size and dash period (export pixels per block ÷ screen pixels per block), so the preview thickness carries over.
- **Broken boundaries**: a region can be several disconnected islands (exclaves, overseas parts). Stacking every segment into one path and closing it once drew a fake edge between two islands, as if a boundary had a gap. Now each island from `components()` gets its own sub-path and closes itself, joined with a 1e-6 tolerance so floating-point error can't shred it.
- **Names smeared together**: the font size floor is now 2× and the ceiling 24×, and names already drawn on the image reserve their space, so later ones step aside — small neighbouring regions no longer pile every name on top of each other.

Names are drawn with `client/PixelFont`, which is pure CPU (`java.awt`, no OpenGL). Glyphs come from the client resource pack: ASCII from `textures/font/ascii.png`, everything else from `font/unifont.zip`, with metrics copied from vanilla's `BitmapProvider` and `UnihexProvider`.

Export is synchronous and takes seconds, so the button press only pushes "Exporting…" onto the screen and the work starts on the next frame.

**Export coordinate range** first rounds the rectangle out to the smallest set of chunks covering it, hands that to Xaero, then crops back to the exact rectangle using the image's pixels-per-block before overlaying regions — so image size and coordinates match exactly.

**"Every patch of colour in the image has a name"** has two fallbacks: `paintNames` first checks with `touches(shape, ...)` whether the region intersects the exported world rectangle and skips it otherwise; when the normal anchor search (`nameAnchorForText`) fails all six rounds, `paintNearest` takes over — sampling `shape.contains(...)` on a pixel grid, picking the visible point closest to the bounding-box centre, clamping it into the image and drawing there, even if it collides with another name (the last resort always draws).

### Gotchas when changing the code

This section is about the 26.x line. Every version line shares the same logic; only the draw API splits in two: 26.x uses `GuiGraphicsExtractor` (`text` / `centeredText` / `outline`, with drawing split into a record pass and a render pass submitted at the end of the frame), while 1.20.x and 1.21.x use `GuiGraphics` (`drawString` / `drawCenteredString` / `renderOutline`, drawn immediately). Check which port you have open before touching screen code.

These are the ones hit on 26.2:

- **Key category lang keys are singular**: `key.category.mc-province.main`. The plural form `key.categories.…` belongs to `KeyMapping` before 1.21; 26.2 only accepts the singular, and a wrong key shows the raw key name in the UI.
- **`Screen` no longer has `hasShiftDown()`.** Ask GLFW directly: `InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), InputConstants.KEY_LSHIFT)`, once for each Shift.
- **`ResourceKey` identifies with `identifier()`**, not `location()`.
- **`EditSession.onRelease()` takes no arguments**; early versions passed a coordinate.
- **`VectorShape` has `contains(double, double)`**, so point-testing doesn't need to fetch the `Area` first.
- **`CompoundTag` has no `getIntArrayOr(String, int[])`.** A comma-separated `String` is the easiest way to store a list of ints (`collapsed` works that way).
- **Delete `build/resources` after changing the version number**, otherwise `processResources` won't re-expand and the jar name silently disagrees with the version in `fabric.mod.json`.
- **Don't finish with `Get-Process java | Stop-Process -Force`.** Gradle daemons are java too, and killing them means a slow cold start on the next build. Only kill the game processes whose command line contains `devlaunch`.
- **`mouseScrolled` has a different arity per line**: three arguments on 1.20.1 (`mouseX, mouseY, scrollY`), four from 1.20.2 on with a horizontal scroll amount. Getting it wrong reports "method does not override or implement a method from a supertype" and the compiler prints both signatures.

### Self-test

To verify a change quickly, enable the self-test with an environment variable:

```sh
# Windows
set MCPROVINCE_SELFTEST=1
gradlew.bat runClient

# Linux / macOS
MCPROVINCE_SELFTEST=1 ./gradlew runClient
```

A batch of assertions that don't need the resource pack runs at startup, and another batch that does (pixel font, export) runs once you're in a world. Results go to the log.

Adding `MCPROVINCE_AUTOWORLD=1` also creates a test world, builds a few regions, takes a screenshot and quits.

## Building

```sh
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

The result is `build/libs/Province-0.2.4+1.21.1.jar`.

The version numbers and the MC version live in `gradle.properties`: `mod_version` is the mod's own version and `minecraft_version` is the game version it targets; `build.gradle` only joins them into `Province-<mod_version>+<minecraft_version>.jar`. To build for another game version, change `minecraft_version` (and the matching `fabric_api_version`) and rebuild; the jar name follows automatically.

## Directory layout

```
src/main/java/com/mcprovince/
  McProvince.java            entry point
  ProvinceSelfTest.java      self-test
  core/                      shapes, snapping, regions, tier names, area format, spatial index (no client deps)
  data/                      NBT I/O and per-world isolation
  edit/                      edit session, tools, history, planar graph for the wand
  client/                    keybinds, edit state, settings, export, pixel font
  client/gui/                region tree, naming, custom tier, settings screens, plus shared Ui and ScrollViewport
  client/render/             world map overlay, minimap overlay, renderer, projections
  mixin/                     minimap injection points and raw keyboard/mouse hooks
src/main/resources/
  fabric.mod.json
  mc-province.mixins.json
  mc-province.minimap.mixins.json
  assets/mc-province/
    icon.png                 mod icon (the one in the mod menu)
    lang/                    built-in text: Simplified Chinese (zh_cn.json) and English (en_us.json); other languages ship in Province Translation
```
