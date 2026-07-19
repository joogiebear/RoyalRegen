# RoyalRegen

Blocks in defined zones can be harvested and come back on a timer.

Built for hub farms — a map full of crops that players should be able to work but not dismantle — though
nothing about it is farming-specific. A zone is a cuboid and a list of blocks, so the same thing serves a
mine, a quarry, or a resource island.

Part of the Royal plugin suite, but standalone: no dependencies, no shared data.

---

## How it works with a protected world

Most maps like this run with a protection plugin denying build outright, which stops harvesting as well
as griefing. RoyalRegen listens **after** that plugin and allows exactly the blocks you list, in exactly
the zones you define. Everything else stays denied.

The block list *is* the permission. There's no "allow build here" flag to leak — a player can break the
wheat and nothing else, including the fence beside it.

When a listed block is broken:

1. The event stays cancelled — vanilla never breaks the block
2. The configured drops are given (not the block's own drops)
3. Crops reset to age 0, other blocks become air
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

    # Opposite corners, inclusive. Order does not matter — they are sorted on load.
    min: {x: 0,   y: 60,  z: 0}
    max: {x: 100, y: 120, z: 100}

    regen-seconds: 45

    blocks:
      minecraft:wheat:
        drops: [WHEAT:1, WHEAT_SEEDS:1]
        require-mature: true
      minecraft:carrots:
        drops: [CARROT:2]
        require-mature: true
```

Block names work with or without the `minecraft:` namespace. `require-mature` refuses crops that haven't
finished growing, and only means anything for crops. `drops` is what the player receives — leave it empty
and a harvest gives nothing, which is occasionally what you want.

Give zones a generous vertical range if the terrain is uneven; fields terraced down a hillside will
otherwise lose their lower rows.

Nothing here is farming-specific. Swap the block list for ores and a longer `regen-seconds` and the same
zone is a public mine.

## Commands

```text
/royalregen status     Zones loaded and blocks waiting to return
/royalregen reload     Re-read zones and messages
```

Aliases: `/rregen`, `/regen`.

## Permissions

```text
royalregen.admin   default: op
```

---

## Behaviour worth knowing

**Trampling is blocked inside zones.** Farmland turning to dirt arrives as a `PHYSICAL` interact, not a
block break, so no amount of block regeneration catches it — the soil is simply gone. Zones cancel it,
which is what stops a field being ruined by people walking over it.

**Pending blocks are restored on shutdown**, so a restart never leaves a field full of bare stems.

**An unloaded chunk defers a restore rather than losing it.** The block comes back the next time the
chunk is loaded instead of staying broken forever.

**Creative-mode breaks are ignored** — that's building, not farming.

**A block already waiting to return can't be harvested again**, so a crop can't be farmed twice in one
regen cycle.

**Crops don't need `randomTickSpeed`.** Regrowth is the plugin's timer, not the world's — which means a
decorative map can stay frozen (`randomTickSpeed 0`) and still have working farms.

---

## A note on economy

A regen zone is an **infinite source**: every block comes back. If a shop buys what it produces at a
fixed minimum price, players have unlimited income at that price, and no amount of dynamic pricing fixes
it — the floor is the floor.

The throttles are `drops` (what one harvest is worth) and `regen-seconds` (how fast it returns). Set them
against what the item actually sells for. A large zone is not itself the problem; a generous drop table
on a well-priced crop is.

---

## Building

```bash
mvn clean package     # target/RoyalRegen.jar
```

Requires JDK 25 to build (paper-api 26.2 ships Java 25 bytecode); runs on Java 21+.
