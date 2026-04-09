# Changelog

## [1.1.0] - 2026-04-10

### Added
- `MortarStats` — single source of truth for all mortar constants: min range 4, max range 11, splash radius 1.5, cooldown 100 ticks, damage per level (40/50/60)
- `MortarConfig` — reads `mortar-system` section from `config.yml`; falls back to defaults with warnings; mirrors `ArcherConfig` exactly
- `MortarAI` — targeting logic with 4-tile blind spot enforcement, Snowball projectile launch, AOE splash damage on landing; pure static helpers `indexOfNearestInRange`, `isInEffectiveRange`, `isInSplashRadius` for testability
- `MortarManager` — defense tick loop mirroring cannon/archer tower pattern; wired into `ClashPlugin` scheduler at 20-tick interval
- `MortarSystemPropertyTest` — jqwik property tests covering config round-trip, blind spot exclusion, and splash radius accuracy
- Mortar added to test attack base at position `{0, 10}` with 5-second fire cooldown
- `mortar-system` block added to `config.yml` (`damage: 40`, `cooldown-ticks: 100`, `min-range: 4`, `max-range: 11`)
- `mortar: 400` HP added to `barbarian-system.building-hp` in `config.yml`

### Changed
- `BuildingType` — added `MORTAR`
- `FootprintRegistry` — 3×3 footprint for MORTAR
- `VillageRenderer.blockFor` — `GRAY_CONCRETE` for MORTAR
- `BalanceBook` — MORTAR unlocked at TH1, gold cost 400, 25s build time
- `BarbConfig` — MORTAR HP default 400 added to `DEFAULT_HP` and `KEY_TO_TYPE`
- `VillageManager` — MORTAR slot at `{0, -26}`, `placeMortar` method (stone brick base + cauldron), map overview color `#636E72`, `getPlayerSlotOverrides` accessor added
- `ClashPlugin` — instantiates `MortarConfig` and `MortarManager`, schedules `tickMortarDefense` every 20 ticks
- `AttackSession` — added `ownsRegistry` flag to distinguish solo vs shared attack sessions
- `BarbManager.endSession` — only clears the active registry when `ownsRegistry` is true (solo attack); shared `/clash attack` registry stays alive so defenses keep firing after barbs die
- `ArcherManager.endSession` — same fix as BarbManager
- `TestBaseManager` — mortar fires every 5 seconds (cooldown counter); defense arrows set to `setDamage(0)` so they are visual-only and cannot hit unintended NPCs

### Fixed
- Mortar targeting players in own village — changed target filter from `LivingEntity` to `Monster` in both `MortarAI` and `MortarManager`, matching cannon/archer tower pattern
- Cannon and mortar stopping after barbarians die in shared attack — `endSession` in `BarbManager`/`ArcherManager` no longer nulls the shared registry; defenses continue firing at remaining troops
- Archer arrows dealing friendly fire damage to barbarians — `ArcherAI` visual arrow now has `setDamage(0)` and `DISALLOWED` pickup status
- Test base defense arrows hitting unintended NPCs — `shootDefense` arrow set to `setDamage(0)`; all damage applied directly to the intended target only

## [1.0.0] - 2026-04-09

### Added
- `/clash edit` command — enter base edit mode: fly above village, pick up/move/place/remove buildings in real time
- `/clash edit exit` — exit edit mode and save layout
- `/clash edit select <type>` — select a building type for new placement
- `/clash edit remove` — remove building under cursor
- `grid.model` package: `GridCoord`, `Footprint`, `PlacedBuilding` value types
- `grid.occupancy` package: `FootprintRegistry` (single source of footprint definitions), `OccupancyMap` (64×64 grid, single source of truth for layout)
- `grid.placement` package: `PlacementSession` (per-player edit state, IDLE/PLACING/MOVING/WALL_DRAW modes), `GhostPreview` (PacketEvents fake block packets, lime/red stained glass), `WallDrawSession` (Bresenham wall drag)
- `grid.renderer` package: `VillageRenderer` (flat single-block grid view during edit, 3D re-render on exit)
- `grid.persistence` package: `LayoutSerializer` (JSON save/load at `plugins/clash/layouts/<uuid>.json`)
- `grid.command` package: `EditCommand`
- Layout JSON persisted on exit; loaded and slot overrides rebuilt on every login so building positions survive server restarts
- Per-player slot overrides in `VillageManager` — cannon defense, archer tower defense, troop visuals, and army camp all use new positions after edit
- Property tests: cursor snapping (Property 1), `EditCommand` guard logic (3 unit tests), `PlacementSession` guard logic (3 unit tests)

### Changed
- `VillageManager.renderVillage` suppressed while player is in edit mode to prevent 3D structures overwriting the flat edit view
- `VillageManager.tickCannonDefense` and `tickArcherTowerDefense` use per-player slot overrides
- `VillageManager.spawnTroopVisuals` and idle archer spawn use per-player slot overrides for army camp position
- `VillageBuildingRegistry` uses per-player slot overrides when building the attack registry
- `PlayerJoinListener` rebuilds slot overrides from saved layout before `setupVillageForPlayer` runs
- `ClashCommand` routes `edit` sub-command to `EditCommand`
- `ClashPlugin` registers `PlayerQuitEvent`, `PlayerInteractEvent`, `PlayerToggleSneakEvent` listeners for edit mode

### Fixed
- Ghost preview blocks appearing in wrong world position — `GhostPreview` now applies grid origin offset (`-32, -32`) when sending PacketEvents block-change packets
- Buildings rendering at wrong position after move — `applyLayoutToVillageData` now converts NW-corner anchor to building centre before passing to `VillageManager`
- Buildings reverting to original positions on rejoin — slot overrides now rebuilt from layout JSON on every login
- Residue blocks left at old building location after move — `clearPlayableArea` now uses a full square clear covering the entire scenery area
- Archers wandering at old army camp position — idle archer spawn uses per-player slot overrides

## [0.9.0] - 2026-04-08

### Added
- `ArcherConfig` — reads `archer-system` section from `config.yml`; falls back to defaults (`archer-damage: 8`, `attack-interval-ticks: 25`, `attack-range-blocks: 10`, `draw-ticks: 10`) with warnings; pure-Java testable
- `ArcherAI` — per-NPC state machine (`IDLE → SEEKING → DRAWING → ATTACKING`) with tick-based movement at 0.45 b/t, bow-draw animation via PacketEvents entity metadata (index 8), arrow projectile on fire, `BuildingRegistry.damageBuilding` damage, target re-selection on destruction, NPC null guard, and pure static `indexOfNearest` helper
- `ArcherManager` — full session management mirroring `BarbManager`: `ARCHER_MAX_HP = 65`, `ARCHER_HEAD_LORE_TAG`, `startAttackSession`, `joinAttackSession`, `deployOneFromSession`, `endSession`, `getSession`, `isValidDeployCount`, `createArcherHeadItem`, `isArcherHeadItem`, `spawnIdleArchers`, `despawnIdleArchers`, `onArcherDespawn`, `clear`
- `ArcherSystemPropertyTest` — 9 property-based tests (jqwik) covering config round-trip, fallback defaults, `indexOfNearest` correctness, and `isValidDeployCount` predicate
- PacketEvents dependency (`com.github.retrooper:packetevents-spigot:2.6.0`) for sending entity metadata and rotation packets without NMS
- `archer-system` block added to `config.yml`
- `packetevents` added as soft dependency in `plugin.yml`

### Changed
- `/clash attack` now creates one shared `TestBaseRegistry` and enlists all trained troop types (barbarians + archers) — both troop head items are given simultaneously; `BarbManager` and `ArcherManager` each gained `joinAttackSession(Player, TestBaseRegistry)` for this
- `ClashPlugin` exposes `getArcherManager()` and wires `ArcherManager` into `VillageManager` via `setArcherManager()`
- `AttackListener` extended with `else if (ArcherManager.isArcherHeadItem(item))` branch routing to `archerManager.deployOneFromSession`
- `VillageManager.spawnTroopVisuals` now skips `TroopType.ARCHER` in the generic NPC loop (ArcherManager handles archer idle visuals separately) and is guarded to only run in the player's own village world (not the test base)
- Idle archer NPCs now wander randomly within army camp bounds every 3 seconds, matching barbarian idle behaviour; nameplate hidden and collision disabled
- `ArcherAI` facing: uses PacketEvents `WrapperPlayServerEntityRotation` + `WrapperPlayServerEntityHeadLook` packets every tick during DRAWING/ATTACKING so the archer correctly faces the target building (yaw + pitch)
- Bow-draw animation cycle: DRAWING sends `handActive=true` metadata each tick; on fire, sends `handActive=false` (release frame) then transitions back to DRAWING — produces visible draw → release → draw loop

### Fixed
- `NullPointerException` in `SkinTrait.setSkinPersistent` — Citizens requires a non-null signature; switched idle/deployed archer NPCs to `setSkinName` (async Mojang lookup)
- 4 idle archer NPCs spawning instead of 2 — archers were being added to both the generic `troopAssignments` loop and `ArcherManager.spawnIdleArchers`; fixed by excluding `TroopType.ARCHER` from the generic loop
- Archer deploy head showing no skin — `createArcherHeadItem` was using a fake texture hash; now uses a real Minecraft texture URL
- Bow-draw animation stuck in drawn state — `sendHandActiveMetadata(false)` now sent on each fire before transitioning back to DRAWING
- Archer not facing target — replaced `entity.teleport()` yaw-only approach (overridden by Citizens) with PacketEvents rotation packets including correct pitch calculation

---

## [0.8.0] - 2026-04-07

### Added
- `HealthBarManager` — floating health bars above buildings and troops using invisible ArmorStand entities; bars decay and hide after 60 ticks of no damage
- `HealthBarRenderer` — pure utility class for colored block-character bars (`█`/`░`) with green/yellow/red thresholds
- `HealthBarConfig` — reads `health-bar` section from `config.yml`; falls back to defaults (`bar-length: 10`, `decay-delay-ticks: 60`, `display-interval-ticks: 2`) with warnings
- `EntityDamageEvent` handler in `AttackListener` — updates NPC health bars when defense towers deal damage

### Fixed
- ArmorStand label showing "Armor Stand" instead of health bar — switched from legacy `setCustomName(String)` to Adventure `customName(Component)` API required by Paper 1.21
- Health bars stacking from previous sessions — `sessionId` now threaded through all `register()` calls so `clearSession()` correctly removes all stands on session end
- Decay hiding showing "Armor Stand" label — changed from setting name to `""` to `setCustomNameVisible(false)`
- Buildings showing no health bar — `TestBaseRegistry.setHealthBarManager()` was never called; fixed in `BarbManager.startAttackSession()`
- Health bar position too high on troops — reduced Y offset from `2.5` to `2.1` blocks above feet

---

## [0.7.0] - 2026-04-07

### Added
- `TestBaseManager` — creates and manages a shared `coc_testbase` flat world with a pre-built enemy base (Town Hall, Cannon, Archer Tower, Army Camp)
- `TestBaseRegistry` — `BuildingRegistry` implementation backed by the static test base; tracks per-building HP in-memory per attack session; exposes `isBuildingAlive(type)` for defense checks
- `AttackSession` — tracks one player's active raid: remaining deployable barbarians, the live registry, and deployed NPC ids
- `AttackListener` — `PlayerInteractEvent` listener; right-clicking the Barbarian Head item deploys exactly one barbarian at the player's feet; blocks deployment outside `coc_testbase`
- `/clash attack` subcommand — starts an attack session from anywhere (including own village), teleports player to test base, deducts trained barbarians, gives Barbarian Head item with count = army size
- Barbarian Head deploy item — `PLAYER_HEAD` with custom lore tag as identifier; item count reflects remaining deployable barbarians and ticks down on each deploy
- Self-attack prevention on `/barbarian deploy` (admin command) — checks world owner via `VillageManager.getWorldOwner()` and blocks if attacker is in their own village
- Defense system for test base — cannon and archer tower fire arrows at barbarian NPCs every second; cannon range 16 blocks / 6 dmg, archer range 12 blocks / 3 dmg; both stop firing when their building is destroyed
- Defeat condition — session ends with "Defeat!" when all deployed barbarians are dead and none remain to deploy
- Victory condition — session ends with "Victory!" when all test base buildings are destroyed
- `VillageManager.getWorldOwner(World)` — public method to resolve which player owns a given village world
- `finishNow("training")` now calls `spawnTroopVisuals` immediately so barbarians appear around the army camp right after instant-finish

### Changed
- `BarbAI` movement completely rewritten — replaced Citizens2 navigator (unreliable for static block targets on flat worlds) with incremental teleportation every 2 ticks at 0.45 blocks/tick; barbarians now reliably walk to and attack every building in sequence
- `BarbAI.selectTarget()` and SEEKING proximity check now use horizontal-only distance math instead of Bukkit `distance()`/`distanceSquared()` to avoid `IllegalArgumentException` when NPC and building are in different worlds
- `BarbAI` post-kill re-target: after destroying a building, immediately selects next nearest and transitions back to SEEKING
- `BarbManager` NPCs spawned with `npc.setProtected(false)` — required for external teleportation to work
- `BarbManager` constructor starts a repeating session monitor task (every second) for victory/defeat detection
- `ClashPlugin` wires up `TestBaseManager`, `AttackListener`, and passes `testBaseManager` to `BarbManager`
- `TestBaseManager.tickDefense` checks `activeRegistries` to skip shooting from already-destroyed buildings

### Fixed
- `IllegalArgumentException: Cannot measure distance between coc_* and coc_testbase` — caused by Citizens2 NPC in test base world trying to measure distance to building origins registered in a different world object
- Barbarians standing idle after destroying first building — Citizens2 navigator silently fails to pathfind to static block coordinates; fixed by switching to teleport-based movement
- Cannon continuing to shoot after being destroyed — defense tick now checks `registry.isBuildingAlive()` before firing
- Session ending immediately after all barbarians deployed — removed premature `endSession` call; session now only ends on victory or defeat
- Troop visuals not appearing after `/clash finish training` — `finishNow` now triggers `spawnTroopVisuals` after adding troops

---

## [0.6.0] - 2026-04-07

### Added
- `BuildingRegistry` interface — abstraction over any set of attackable buildings, decoupling BarbAI from concrete building sources
- `VillageBuildingRegistry` — implements `BuildingRegistry` against a live player village, deriving `BuildingInstance` locations from `VillageManager.BUILDING_SLOTS` at raid time
- `VillageManager.findNearestRegistry()` — finds the village world the attacker is standing in and returns a registry for it
- `VillageManager.getBuildingSlots()` — public static accessor for the building slot layout map

### Changed
- Barbarian AI (`BarbAI`) now targets buildings from any `BuildingRegistry`, not just a hardcoded test base
- `/barbarian deploy <count>` now attacks real player village buildings in the world the attacker is standing in
- `BarbManager` wired to `VillageManager` instead of `TestBaseManager`
- Property 3 test now uses `BuildingRegistry.applyDamage` (the canonical static helper)

### Removed
- `TestBaseManager` — replaced by `VillageBuildingRegistry`
- `TestBase` model record — no longer needed
- `TestBaseCommand` and `/testbase` command — hardcoded test base spawning removed
- `BarbManager.removeBarbsForBase` and `barbToBase` tracking — simplified since registry is per-deploy, not per-base
- Property 1 and Property 6 tests — were specific to the removed TestBase layout

### TODO
- Prevent barbarians from being deployed into the attacker's own village — deployer should only be able to attack another player's village, not their own

---

## [0.5.0] - 2026-04-07

### Added
- Citizens NPC troop visuals spawned at Army Camp footprint, reflecting current trained troop count (capped at `ARMY_CAP_PER_CAMP` per camp)
- NPCs wander randomly within the army camp bounds via navigator scheduler
- Barbarian troop visuals display the correct custom skin via Citizens `SkinTrait.setSkinPersistent` with mineskin texture + RSA signature
- Citizens added as a `softdepend` — plugin loads with or without it

### Fixed
- Overview mode (`/clash overview`) now enables flight on open and restores the player's original `allowFlight` and `flying` state on exit, preventing fall damage
- Troop visuals now refresh immediately when training completes, not only on next `renderVillage` call



## [0.4.0] - 2026-04-05

### Added
- Private per-player Clash village worlds (`coc_<uuid>`) on first join.
- Flat world generation for village worlds.
- Starter village initialization with Town Hall and core starter buildings.
- Persistent village data (`players.yml`) for TH level, buildings, resources, gems.
- Construction system with timers.
- Construction visuals:
  - Builder NPC (villager) at active construction.
  - Fence perimeter on construction tile.
  - Hologram-style floating text with building name + countdown (d h m s).
- Builder workforce logic:
  - Free/busy builder tracking.
  - Builder availability checks before starting construction.
- Scoreboard lines for:
  - Gold
  - Elixir
  - Gems
  - Builders available/busy
- Village boundaries and protection:
  - No block place/break in village gameplay zones.
  - Movement limits to keep players inside playable area.
- Decorative obstacle/scenery generation around village.
- TH requirement validation for upgrades (TH0 → TH1, TH1 → TH2).
- TAB menu requirement display:
  - Current TH and next TH requirements.
  - Per-requirement progress (have/needed).
  - Level requirement progress.
- `/clash build` tab-completion now prioritizes missing required buildings.

### Changed
- Safe spawn handling to prevent spawning inside structures.
- Teleport target adjusted to non-suffocating village position.
- Building placement/rendering made slot-based and deterministic.
- Resource/economy logic expanded for gem-based builder hut progression.

### Fixed
- Compilation issue in `VillageManager` (`job.key()` → `job.key`).
- Archer Tower projectile spawning inside tower blocks.
- Archer Tower targeting logic improved to reduce self-collision.
- Cannon behavior updated toward functional targeting/fire loop.
- Construction completion now pushes/relocates players out of footprint to prevent suffocation.

### Notes
- Archer Tower and Cannon now attack entities, but balancing/tuning is still ongoing.
- Existing generated village worlds may need deletion for clean regeneration after major world/layout updates.

### Requested But Not Fully Implemented Yet (Open Scope)
- Wall system
- Laboratory
- Gold Storage and Elixir Storage
- Resource cap enforcement and overflow protection
- Troop training via Barracks
- Troop types (Barbarian, Archer, Giant, etc.)
- Obstacle removal for gems
- Instant-build with gems
- Manual resource collect interaction (tap/collect style)
- Building info GUI on right-click (HP, level, upgrade cost, etc.)