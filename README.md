# CoC Plugin — TH5 TODO List

## 🏗️ Buildings

### Defenses
- [ ] Mortar (1x, max lvl 3)
- [ ] Air Defense (1x, max lvl 3)
- [ ] Wizard Tower (1x, max lvl 3) ⭐ signature TH5 unlock
- [ ] Add 2nd and 3rd Cannon instance
- [ ] Add 2nd and 3rd Archer Tower instance
- [ ] Cap building upgrade levels per TH level

### Traps
- [ ] Bomb (4x)
- [ ] Spring Trap (2x)
- [ ] Air Bomb (2x)

### Economy & Production
- [ ] Add up to 5x Gold Mine instances (max lvl 10)
- [ ] Add up to 5x Elixir Collector instances (max lvl 10)
- [ ] Add 2nd Gold Storage instance (max lvl 9)
- [ ] Add 2nd Elixir Storage instance (max lvl 9)
- [ ] Enforce resource storage caps per TH level

### Army
- [ ] Add up to 3x Army Camp instances (max lvl 5, 135 total capacity)
- [ ] Add up to 3x Barracks instances (max lvl 7)
- [ ] Enforce total army capacity (135 troops at TH5)

### Other
- [ ] Clan Castle (1x, lvl 2)
- [ ] Spell Factory (1x, lvl 1)
- [ ] Make Laboratory actually research and upgrade troop stats (lvl 3)
- [ ] Up to 100 Wall pieces (max lvl 5)

---

## 🪖 Troops

- [ ] Finish Giant AI (stub exists, needs full implementation)
- [ ] Goblin (lvl 3) — targets resources first
- [ ] Wall Breaker (lvl 2) — targets walls on shortest path
- [ ] Balloon (lvl 2) — flies, targets defenses
- [ ] Wizard (lvl 2) — splash damage ⭐ signature TH5 troop
- [ ] Add troop level system (stats scale per level)
- [ ] Enforce per-barracks training queue with real timers
- [ ] Enforce army camp capacity during training

---

## ✨ Spells

- [ ] Implement Spell Factory building logic
- [ ] Lightning Spell (lvl 4 at TH5)
- [ ] Spell housing capacity (1 slot at TH5)

---

## ⚔️ Attack System

- [ ] 3-minute attack timer
- [ ] Star rating system (1★ = 50% destruction, 2★ = TH destroyed, 3★ = 100%)
- [ ] Wall interaction — troops path around walls (A* on grid)
- [ ] Wall Breaker targets walls blocking shortest path
- [ ] Loot calculation and transfer from defender to attacker
- [ ] Shield system after being attacked
- [ ] Real player vs player attacks (load defender's village layout)

---

## 🗺️ Base Building (Free-Form Grid)

- [ ] 44x44 tile occupancy grid (`HashMap<GridCoord, PlacedBuilding>`)
- [ ] Ghost preview on crosshair (fake block packets via PacketEvents)
- [ ] Green/red preview based on valid/invalid placement
- [ ] Left-click to confirm, Sneak to cancel
- [ ] Wall draw mode (click-drag to chain walls)
- [ ] Move building command (no builder consumed)
- [ ] Save layout as JSON per player
- [ ] Load and regenerate village from JSON on login
- [ ] Generate defender's village from JSON in attack world

---

## 👥 Social / Clan

- [ ] Clan creation and joining
- [ ] Clan Castle troop donations
- [ ] Clan chat channel

---

## 🖥️ UI / QoL

- [ ] Building upgrade menu (right-click GUI)
- [ ] Troop training menu (right-click Barracks GUI)
- [ ] Star rating screen after attack ends
- [ ] Trophy / league tracking (Bronze League at TH5)
- [ ] Dark Elixir resource (not needed until TH7 but plan for it)