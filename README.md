# AIPlayer — Autonomous Minecraft AI Players

A **Fabric mod** for Minecraft 26.3 that spawns autonomous agents who survive, learn, and form a society inside a real survival world — **without any LLM**. Each bot runs on a custom **neural associative memory** with **embodied sensing**; language is not taught but **self-invented** from a 24-letter alphabet; nothing about behaviour is hard-coded. Everything is emergent and **persisted across restarts**.

## Highlights

- **No LLM, no chatbuddy.** Removed. Agents never call a language model — language emerges from world events and their own phonation. Spoken lines may carry English punctuation (`.`, `,`, `!`, `?`) self-invented like the letters: the mouth can draw a mark, the semantic layer binds it to a state, and the brain may repeat it — never a hard-coded break. Neither the letter-count of a coined syllable nor the word-count of a sentence is capped — both limits were removed in favor of natural emergence: a syllable is as long as the words already learned, and a sentence runs until the moment has no more words to say.
- **Embodied perception.** A foveated **retina matrix** (fine fovea + coarser periphery) returns entity/block labels, names, brightness and depth; separate input channels for hearing (sound registry names), the crosshair, world events, containers, signs and books.
- **Neural associative memory.** Perception is encoded as **addressed ids** and linked by co-occurrence; no rules are injected into the encoding. Multi-tier memory (working / episodic / semantic / long-term) with a forgetting curve and one shared store.
- **Partitioned brain.** Regions mirror a nervous system: sensory cortices, parietal spatial working memory, hippocampus (cognitive map), prefrontal cortex (counterfactual simulation / prediction), limbic + autonomic (emotion, drives), motor cortex, and a language area that binds **self-invented words** to perception.
- **Emergent behaviour.** Movement, mining, crafting, combat, eating, sleeping, trading, signs — all learned from lived outcome, not hard-coded. Only player names, name tags and anvil rename slots are ever fed as words.
- **Companion viewer.** A Swing app (`viewer/`) replays bot lives from exported CSVs: positions, health/hunger, panic/joy, goals, world chat, day/night lighting and local light sources, scrubbing along the world clock.

## How it works

Embodied perception (retina matrix + hearing + world events + crosshair + containers) → encoded as addressed feature ids → driven into the partitioned brain regions → memories linked by co-occurrence and re-activated on cue → executive goal selected by learned policy + counterfactual imagination → motor cortex turns the decision into real movement / mining / crafting / combat. Language is produced by a phonation layer that coins syllables and binds new words to the perception ids active at the moment, and each spoken line may carry English punctuation (`.`, `,`, `!`, `?`) that the mouth self-invents like a letter and the semantic layer learns by co-occurrence — never a hard-coded sentence break.

## Build

```
./gradlew build        # JDK 25, Fabric Loader + API, Aliyun mirrors
```

Output: `build/libs/aiplayer-1.0.0.jar`.

## Run

Launch a Fabric server or the IDE "run Server" (or the headless server in `headless/`). Spawn bots:

```
/aiplayer add <name>
```

`/aiplayer botview` renders a client-side view the way a bot's retina sees the world (toggle on/off).

## Data output (`run/`)

`aiplayer_world.csv`, `aiplayer_chat.csv`, `aiplayer_map_latest.png`, `aiplayer_lights.csv`, `aiplayer_map_meta.csv` — unified **world-clock** timestamps (day + tick, persisted across restarts), consumed by `viewer/`.

## Status & roadmap

See `TODO.md` for the detailed backlog (e.g. referential pressure, whether spoken sentences grow longer over time, further brain-region refinement). See `DEVELOPER_HANDOVER.md` for an onboarding guide for future maintainers.

## License

CC0-1.0 (see `LICENSE`).
