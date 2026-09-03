# FlatLand CTF-Workspace Notes

## Objective

Animate user's Java Swing game (FlatLand) with itch.io assets WITHOUT changing game mechanics. DISPLAY ISSUE SOLVED (Xephyr). Art overhaul COMPLETE: Cute Fantasy RPG pack (Kenmi) for player/enemies/level + 0x72 dungeon weapons.

## Environment (CONFIRMED)

- Compositor: **niri 26.04** (NOT GNOME) + **xwayland-satellite 0.8.1** (`:0`). All X11 apps go through satellite.
- GPU: Intel TigerLake iGPU + NVIDIA RTX 3060M (hybrid). Fedora 44.
- User: thinh, /home/thinh/proj/FlatLand. Session: Wayland (`wayland-1`), X display `:0` via satellite.
- No system javac (headless JREs). Temurin JDK 25.0.4.1+1 at /tmp/opencode/scratch/jdk/jdk-25.0.4.1+1/bin (full JDK).
- JDKs present: Temurin 25 (/tmp), Fedora java-21, java-25-openjdk (headless), java-latest-openjdk 26. NONE have WLToolkit (all fall back to XToolkit).

## Key Facts (verified)

- All 3 installed JDKs + every Java2D pipeline flag render ORANGE TEST WINDOW AS BLACK: X11 default, xrender=false, opengl=True, pmoffscreen=false, WLToolkit-arg (void: falls back), across JDK 21/25/26.
- Control: ImageMagick `display` (non-Java X11) renders CORRECTLY (118k orange px in niri D-Bus screenshot). So satellite CAN display X11 windows.
- Game self-verify via Robot.createScreenCapture of own window: ALL BLACK (507300 blackPx). Paint EDT healthy (paintCalls++ ticks, no exceptions, sprites 38-39 loaded/0 fail).
- niri implements org.gnome.Shell.Screenshot D-Bus (works, saves to ~/Pictures/Screenshots/"Screenshot from ... .png" — QUOTE the path, spaces!).
- Earlier "verified rendering" claims were wrong: Robot black-counted-as-floor bug; user was RIGHT (window blank).

## Footguns (recurring!)

- `pkill -f <pattern>` self-matches the tool's own bash cmdline → kills own shell / loop. Use PID files (setsid nohup ... & echo $! > pid; kill $(cat pid)).
- Screenshot filenames contain spaces → must quote when passing to magick.
- SpriteAnim diagnostic printed BEFORE panel construction → always showed loaded=0. Now delayed 3s timer.
- Game window 300x200 originally — easy to miss on 1920x1080.
- **/tmp/opencode gets WIPED between sessions** (tmpfs). JDK must be re-downloaded: `curl -sL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" -o /tmp/opencode/scratch/jdk.tar.gz && tar xzf ... && mv jdk-* jdk`. T3.java must be recreated (see scratch).
- **My bash tool's children get killed when the shell exits** (cgroup cleanup) → setsid/nohup are NOT enough. Launch game via `systemd-run --user --unit=flatland-game --working-directory=/home/thinh/proj/FlatLand --setenv DISPLAY=:1 -- <jdk>/bin/java MainGame`; Xephyr via `systemd-run --user --unit=flatland-xephyr -p Restart=on-failure -p RestartSec=2 -- Xephyr :1 -ac -screen 1000x700x24`.
- **The 1000x700 Xephyr window IS the game container** — user closing it kills the game. Game window is centered inside Xephyr at (50,50), so verify crops are (50,50,950,650).
- **HEADLESS T3 MUST setSize(900,600)** — an unparented JPanel has getWidth()=0 → drawFloor draws nothing → false "no grass" conclusions.
- **9-arg Graphics.drawImage takes DEST CORNERS (dx1,dy1,dx2,dy2), not (x,y,w,h)** — passing w/h blew images up over the whole screen (the "player huge, no tiles" bug).
- **Cute Fantasy FREE pack has NO textured grass** — Grass_Middle/Path_Middle/Water_Middle are flat colors. Textured path/water interiors are in Path_Tile.png/Water_Tile.png rows 3-4 (16px cells: Path (0,3),(1,3); Water (0,3),(0,4)). Grass texture is synthesized in GamePanel.makeGrass() from the pack palette (#3E8948 base, #34743D/#265C42 dark, #5AC54F/#4BA54E light).

## Work State

### Done (session 3 — blob terrain + combat art)

- **Blob terrain + autotiling** (MainGame.java): value noise `vnoise`/`h2` (bilinear+smoothstep, worldSeed) → `isWater` (field<0.29, scale 9.0, spawn-clearing term within 10 tiles of SPAWN_TX) and `isPath` (field>0.75, scale 7.0, only on non-water). `blobCell()` picks 16px cells from sheetGrid(PATH_C/WATER_C): ring (0,0)…(2,2) by 4-neighbour mask, inner corners (0,4)(1,4)(1,3)(0,3)=diag TL/TR/BL/BR, interiors (1,1)+row5. Grass = procedural 4 variants + decor hash>=97 on land only. Thresholds calibrated via Hist.java percentiles (water ~10-12% global, path ~6%).
- **Sheet layouts VERIFIED by ASCII maps** (16px cells): ring = grass fringe on the land side; (0,3)=pppg (land BR), (1,3)=ppgp (BL), (0,4)=pgpp (TL), (1,4)=gppp (TR); row 5 = pure interiors; (1,1) pure. Water sheet identical template.
- **Sword black rect → steel slash arc**: Sword stores aimAngle=atan2(-dir) in use(); draw() = 2 fading arcs (250ms, drawArc start=-deg(phi)-35+120t, extent 70; r=1.6*range + inner r=1.15*range). Verified live: 501 bright px fading to 484 frame-to-frame.
- **Enemy yellow rect → amber telegraph arc**: Enemy.lastAttackTime() getter added; draw() replaces fillRect with fading amber arc (260ms, size*1.3 radius, toward player via -direction). Verified 0 saturated-yellow px on live screen.
- **Potions bob + sway** (WeaponView kinds 3/4: rot=sin(t*2.1)*0.14, hy+=sin(t*2.6)*2) and **arrow-on-staff** implemented (kind 1 draws ARROW at staff upper third, slides 4px·scale toward hand during 0.35 recoil); Bullet arrow rotation FIXED to atan2(dir)+PI/2 with center anchor (-w/2,-h/2) — sprite tip is at TOP (y=0), so +PI/2 points tip along flight. Verified live: arrow flying right (235 brown px in flight), staff+nock visible (100 px).
- **FIXED blobCell [row][col] transposition**: first version indexed the sheet grid as c[col][row] while sheetGrid stores [cy][cx] → all edge/corner tiles rotated 90° (user saw wrong-facing fringes). Correct mapping (c[cy][cx]): land right→c[1][2], land left→c[1][0], land below→c[2][1], land above→c[0][1]; outer corners TL/TR/BL/BR→c[0][0]/c[0][2]/c[2][0]/c[2][2]; inner corners land diag TL/TR/BL/BR→c[4][1]/c[4][0]/c[3][1]/c[3][0]. Verified two ways: pixel checker (0 mismatches on tree-free edge tiles) + all 16 mask→cell mappings checked against decoded quadrant signatures (e.g. (2,1)=pgpg grass-right). NOTE: tile-orientation pixel checkers must exclude obstacle tiles (tree canopy→'o', trunk brown≈path brown caused 43 false mismatches at ng≈0.39).
- Gun still draws its small black/cyan cooldown bar (not user-flagged; left alone).
- Input injection for verification: python-xlib XTEST (move/click/wheel) + X.GetImage fast captures — no xdotool on this system.
- Live verification: grass 74.7% / water 2.4% / path 6.8% / other 16.0%; player at screen centre (dark hair outline, facing up); slash arc + arrow flight + no yellow all confirmed.

### Done (earlier sessions)

- Art overhaul COMPLETE (verified on live Xephyr screen): Player (Cute Player.png rows 0-2 walk/3-5 idle, 32px cells, zoom=2·size/30), Enemy (Skeleton, directional), Enemy_Archer (Slime_Green 64px cells, unarmed), Obstacle (Oak trees/decor/chests by hash), weapons (WeaponView: 0x72 sword swing −60°→+60° 250ms, staff recoil, flasks), Bullet arrow sprite rotated, map (textured grass 4 variants + decor cells + 2x tiles), HUD alpha-blended gray.
- CREDITS.md updated (0x72 CC0 weapons/props; Kenmi Cute Fantasy, credit required).
- systemd units: flatland-xephyr (Restart=on-failure), flatland-game. run.sh still works for the user.

### Known cosmetic quirks (accepted)

- Player position vs hitbox: sprite bottom-anchored at y+size; close enough with 64px draw on 30px hitbox.
- selfCheck() Robot always throws SecurityException in this environment — harmless, verification is `DISPLAY=:1 import -window root` or python-xlib GetImage + PIL analysis.
- Isolated single-tile water/path cells (no same-terrain neighbours) draw the interior tile (no full-ring art exists); rare with blob noise.
- reset-failed on the transient flatland-game unit REMOVES it (transient units vanish) → recreate with systemd-run, don't just restart.

### Next steps (if user reports issues)

- Tune water/path coverage: thresholds in isWater/isPath (Hist.java computes percentiles per seed).
- If inner corners look wrong in-game, swap the (0,3)/(1,3)/(0,4)/(1,4) mapping in blobCell().
- If player looks misaligned: tweak Player.draw anchor or zoom.
- If game dies: check `journalctl --user -u flatland-game -n 5` — Xephyr window closure kills both.

## Files

- Game: MainGame.java (GamePanel: HUD overlay, selfCheck(), paintCalls, drawFloor with textured grass/path/water, makeGrass(), cell()), SheetAnim.java (sprite-sheet slicing w/ alpha pad detect, zoom-based), WeaponView.java (sword/staff/flask rotated drawing), SpriteAnim.java (CACHE, resolve(), loadedCount/failedCount), Player/Enemy/Enemy_Archer/Bullet/Obstacle (Cute Fantasy wiring), assets/cute/ (12 PNGs), assets/dungeon/ (0x72, weapons/props), run.sh.
- JDK: /tmp/opencode/scratch/jdk/bin (re-download if wiped). T3: /tmp/opencode/scratch/T3.java (headless frame dump, MUST setSize(900,600)).
- systemd units: flatland-xephyr, flatland-game (`systemctl --user restart flatland-game` to relaunch after recompile).

## 2026-09-01 session: text-map world generation (user request)

- RESTRUCTURED world gen to text-first per user demand: GamePanel now builds `worldText[313][313]`
  (MAP_TILES = WORLD_SIZE/TS + 1) in generateWorldText() at construction: 'g'/'w'/'p' chars.
  Layers: (1) same noise fields -> waterField/pathField (identical shapes/percentiles),
  (2) spawn clearing r6 forced grass, (3) isolated water singles -> grass.
  drawFloor is now a pure text->tile mapping via tileAt(tx,ty) (edge clamp 'g').
  blobCell lambdas: water -> same=='w'; path -> same!='g' (dirt flush to water edge,
  water tile draws grass bank). isWater/isPath methods DELETED (no refs).
- onSpecialTile(rect) package-private: rejects rects covering non-'g' tiles; used by
  obstacle AND both enemy placement loops -> 0 entities on water/path.
- MainGame.DECOR cleaned to natural art only (no seed-bag cells {80,0},{96,0}).
- Deterministic tests: -Dflatland.seed=N pins worldSeed (else currentTimeMillis()).
- T5 harness (in /tmp/opencode/scratch, recreated per session): extends GamePanel, prints
  map %, spawn-clear check, water-singles check, entity placement check, ASCII text-map of
  spawn view. Verified seed=12345: g82.08% w12.22% p5.71%, 0 spawn water, 0 singles,
  0/2000 entities on bad tiles, gen=745ms. T4/T5 need System.exit(0) (timer thread lingers).
- Fixed during verification: blobCell outer-corner + lone-side branches had INVERTED
  semantics (up=true means same-terrain above) -> corners rotated 180 deg, lone 'down'
  drew fringe at bottom. Correct: up&&left->c[2][2], up&&right->c[2][0], down&&left->c[0][2],
  down&&right->c[0][0], lone up->c[2][1], lone down->c[0][1], lone left->c[1][2], lone right->c[1][0].
- run.sh JDK path fixed -> /tmp/opencode/scratch/jdk/bin (was jdk-25.0.4.1+1 subdir).
- No services recreated. User launches with ./run.sh (at-based detached launch retired too).
- Pixel checkers retired: /tmp/opencode/scratch/check_shore.py exists but text map is
  ground truth now. beware: water foam + grass speckles defeat RGB tile classifiers.

## 2026-09-01 session 2: algorithmic autotiling + player animations (both DONE, T5/T6/T7 green)

### (a) Tile boundary rule -- algorithmic, no hand-mapped branches

- generateWorldText step 3 replaced: fixpoint "representability cleanup". A terrain
  tile is representable iff (U&D&L&R) or ((U||D)&&(L||R)); violating tiles trim to
  'g' (water->'p' first if orthogonally adjacent to path, so shore dirt survives).
  Loops over FULL map incl. border (tileAt clamps out-of-map to 'g'). Terminates:
  conversions only w/p -> p/g.
- blobCell() rewritten as algorithmic autotile: per sheet, cells measured once into
  4x4 quadrant bitmaps (feature = opaque non-grass-green px, >=5 px and >2x grass;
  care = decided quadrants). Per 8-bit neighbour mask (U1 D2 L4 R8 + TL16 TR32 BL64
  BR128), ideal bitmap: centre=1, edge quadrants=orthogonal-neighbour-same, corner
  quadrants decided only when both flanking edges agree (diag bit otherwise
  unconstrained). Pick = min #decided-quadrant mismatches; undecided (soft-edge)
  quadrants cost 0 -- counting them was the bug that made sparse corner cells tie
  and win. usable = feat!=0 && bitCount(care)>=8. mask==0xFF -> hash-pick from the
  4 pure interiors (feat==0xFFFF, auto-discovered). Table cached in IdentityHashMap
  AUTOTILE per Image[][] (static, built on first draw; ~ms).
- Grass underlay drawn under 'w' and 'p' tiles in drawFloor before the blob cell
  (fringe px are transparent; background can never leak through).
- T6 (/tmp/opencode/scratch/T6.java): 3 seeds x full world: (i) representability
  rule 0 violations incl. border; (ii) all 256 masks: pick is optimal
  (score==min over usable cells) and cell usable; (iii) worst score on masks that
  actually occur = 3/16 quadrants = the sheet's inherent soft edges (proven
  unavoidable: min over ALL cells equals it). EXIT 0. TERRAIN_TILES=51771.
- Sheet dump tool: /tmp/opencode/scratch/TDump.java (feat/care bitmaps per cell).
  (2,3) and (2,4) are fully empty (feat=0) -> excluded by usable.

### (b) Player animations from Player.png extra rows

- Sheet truth: rows 0-2 idle loops, rows 3-5 run loops, rows 6/7/8 = 4-frame
  sword slash (down/side/up), row 9 = 4-frame bow draw. Old code had idle/run
  rows SWAPPED (walk=0-2, idle=3-5).
- SheetAnim: added drawFrame(g,col,...,padOverride) -- clocked single-frame draw;
  padOverride>=0 pins feet anchor (attack rows detect pad=0 because slash arcs
  reach frame bottom -> body would float; force 7 like walk rows).
- Player: walk=rows 3-5 (80ms), idle=rows 0-2 (180ms); slash[] indexed by facing
  {0:row6, 1:row8, 2:row7} 70ms; bow=row9. Attack() now sets attackStart/attackRow/
  attackDur (sword 280ms, gun/bow 350ms); draw() plays one-shot col=min(t*4/dur,3)
  with pad 7, else walk/idle. tryJump(): Space, cosmetic parabolic hop 520ms,
  -44px apex via -sin(pi*t/520)*44; jumpOff applied to sprite anchor + weapon
  hand (NOT to hitbox/collision -- no gameplay effect). VK_SPACE in game loop.
- WeaponView arcs/nocked arrow kept on top; Bullet ang unchanged.
- T7 (/tmp/opencode/scratch/T7.java): renders stages headless: slash stages differ
  (1254/1110/522 px), rows 7/8 + bow animate (695/414/1175), jump apex diff 2147px,
  all 10 anim rows wired to correct sheet rows, idle-row0 vs run-row3 sheet art
  differs (166px). EXIT 0. GOTCHAS: (1) harness must re-read player "now" before
  each stage (live Swing timer drifts elapse past the anim window -> false FAIL);
  (2) getDeclaredField on T7 subclass misses GamePanel fields -> walk superclasses;
  (3) attackRow indexes slash[] by FACING (0/1/2), not sheet row.
- T5 regression still green (g82.88 w11.89 p5.23, 0 spawn water, 0 singles,
  0 entities on special, gen=719ms). Build: javac -encoding UTF-8 MainGame.java
  Obstacle.java Player.java (Player.java new in the build set).

## 2026-09-02 session: interaction systems overhaul (ALL DONE, T5-T11 green)

### User-facing fixes this session

- **Water corners (THE main complaint)**: `cornerCell()` hand-mapped override in
  MainGame covers ALL corner geometry: diagonal-only pairs (user's "b o / o b"
  case -> water wedges pointing at each other), outer corners, inner corners.
  Representability cleanup now keeps diagonal-ONLY contacts (renders as wedge),
  but diagonals never rescue 1-wide orthogonal pinch channels (those trim).
  T10 semantic audit: 11.5k water tiles, 3.5k corner picks, 0 bad.
- **Bow orientation fixed**: dedicated `drawBow()` in WeaponView — bow rotated
  90° from arrow (perpendicular to aim, string toward archer), pivot at grip
  (10.5,13)/14x26 sprite; nocked arrow in its own frame (rot = aim+PI/2)
  sliding back on the string during the draw window (0..0.3 of 350ms shot).
- **Hit particles**: tiny 3-star scatter (0.12*s scale) + 4 puff chips, NOT
  big circles. Death pop clouds massively reduced (0.12*s).
- **Arrows**: maxRange=320px straight flight -> stall (tip dips) -> LAND +
  stick in ground 4s with fade; IMPALE enemies 10s (rides body offset, fades
  out, vanishes if the enemy dies). No more flying off-screen.
- **Weather is real now**: fog = pixel-art fog-bank SPRITES (fog0-2.png,
  64x24 lumpy dithered alpha tiers) drifting in 2 depth bands; stormy/rainy =
  proper rain streak sprite (fixed screen-space bug: weather particles were
  camera-subtracted -> rendered off-screen); snowy = 6-arm flakes; windy =
  leaves crossing the screen.
- **Obstacle hierarchy (user demanded)**: abstract Obstacle base owns ALL
  disturb machinery (cooldown 500ms, damped wobble, wind idle sway via static
  windStrength, shedDebris per material). Subclasses: TreeBig(3x3 footprint,
  blocks), TreeSmall(2x2, blocks), Stone(blocks; pebble variant non-blocking),
  Chest(blocks, never shakes), Plant(flowers/grass/tufts/mushrooms — NEVER
  blocks, pure decor). Factory fixed: stones are their own branch now, not
  the old h<8 chest branch (chests-where-rocks-should-be bug).
- **Decor density**: +2600 small plants/pebbles/flowers scattered on grass
  (non-blocking). Movement collisions all respect ob.blocks().
- **Spawn rates reduced** for smoothness: 500->120 melee, 500->120 archers,
  300->90 mixed kinds, 80->25 ogres, 120->60 animals.
- **Day/night + weather**: Weather.java — 4min day cycle, light curve w/
  golden-hour tint; weather rolls every ~1min from weighted pool; lightning
  flashes in storms; ambient wind drives tree/grass sway.
- **Friendly NPCs (10)**: knight/elf/wizzard/dwarf/lizard/doc/angel (0x72
  frames, shared-clock drawKind), wander w/ leash, greet ("Hello!" bubble +
  sparkle) when player near.
- **Animals (60)**: chicken/cow/pig/sheep (cute pack 2x2 sheets), wander,
  flee when player close, splash into water.
- **Enemy bestiary**: Enemy_Brute (goblin art, fast), Enemy_Caster (shaman
  art, keeps distance, fires bolts), Enemy_Ogre (heavy, tanky).
- **Ground grid**: Ground[MAP_TILES][MAP_TILES] — playerOn/enemyOn/weather
  fields + trample tint, stamped each tick by panel.
- **Player**: updateWithSliding() — axis-retry sliding along obstacles (no
  hard stops); hurt() now calls die() from any damage source (was update-only
  = death never fired mid-freeze); respawn() resets all combat state.
- **Death/respawn**: freeze 1.2s w/ "You died — respawning..." overlay, then
  pop back at spawn with full health.

### New files

- Effects.java (sprite particles, screen-space weather), Weather.java,
  Ground.java, Friendly.java, Animal.java, Enemy_Kinds.java,
  assets/fx/* (hit_star, puff, drop, leaf, grass, spark, slash, snow, rain,
  glint0-2, fog0-2), assets/cute/{Player_Actions,Chicken,Cow,Pig,Sheep}.png,
  assets/dungeon/weapon_bow.png + ~230 frame PNGs (all 0x72 character kinds).

### Test state (all in /tmp/opencode/scratch, KEEP SHORT per user)

- T10 EXIT 0 (corner semantic audit), T11 EXIT 0 (22 checks: NPCs, animals,
  ballistics/impale, sliding, respawn, fog/rain spawn, ground stamps),
- T8 EXIT 0 (bow not-swim, nocked arrow, drink pose+overhead flask, swim),
- T6: 0 violations 0 suboptimal (worst=6 is the 1x diagonal pair handled by
  the cornerCell override, verified semantically by T10), T5: gen ~330ms.
- User directive: DO NOT run long tests — quick grep-EXIT only.

### Known cosmetic leftovers

- Potions effect icon still draws a plain oval above the player (pre-existing).
- Gun cooldown bar (pre-existing, user never flagged it).
- T7 was retired (referenced removed attackRow field); T8 supersedes it.

## 2026-09-02 session 2: tree art/block alignment (DONE, T12 green)

- **The invisible-barrier fix**: obstacles' art is now anchored by MEASURED
  root geometry, not the sprite cell. Obstacle.draw() computes
  zoom = size/anchorWidth() so the trunk+root FLARE fills the block width
  exactly, places anchorCx on the block centre X and anchorBottom ON the
  block bottom edge (root base = ground line). Measured anchors (opaque-px
  analysis): TreeBig (Oak_Tree) flare 36px @cx31.5 base-row 72; TreeSmall
  cell1 flare 14px @15.5 base 37; stones big/mid/pebble 15/13/10px; chest 15px.
- **Tile-quantized blocks**: blocking kinds normalize size to their tile
  block: TreeBig = 3x3 (96px), TreeSmall/Stone(big)/Chest = 1x1 (32px).
  Trees now LOOK like they occupy 3x3 / 1x1 — art root = collision box.
- **Saplings + mid stones are decor now**: TreeSmall cell2 (4px stem, no
  flare) and Stone cell1 (13px art can't fill 1x1) never block; factory
  makes ~25% small-trees saplings for variety.
- **Placement validates the FINAL block** (was: the random probe rect — a
  20px probe near a shore could pass then quantize into a 96px tree over
  water; T5 caught 19 entities on water/path, now 0).
- T12 (art/block alignment): magenta-bg render of each kind; checks root
  base ON block bottom (±2px), root zone fills >=70% of block width.
  TreeBig flare 91px of 96, TreeSmall 31 of 32, Stone 30 of 32, chest 32/32.
  EXIT 0. GOTCHA: brown-dirt test backgrounds confound root-tendril colors
  (63,40,50)/(109,72,59) — use magenta.

## 2026-09-02 session 3: giants + full prop repertoire from both packs (T13 green)

- **GiantTree**: 5x5-tile landmark (~1% of big trees, 2/world) — same oak art,
  root flare anchored on a 160px block; canopy towers over everything.
- **New obstacle kinds** (all anchored per measured root geometry):
  - 0x72: RuinColumn (broken/full variants, 16x48), RuinWall (plain/hole/goo),
    Crate, Skull, CoinPile (4-frame spin), Spikes (4-frame floor hazard, decor).
  - cute pack: WoodPile (2 log stacks), Barrel, Fence (post/mid/end pieces),
    Crop (rows 8-11: 4 growth stages x 2 variants), House (4x4 landmark,
    96x128), Bridge (planks over 4-tile water runs, decor-only, true 4x1 bounds).
- **Structured world features** (tile-aligned origins, zone-validated):
  ruin clusters (columns+walls+skulls+coins, 26 sites), fence runs along
  paths (40 sites), crop fields 3-5x3-5 (18 fields, one stage each),
  farmsteads (house+fenced yard+barrels+crate, 12 sites), bridges across
  4-tile water with land banks (150 tries), spikes/coins sprinkled on grass.
- Placement gotchas fixed: origins MUST be tile-aligned (pixel-random origins
  straddled validated zones -> crops/ruins on water); Bridge needs its own
  getBounds (4x1 water run, not the square 4x4 block).
- T13: all 14 new kinds render+block correctly; world has giants=2 house=2
  ruin=88 fence=58 crop=116 bridge=1 crate=33; T5 0 misplaced; T6 corners
  still optimal; T12 art/block alignment still green.

## 2026-09-02 session 4: villages + dungeons as coherent structures (T14 green)

- **New obstacle kinds** from decor sheet rows 3-6 (measured anchors):
  Lantern (r4-6 c4 vertical strip: cage/pole/base, warm glow disc, non-blocking),
  Gravestone (r3 c0-3, 4 variants, decor), Fountain (r4 c0-2, 3-frame animated,
  2x2 plaza centrepiece).
- **VILLAGES** (5, 11x11 tiles, placed BEFORE obstacle scatter so big zones
  claim space first): fountain plaza centre + 4 ring lanterns, 3 houses (NW/NE/SW),
  SE quadrant = fenced 4x4 crop field at one growth stage, 8 boundary lantern
  streetlights, yard barrels/crates/woodpile, 1 chest, 2-3 named villagers
  (leashed to plaza via setHome), 2-4 livestock (chickens/pigs).
- **DUNGEONS** (4, 9x9 tiles): outer ruin-wall ring with 1-tile gates N/S/E/W,
  4 corner watchtower columns, NW graveyard (gravestones+skulls), SE treasure
  hoard (chest + 6-coin ring), spike traps in NE/SW, broken column debris,
  garrison of 6-8 enemies (brute/caster/ogre mix) spawned INSIDE pre-occupied.
- Wilderness: bridges + sparse gravestone/skull/spike singles away from
  structures; wild herds (cows/sheep) still roam at large.
- GOTCHAs: (1) interior spawns MUST happen before the structure zone is added
  to `occupied` (zone blocks its own interior otherwise); (2) structures place
  BEFORE the random obstacle scatter or 11x11 all-grass sites never fit;
  (3) T5/T14 mismatch was a stale .class — always rebuild before re-running.
- T14: villages=5 dungeons=4, every village [f=1 lamp=12 house=3 fence=12
  crop=4 +chest] + villagers + livestock leashed; every dungeon [wall=28
  tower=7 grave=5 spike=4 coin=6 +chest] + 5-8 garrison; 0 misplaced.
  Full suite: T5 0-misplaced, T6/T8/T10/T11/T12/T13/T14 all EXIT 0.

## 2026-09-02 session 5: real maps + semantic sprite registry (T14/T15 green)

- **Semantic sprite registry**: all 58 usable sprite cells extracted from both
  packs to `assets/map/*.png` with CONTENT-NAMED files (well, birdbath,
  signpost_right/blank, gravestone_0-3, fountain_0-2, lantern_post, chest_x4,
  woodpile_a/b, barrel, crop_s{stage}v{variant}, flowers_*, bush_*, stump_*,
  rock_*, ruin_column/wall variants, crate_wood, skull_bone, coin_pile,
  spikes, chest_treasure_open). Text->visual mapping is now 1:1 by filename.
  (No vision model available in this harness -- identification was done by
  pixel-structure analysis: palette classes, geometry, animation sequences.)
- **MapGen.java**: real cartography. Sites (5 villages 12x10, 4 dungeons
  13x11) claim validated all-grass zones FIRST; then a ROAD NETWORK: Dijkstra
  over the tile grid (water cost 50, road 1, grass 3) connecting each site to
  its 2 nearest neighbours (dungeons 1 road); roads route site-BORDER to
  site-BORDER and site interiors are hard walls (cost 1e6) so roads never cut
  through house plots. carveRoad writes 'p' tiles into worldText (drawn by the
  existing path autotiler).
- **Village layout**: street grid (main street E-W row 5, cross street N-S
  col 6, 3x3 road plaza), lantern streetlights every 3 tiles, fountain plaza
  centrepiece + birdbaths + well, 4 houses (4x4, clear of the cross street),
  fenced 4x4 farm with 2x2 crops, barrels/woodpile/chest, villagers + livestock.
- **Dungeon floor plan**: outer wall ring, interior cross walls with door gaps
  at the room corners, 4 rooms: NW graveyard (gravestones+skulls), NE garrison
  barracks (spikes + 4+ guaranteed enemies incl. deterministic fill), SW rubble
  (broken columns/crates/barrels), SE treasure vault (chest + coin ring + ogre
  guards), gatehouse columns + signpost (snapped to nearest grass).
- **Edge-triggered vegetation** (user-reported bug): plants shake ONCE when a
  MOVING player ENTERS their reach; standing still inside never re-shakes;
  the plant re-arms only after the player fully EXITS (vegArmed set). T15
  covers: outside->no shake, enter-moving->shake, stand->none, mill-around->
  none, leave->re-arm, re-enter->shake again (after 500ms cooldown).
- **Roads-are-sacred cleanup** (fixpoint): blocking props nudged to all-grass
  tiles; last resort removal. Street furniture (lantern/fountain/signpost) is
  intentionally ON road tiles (non-blocking).
- Spawn hardening: stone scatter validates the QUANTIZED 32px block (probe
  rect != final block was the on-water flake); garrison guaranteed 4+.
- T14: villages=5 dungeons=4, every village complete [fountain, 8 lanterns,
  4 houses, 12 fences, 4 crops, well, 2-3 birdbaths, signpost, chest, 3
  villagers, livestock], every dungeon [40+ walls, 4 gate columns, 5 graves,
  skulls, spikes, 6 coins, chest, 4+ garrison], roads walkable, nothing on
  water. 15/15 PASS across unseeded runs. T5 0-misplaced, T15 6/6.
- GOTCHA: MapGen needs explicit java.util imports (java.awt.*+ java.util.*
  both export List). T14 flake was 5T-radius boundary (float <), not spawn logic.
