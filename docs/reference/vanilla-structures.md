# Controlling Vanilla & Datapack Structures

How to turn vanilla (and datapack) structures on or off per dimension.

## Where it lives

Add a `vanillaStructures` block to a **dimension** file (`packs/<pack>/dimensions/<dim>.json`). It is backed by `IrisVanillaStructureControl` and has three fields:

| Field | Type | Meaning |
|-------|------|---------|
| `mode` | `ALL_ON` \| `ALL_OFF` \| `CUSTOM` | Master toggle. Default `ALL_ON`. |
| `disabled` | list of structure keys | Keys to turn **off** while `mode = ALL_ON`. Ignored otherwise. |
| `enabled` | list of structure keys | Keys to turn **on** while `mode = ALL_OFF` / `CUSTOM`. Ignored otherwise. |

If you omit `vanillaStructures` entirely, the dimension behaves as `ALL_ON` with nothing disabled (every vanilla/datapack structure generates).

## Blacklist a few (keep everything else)

```json
"vanillaStructures": {
  "mode": "ALL_ON",
  "disabled": [
    "minecraft:stronghold",
    "minecraft:mansion",
    "minecraft:trial_chambers"
  ]
}
```

Everything generates except those three.

## Whitelist a few (turn everything else off)

```json
"vanillaStructures": {
  "mode": "ALL_OFF",
  "enabled": [
    "minecraft:village_plains",
    "minecraft:ancient_city"
  ]
}
```

Only those generate. (`CUSTOM` behaves identically to `ALL_OFF`.)

## Key matching: exact OR prefix

An entry matches a structure when the structure key **equals** the entry **or starts with** it. This lets a partial key cover a whole family:

| Entry | Matches |
|-------|---------|
| `minecraft:village_plains` | only the plains village |
| `minecraft:village` | every village variant (`village_plains`, `village_desert`, `village_savanna`, `village_snowy`, `village_taiga`) |
| `minecraft:ruined_portal` | every ruined-portal variant |
| `minecraft:` | every vanilla structure (rarely useful — prefer `mode: ALL_OFF`) |

## Finding the keys

Both `disabled` and `enabled` have editor autocomplete (the `@RegistryListVanillaStructure` schema hint). To dump the full live list (vanilla + any installed datapacks) into `packs/<pack>/structures/structure-index.json`:

```
/iris structure list <dimension>
```

## Notes & gotchas

- **Only affects newly generated chunks.** Already-generated terrain keeps whatever structures it has — explore fresh chunks or regenerate to see changes.
- **Some structures are gated by biome.** Disabling is always honored, but *enabling* a structure (whitelist mode) still requires its biome to exist for it to actually place — e.g. an ocean monument needs deep ocean.
- **This is native vanilla control, separate from imported structures.** Structures you import (`/iris structure import-all`, `import-templates`) and place yourself via a biome/region/dimension `structures` placement (route `IRIS_PLACED`) are controlled by *that* placement, not by `vanillaStructures`. Disabling `minecraft:ancient_city` here does not affect an imported `minecraft_ancient_city` you placed via `IRIS_PLACED`.
- **`mode` only flips which list is read.** `disabled` is consulted only under `ALL_ON`; `enabled` only under `ALL_OFF`/`CUSTOM`. The unused list is ignored, so it's safe to keep both populated while you toggle `mode`.
