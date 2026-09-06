## 2026.36.0 — 2026-09-06

### ✨ Features
- report anonymous usage to bStats (`a9282fe`)
- in-game zone creation (`10e000d`)
- royalregen.bypass permission (`bf200fa`)
- crash-safe pending restores (`1210c73`)

### 🐛 Fixes
- restore blocks with shorter per-block regen times on schedule (`8e6e115`)

### 📝 Documentation
- state the Paper 26.2-or-newer requirement (`c227e93`)
- reattach the tree-heuristic javadoc to partOfTree (`e2c64af`)

## 2026.34.0 — 2026-08-21

### ✨ Features
- deny hanging entities and armour stands inside a zone (`fa2c53d`)
- fell a tree over several ticks, gated on a permission (`b6d16f3`)
- per-block regen-seconds, overriding the zone timer (`10c1c67`)
- world-scoped zones, require-leaves, and harvesting under a build deny (`d0bf166`)

### 🐛 Fixes
- follow a built trunk to its canopy instead of straight up (`065a83c`)

## 2026.32.0 — 2026-08-07

### ✨ Features
- ship all seven farming regions (`23fc2bf`)
- harvestable blocks that regenerate, in defined zones (`584e5f9`)

### 🐛 Fixes
- stop suppressing vanilla drops, so drop perks work in regen zones (`c6ead37`)
- refuse a break before anything can pay out for it (`833b56e`)
- ship example zones instead of one map's coordinates (`433e375`)
- leave the break event intact, and name zones (`f3303e6`)

## 2026.29.0 — 2026-07-19

### ✨ Features
- RoyalRegen — harvestable blocks that regenerate, in defined zones
