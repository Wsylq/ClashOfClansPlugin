# Changelog

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