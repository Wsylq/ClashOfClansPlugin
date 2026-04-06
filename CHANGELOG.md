# Changelog

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