# RoyalRegen

Blocks in defined zones can be harvested and come back on a timer.

Built for hub farms — a map full of crops that players should be able to work but not dismantle — though
nothing about it is farming-specific. A zone is a cuboid and a list of blocks, so the same thing serves a
mine, a quarry, or a resource island.

Part of the Royal plugin suite, but standalone: no dependencies, no shared data.

---

## How it works with a protected world

Most maps like this run with a protection plugin denying build outright, which stops harvesting as well
as griefing. Inside a zone RoyalRegen takes over: it allows exactly the blocks you list and denies
everything else itself — breaking, placing, buckets, armour stands, item frames, trampling.

The block list *is* the permission. A player can break the wheat and nothing else, including the fence
beside it.

There are two ways to run it alongside a protection plugin, chosen by `override-protection`:

- **`false` (default):** open your protection for each zone's area (for example a WorldGuard region
  allowing `block-break`) and let RoyalRegen do the denying there. Harvests are never cancelled, so every
  plugin sees them, and anything another plugin cancels stays cancelled.
- **`true`:** leave protection denying everywhere. RoyalRegen un-cancels a break of a listed block after
  protection cancels it. It can't tell which plugin cancelled, so players can also break listed blocks
  that other plugins protect, such as island crops, claims and minion fields. A break an anti-cheat
  cancelled goes through as well, some jobs/skills setups may miss harvests (they skip cancelled breaks),
  and the protection plugin may still show its "can't break" message. This only applies to zones with
  `min` and `max`: whole-world zones ignore it, and RoyalRegen logs a warning naming each one on start-up
  and reload.

When a listed block is broken:

1. The break goes through as a normal break, so jobs, skills, collections and quests all see it
2. Vanilla drops are given, so Fortune and every drop perk on the server work — unless the block has a
   `drops:` override
3. Crops reset to a bare stem; other blocks become air. A cane, cactus or bamboo cut low has the whole
   stack above it recorded too
4. The original block is restored after `regen-seconds`

---

## Configuration

The shipped config contains two examples — a farm and a mine — both **disabled**, because a cuboid only
means something on the map it was measured on. Read your own corners off the F3 screen (or a WorldEdit
selection), set them, then enable the zone.

```yaml
zones:
  village-fields:
    enabled: true
    world: hub
    display-name: "&aVillage Fields"

    # Opposite corners, inclusive. Order does not matter — they are sorted on load.
    # Leave both out and the zone covers the whole world.
    min: {x: 0,   y: 60,  z: 0}
    max: {x: 100, y: 120, z: 100}

    regen-seconds: 45

    blocks:
      minecraft:wheat: {}
      minecraft:carrots: {}
      minecraft:sugar_cane:
        regen-seconds: 90
```

Block names work with or without the `minecraft:` namespace. Per-block options:

| Option | Meaning |
|---|---|
| `require-mature` | Refuse a crop that hasn't finished growing. On by default for crops, off for everything else — sugar cane, cactus and bamboo have an age, but it isn't ripeness. |
| `regen-seconds` | This block's own timer instead of the zone's. Decimals are fine. |
| `drops` | Replace the vanilla drops, e.g. `[WHEAT:1, WHEAT_SEEDS:1]`. Leave it out almost always — see the note on economy. |
| `require-leaves` | Logs: refuse one that isn't part of a living tree, so trees are harvestable and a wall of the same log isn't. |
| `fell` | Logs: breaking one brings the connected tree down, a few logs per tick, within the zone. Needs `royalregen.fell` (configurable under `felling:`). |
| `placeholder` | What stands in the block's place until it comes back, e.g. `bedrock` in a mine. Can also be set once per zone; `none` turns it off for one block. Crops ignore it and regrow from a seedling. The placeholder can't be broken. |
| `brush` | Suspicious sand and gravel: what a finished brush pays out — see [Archaeology](#archaeology). |

Each zone can also announce itself when a player walks, rides or teleports in — a title on the first
visit, the display name after that. Set `discovery.enabled: false` to turn that off. A zone without its own
`display-name`, `discovery.title` or `discovery.subtitle` uses `zone-defaults` in `messages.yml`.

Give zones a generous vertical range if the terrain is uneven; fields terraced down a hillside will
otherwise lose their lower rows.

Nothing here is farming-specific. Swap the block list for ores and a longer `regen-seconds` and the same
zone is a public mine.

## Archaeology

Brushing a suspicious block isn't a break, so it needs its own section. When a player finishes brushing a
listed block, RoyalRegen rolls the block's `drops`, runs their console commands, stands the placeholder
where the block was, and brings the original back after `regen-seconds`.

```yaml
blocks:
  minecraft:suspicious_gravel:
    regen-seconds: 300
    brush:
      enabled: true
      replace-vanilla-loot: true       # drop any vanilla item the brush would pop out (default true)
      tools:                           # percentage points added to every `bonus: true` drop
        minecraft:brush: 0
        ecoitems:archaeologists_brush: 1
      drops:                           # each rolled on its own
        - chance: 4                    # percent
          bonus: true
          commands: ["ecoitems give %player% fossil_fragment 1"]
      rare-event:                      # rolled once per finished brush
        chance: 0.2
        commands: ["ecomobs spawn buried_sentinel %x% %above_y% %z% %world%"]
```

- **Chance and bonus.** A drop's chance is `chance`, plus the held tool's bonus when the drop says
  `bonus: true`, capped at 100. A tool that isn't listed adds nothing.
- **Tool ids.** An EcoItem is `ecoitems:<id>`, read from EcoItems' own `ecoitems:item` tag; anything else
  is the item's key, such as `minecraft:brush`. Other plugins also tag vanilla items, so only EcoItems'
  tag counts.
- **Placeholders** in commands: `%player% %uuid% %x% %y% %z% %above_y% %world% %tool%`. The coordinates are
  the brushed block, which turns into the placeholder — spawn a mob at `%above_y%`, not `%y%`.
- **Regenerated blocks have no loot table**, so a brush gives nothing on its own; the table is the whole
  reward. `replace-vanilla-loot` only matters for blocks generated with one.
- Creative players and `royalregen.bypass` holders brush normally and trigger nothing, as with breaking.
- Suspicious sand and gravel work the same way; a block not listed, or outside a zone, is untouched.

Brushing is **not** revived past protection the way a harvest is. It needs `override-protection: false`
and the zone's area opened up: with WorldGuard, give the region `block-break`, `block-place` and
`interact` set to `allow`. Every brush stroke swaps the block for its next dust stage, which WorldGuard
checks as a break plus a place, and starting to brush is a right-click on the block.

## Commands

```text
/royalregen status                        Zones loaded and blocks waiting to return
/royalregen reload                        Re-read zones and messages
/royalregen pos1 | pos2                   Set a zone's corners where you stand
/royalregen create <id> [regen-seconds]   Write a zone from the corners, seeded with the block you're looking at
```

Aliases: `/rregen`, `/regen`.

## Permissions

```text
royalregen.admin    default: op      Every /royalregen command
royalregen.bypass   default: op      Edit a zone's blocks without switching to creative
royalregen.fell     default: false   Fell whole trees on logs with fell: true
```

---

## Behaviour worth knowing

**Soil and leaves stay put inside zones.** Trampling (by players or mobs), farmland drying out, and leaf
decay are all cancelled. None of them is a block break, so no amount of regeneration would bring back a
field or a tree canopy they had destroyed.

**Pending blocks are restored on shutdown**, so a restart never leaves a field full of bare stems. They
are also saved every few seconds, so a crash doesn't lose them either.

**An unloaded chunk or world defers a restore rather than losing it.** The block comes back once the
chunk (or a world loaded later by another plugin) is there, instead of staying broken forever.

**Creative-mode breaks are ignored** — that's building, not farming. `royalregen.bypass` does the same
without the gamemode switch.

**A block already waiting to return can't be harvested again**, so a crop can't be farmed twice in one
regen cycle.

**Crops don't need `randomTickSpeed`.** Regrowth is the plugin's timer, not the world's — which means a
decorative map can stay frozen (`randomTickSpeed 0`) and still have working farms.

---

## A note on economy

A regen zone is an **infinite source**: every block comes back. If a shop buys what it produces at a
fixed minimum price, players have unlimited income at that price, and no amount of dynamic pricing fixes
it — the floor is the floor.

The throttle is `regen-seconds` (how fast it returns). Set it against what the item actually sells for.

Don't throttle with `drops`. Overriding drops stops `BlockDropItemEvent` firing, which is what every drop
perk reads — Fortune, reforge and talisman multipliers, telekinesis. They go silently inert in the zone,
with nothing in any log to say why. A large zone is not itself the problem either: a field regenerating
faster than anyone can harvest is capped by clicking speed, not size.

---

## Metrics

Reports anonymous usage to [bStats](https://bstats.org/plugin/bukkit/RoyalRegen/33889). Turn it off for
the whole server in `plugins/bStats/config.yml`.

---

## Building

```bash
mvn clean package     # target/RoyalRegen.jar
```

Requires Paper 26.2 or newer to run, and JDK 25 to build (paper-api 26.2 ships Java 25 bytecode).
