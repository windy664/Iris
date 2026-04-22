# Floating Island Shard Domain Warp Implementation Plan

Created: 2026-04-22
Status: VERIFIED
Approved: Yes
Iterations: 0
Worktree: No
Type: Feature

## Summary

**Goal:** In the Iris overworld pack (primary testbed: `roughplains.json`), make floating island shards read as less "mathy / sharply vertical" by enabling the already-wired per-layer domain warp on the two `floatingChildBiomes` entries.

**Architecture:** `IrisFloatingChildBiomes` already defines `wallWarpStyle` + `wallWarpAmplitude` fields and exposes them through `getWallWarpCng(...)`. `FloatingIslandSample.sample(...)` already consumes them at `Iris/core/.../FloatingIslandSample.java:220-254` — for each Y layer of an island column, it offsets the footprint XZ sample by a signed 3D noise value before re-testing against `footprintThreshold`. This gives organic bulge/recession on the side walls without touching the tops (driven by `topShapeMode=BIOME`) or the bottoms (driven by `bottomStyle`). The current pack doesn't set `wallWarpStyle`, so `useWarp == false` and walls are a straight extrusion of the 2D footprint.

**Tech Stack:** Iris pack JSON (no Java changes).

---

## Scope

### In Scope

- Edit `[Minecraft Server]/consumers/plugin-consumers/shared-plugin-data/iris/packs/overworld/biomes/temperate/roughplains.json`.
- Add `wallWarpStyle` and `wallWarpAmplitude` to **both** entries in `floatingChildBiomes` (`mushroom/forest` and `tropical/wilds`).
- Chosen values (answers to Q1/Q2/Q3): `wallWarpStyle = {"style":"SIMPLEX","zoom":0.25}`, `wallWarpAmplitude = 10.0`. Identical on both entries.

### Out of Scope

- Java source changes inside `Iris/core` — the warp is already wired. No `FloatingIslandSample.java` or `IrisFloatingChildBiomes.java` edits.
- Changing the schema default for `wallWarpStyle` from `null` to a non-null value — would silently change behavior for every existing pack; user explicitly chose "pack only".
- Audit of other biomes — Grep confirms `floatingChildBiomes` only appears in `roughplains.json` across the overworld pack.
- Any change to `topShapeMode`, `bottomStyle`, `carveStyle`, or `footprintStyle` (tops and bottoms already look correct).
- Code quality improvements to the warp algorithm (independent X/Z seeds, multi-octave / iq-style warp-the-warp, vertical frequency multiplier) — user declined the "code-side improvement too" option.
- `TotalFixes.md` entry — repo policy (AGENTS.md) says append to existing sections; will be handled during implementation as part of the DoD if the repo conventions require it.

---

## Approach

**Chosen:** Pack-only enablement of the existing wall-warp feature.

**Why:** All the plumbing already exists; the missing link is pack configuration. Zero code changes means zero risk of regressing any other floating-island behavior. The user owns this test pack, so they can tune values in follow-up without a code round-trip.

**Alternatives considered:**

- **Flip schema default of `wallWarpStyle` to a gentle SIMPLEX.** Rejected — silently changes behavior for every existing pack/user, and the point of having the feature opt-in is that visual style is a pack-authored choice.
- **Rewrite the warp in `FloatingIslandSample` for higher fidelity** (iq-style warp-the-warp, independent X/Z noise hashes, per-axis frequency scaling). Rejected — the current single-pass 3D warp already produces perturbed walls; the author of this ticket observed that the walls look "very sharply vertical" precisely because the feature is off in this pack, not because the existing implementation is inadequate. Revisit only if enabling the feature fails to soften the walls to the user's liking.
- **Per-entry tuning (mushroom gentler, tropical heavier).** Rejected — user selected "same warp for both". Revisit in a follow-up if the tropical shards (thicker at 112) still read as too vertical compared to mushroom (32).

---

## Context for Implementer

- **Target file (ONLY file to edit):** `/Users/brianfopiano/Developer/RemoteGit/[Minecraft Server]/consumers/plugin-consumers/shared-plugin-data/iris/packs/overworld/biomes/temperate/roughplains.json`
- **Do not touch:** any file under `Iris/core/src/...`. This is pack authoring, not code.
- **Schema reference:** `Iris/core/src/main/java/art/arcane/iris/engine/object/IrisFloatingChildBiomes.java:170-176` documents `wallWarpStyle` and `wallWarpAmplitude` with accepted values, including the exact example the chosen config is based on (`{"style":"SIMPLEX","zoom":0.25}` for "gentle undulation"). Amplitude 10 falls between the labeled bands (`@Desc` line 175: "4..8 = gentle naturalization. 16+ = heavily meandering"), placing it in the lower-medium range — consistent with the user's stated Medium (~8-12 blocks) preference.
- **Field type:** `wallWarpAmplitude` is declared `double` (default `6.0`) at `IrisFloatingChildBiomes.java:176`. In JSON, write `10.0` (not `10`) to match the field's declared type and the existing default's literal form — Gson coerces either, but `10.0` is clearer for future pack authors comparing to the default.
- **Consumer reference:** `FloatingIslandSample.java:220-254` — confirms that when both `wallWarpStyle != null` and `wallWarpAmplitude > 0`, the per-layer XZ warp activates. No other branch needs to be exercised.
- **Field placement convention:** Existing entries order properties as `footprint* → picker* → altitude* → height → topShape → bottom* → maxThickness → carve* → inheritX → objectShrinkFactor`. Insert the two wall-warp fields **immediately after `maxThickness`** and **before `carveStyle`** — this keeps wall-shaping modifiers grouped and matches the @Desc-style progression from coarse (footprint) → fine (carve) detail. Preserve JSON formatting (4-space indent, trailing commas only where already present).
- **Gotchas:**
  - JSON (not JSONC) — no comments, no trailing commas after the last key of an object.
  - Iris CNG styles are case-sensitive — use `"SIMPLEX"` exactly, not `"simplex"`.
  - The file currently has two entries in `floatingChildBiomes` — both must be updated. The whole point of the change is symmetric behavior on both.

### Domain context

- **What the warp does at runtime (to guide visual verification):** For each Y layer of a candidate island column, the generator computes `(sx, sz) = (wx, wz) + 10 × signed_noise3d(wx, wy, wz)` (X and Z offsets use decorrelated input coordinates), then re-samples the 2D footprint at `(sx, sz)` and re-tests against `footprintThreshold`. This means the horizontal silhouette of a shard can expand or contract by up to ~10 blocks per Y slice — the wall is no longer a vertical extrusion of the footprint. Zoom 0.25 makes the feature size of the warp ~25 blocks, which varies across the ~30-block CELLULAR shards enough to push each shard's silhouette into asymmetric bulges without high-frequency jitter.
- **Why tops/bottoms stay clean:** Tops are shaped by `topShapeMode = BIOME` (the target biome's own generators); bottoms by `bottomStyle` (CELLULAR zoom 0.3 / NOWHERE). The wall warp only perturbs the footprint test inside `sample()`, not the computed `topY` or `botY` — so the nicely-noised caps are untouched.

---

## Runtime Environment

This is a Minecraft server plugin — there is no UI to script and no HTTP surface. Verification requires running the Minecraft server with the edited pack and inspecting a generated chunk that contains a floating island.

- **Pack hot-reload:** Iris supports `/iris studio close` + `/iris studio open` or `/iris reload <pack>` on a live server; the user's workflow memory (`project_iris_floating_debug.md`) confirms this is the active testbed.
- **Deploy path:** The pack is already under `[Minecraft Server]/consumers/plugin-consumers/shared-plugin-data/iris/packs/overworld/` — editing the file **in place is the deploy step** (no build artifact to copy; per `AGENTS.md` never copy JARs into server plugin folders, but pack JSON edits are safe).
- **Health check / restart procedure:** Run `/iris studio close` in-game to close any open studio world, then `/iris create <test-world> overworld` (or re-open the existing studio world) so the floating child entries re-initialize. Teleport to a floating island column and visually inspect walls.

---

## Assumptions

- Both `floatingChildBiomes` entries are intended to benefit from wall warping — supported by the user's phrasing "the shards" (plural) and Q3 choice "Same warp for both". Task 1 depends on this.
- `wallWarpStyle = SIMPLEX, zoom 0.25` + `wallWarpAmplitude = 10.0` lands in the lower-medium range between the `@Desc` labels "4..8 = gentle" and "16+ = heavily meandering" on `IrisFloatingChildBiomes.java:175`. Task 1 depends on this.
- Iris correctly serializes `wallWarpStyle` into an `IrisGeneratorStyle` on pack load. Supported by `IrisFloatingChildBiomes.getWallWarpCng(...)` reading it via `getWallWarpStyle()` and `FloatingIslandSample:221` consuming the CNG (no reflection path or gated loader). Task 1 depends on this.
- The generator does not cache island solidity in a way that would persist pre-change results — `FloatingIslandSample` uses `CHUNK_MEMO` ThreadLocal which is cleared on each chunk build. Task 1 (verification step) depends on this; if stale chunks appear post-edit, regenerate or use a fresh world.
- No other pack files reference `roughplains` via snippet-inheritance that would also need edits. Supported by Grep of `floatingChildBiomes` returning only `roughplains.json`. Task 1 depends on this.

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| JSON syntax error (missing comma, wrong quote) breaks pack load | Low | Medium — pack won't load until fixed | Validate with `python3 -m json.tool <file>` after edit; check Iris console for load errors on server restart |
| Amplitude 10 on the taller tropical/wilds shards (112 thick) still reads as too vertical | Medium | Low — purely aesthetic, follow-up tune | Document in "Deferred Ideas" the fallback of bumping tropical amplitude to ~14 or adding scale-aware per-entry tuning |
| Warp displaces thin edge columns entirely out of the footprint, shrinking island footprint noticeably | Medium | Low — this is the desired effect but could feel over-aggressive at the edges | Amplitude 10 is moderate; if islands feel too shrunken, reduce to 6-8 on follow-up |
| Enabling warp adds 2 extra 3D noise samples per Y layer inside islands — perf hit on pregen | Low | Low — bounded by island coverage × max thickness; CNG samples are cheap and already-cached | No action needed; if pregen CPS regresses (see `project_iris_pregen_perf.md`), revisit by disabling warp on one entry |
| The two entries producing identical warp patterns at the same world XZ (because the warp seed mask `0xA117BA17E0FL` is constant across entries) makes them look correlated | Low | Very low — entries don't spatially overlap (picker noise chooses one per column) | Mention as a known-limitation in "Deferred Ideas"; if user ever wants decorrelated warp, add a per-entry seed salt. Not in scope. |

---

## Goal Verification

### Truths

1. `wallWarpStyle` and `wallWarpAmplitude` are set on **both** entries of `floatingChildBiomes` in `roughplains.json`.
2. Both entries use **identical** warp config: `{"style":"SIMPLEX","zoom":0.25}` and amplitude `10.0`.
3. `roughplains.json` remains valid JSON (parses without error).
4. The placement of the two new keys is inside each floating entry object, adjacent to other wall/interior shaping fields (between `maxThickness` and `carveStyle`).
5. No files under `Iris/core/` are modified (enforced by `git diff --stat` showing only the pack JSON changed, if the user is tracking the pack in git).
6. At runtime, a generated floating island in a roughplains region shows horizontal wall perturbation (bulge/recede) rather than a straight XZ extrusion of the 2D footprint. (Manual verification — see scenario MV-001 below.)

### Artifacts

- `[Minecraft Server]/consumers/plugin-consumers/shared-plugin-data/iris/packs/overworld/biomes/temperate/roughplains.json` — the edit
- `Iris/core/src/main/java/art/arcane/iris/engine/object/IrisFloatingChildBiomes.java:170-176` — schema source (read-only reference, not modified)
- `Iris/core/src/main/java/art/arcane/iris/engine/object/FloatingIslandSample.java:220-254` — consumer source (read-only reference, not modified)

---

## Manual Verification Scenarios

No browser automation path exists for a Minecraft server plugin. Scenarios are documented for the user to run in-game.

### MV-001: Wall warp visually active on roughplains floating islands
**Priority:** Critical
**Preconditions:** Test world generated (or fresh chunks loaded) using the overworld pack with the edited `roughplains.json`. Fly mode + `/iris studio` access.
**Mapped Tasks:** Task 1

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Restart the server (or `/iris reload overworld`) so the pack JSON is re-parsed | Iris console logs pack load with no errors mentioning `wallWarpStyle` or `IrisFloatingChildBiomes` |
| 2 | Generate or teleport to a roughplains region (Y ≥ ~180 where floating islands live) | At least one floating island column visible |
| 3 | Fly around the side of a floating island shard | Side walls show horizontal bulges/recesses as Y changes — silhouette is NOT a perfect vertical extrusion of the top outline |
| 4 | Compare top surface with earlier screenshots/observations | Top biome surface (mushroom/forest or tropical/wilds) looks the same as before — tops unchanged |
| 5 | Compare bottom tail with earlier observations | Bottom tail (dripping/roots) looks the same as before — bottoms unchanged |

### MV-002: Pack loads as valid JSON
**Priority:** Critical
**Preconditions:** Edit applied.
**Mapped Tasks:** Task 1

| Step | Action | Expected Result |
|------|--------|-----------------|
| 1 | Run `python3 -m json.tool <pack-path>` from shell | Exits 0, no parse error |
| 2 | Grep the file for `wallWarpStyle` | Returns exactly 2 matches (one per floating entry) |
| 3 | Grep the file for `wallWarpAmplitude` | Returns exactly 2 matches |

---

## Progress Tracking

- [x] Task 1: Enable wall warp on both `floatingChildBiomes` entries in `roughplains.json`

**Total Tasks:** 1 | **Completed:** 1 | **Remaining:** 0

---

## Implementation Tasks

### Task 1: Enable wall warp on both floating child biomes in roughplains.json

**Objective:** Add `wallWarpStyle` and `wallWarpAmplitude` to both entries of `floatingChildBiomes` in `roughplains.json`, identical values on both.
**Dependencies:** None
**Mapped Scenarios:** MV-001, MV-002

**Files:**

- Modify: `/Users/brianfopiano/Developer/RemoteGit/[Minecraft Server]/consumers/plugin-consumers/shared-plugin-data/iris/packs/overworld/biomes/temperate/roughplains.json`

**Key Decisions / Notes:**

- Insert two keys in each entry, immediately after `"maxThickness": N,` and before `"carveStyle": { ... },`.
- Exact JSON to insert (indentation matches surrounding 4-space style — one leading space for the key position based on the file's existing alignment):

```json
"wallWarpStyle": {
    "style": "SIMPLEX",
    "zoom": 0.25
},
"wallWarpAmplitude": 10.0,
```

- Do this on the `mushroom/forest` entry (currently the block starting at line 255) AND on the `tropical/wilds` entry (starting at line 277).
- Do NOT reorder any existing fields.
- Do NOT add `wallWarpStyle` to the parent biome fields — it is per-entry on `IrisFloatingChildBiomes`, not on `IrisBiome`.
- Preserve the existing (minor) indentation quirks in the file as-is; this is a targeted additive edit.
- No performance concern — the warp adds two noise samples per Y layer inside island columns only; CNG results are cached per-entry via `AtomicCache` in `IrisFloatingChildBiomes`.

**Definition of Done:**

- [ ] Both `floatingChildBiomes` entries contain `wallWarpStyle: {"style":"SIMPLEX","zoom":0.25}` and `wallWarpAmplitude: 10.0`.
- [ ] Pre-edit sanity: `grep -rl 'floatingChildBiomes' /Users/brianfopiano/Developer/RemoteGit/[Minecraft\ Server]/consumers/plugin-consumers/shared-plugin-data/iris/packs/overworld/` returns only `roughplains.json` (no other pack file needs the same edit).
- [ ] `python3 -m json.tool <pack-path>` exits 0 (valid JSON).
- [ ] `grep -c 'wallWarpStyle' <pack-path>` returns `2`.
- [ ] `grep -c 'wallWarpAmplitude' <pack-path>` returns `2`.
- [ ] MV-001 passes end-to-end on a live test world: side walls of roughplains floating shards exhibit horizontal bulge/recession, while tops and bottoms look unchanged from prior observation.
- [ ] No files under `Iris/core/` or any other plugin source tree are modified.

**Verify:**

```bash
# JSON validity
python3 -m json.tool /Users/brianfopiano/Developer/RemoteGit/[Minecraft\ Server]/consumers/plugin-consumers/shared-plugin-data/iris/packs/overworld/biomes/temperate/roughplains.json > /dev/null

# Field counts
grep -c 'wallWarpStyle' /Users/brianfopiano/Developer/RemoteGit/[Minecraft\ Server]/consumers/plugin-consumers/shared-plugin-data/iris/packs/overworld/biomes/temperate/roughplains.json
grep -c 'wallWarpAmplitude' /Users/brianfopiano/Developer/RemoteGit/[Minecraft\ Server]/consumers/plugin-consumers/shared-plugin-data/iris/packs/overworld/biomes/temperate/roughplains.json
```

Then MV-001 via manual in-game verification on the user's running test server.

---

## Deferred Ideas

- **Per-entry scale-aware tuning:** If the tropical/wilds shards (112 thick) still read as too vertical compared to mushroom/forest (32 thick), bump tropical's `wallWarpAmplitude` to ~14 while leaving mushroom at 10.
- **Per-entry warp decorrelation:** Both entries currently sample `wallWarp` noise from the same RNG mask (`0xA117BA17E0FL` in `IrisFloatingChildBiomes:81`). Entries never spatially overlap (the picker routes each column to exactly one), but if visually noticeable correlation ever appears where biomes are adjacent, add a per-entry seed salt.
- **Higher-fidelity warp (iq-style):** Chain a second noise sample: `P' = P + A·n1(P); P'' = P' + B·n2(P');` then sample footprint at `P''`. Only pursue if the single-pass warp is not expressive enough after Task 1 is tuned.
- **Vertical frequency scaling:** Expose a `wallWarpVerticalScale` so the warp varies faster with Y than with XZ — would make adjacent Y slices more different, reducing any residual vertical banding without changing horizontal feature size.
- **Schema default flip:** Changing `IrisFloatingChildBiomes.wallWarpStyle` default from `null` to a gentle SIMPLEX. Out of scope this plan; would silently change behavior for any existing pack.
