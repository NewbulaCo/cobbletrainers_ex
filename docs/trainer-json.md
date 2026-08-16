# Editing trainer files by hand

Trainers are JSON files at `config/trainers/groups/<group>/<Name>.json`.

The **filename** is the trainer's name and the **folder** is the group, `Brock.json` inside `gym-leaders/` is trainer "Brock" in group "gym-leaders".

Copy [`example-trainer.json`](example-trainer.json) to get started.

## Read this first

- **A malformed file disables the whole mod** when the world loads, and every trainer after it is skipped, so back up before editing.
- **Forms are aspects, not species.** Ogerpon-Hearthflame is `cobblemon:ogerpon` with aspect `hearthflame`.

## Trainer fields

`team` is the only required field.

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `team` | array | n/a | **Required.** Up to 6 Pokemon |
| `battleType` | string | `"singles"` | `"singles"` or `"doubles"` |
| `aiLevel` | int | `-1` | `-1` inherits the global AI (level 4) otherwise `0` to `11` |
| `randomizeLeads` | bool | `false` | Send out a random lead |
| `partyMaximumLevel` | int | `100` | Caps the challenger's party |
| `cooldownSeconds` | int | `0` | Re-battle cooldown |
| `canOnlyBeatOnce` | bool | `false` | One win per player |
| `winCommand` | string | `""` | Command run when the player wins |
| `lossCommand` | string | `""` | Command run when the player loses |
| `defeatRequiredTrainers` | array | `[]` | Trainer names to beat first |

## Pokemon fields

`species`, `gender`, `level`, `nature`, `ability`, `moveset`, `ivs` and `evs` are all required.

| Field | Type | Notes |
| --- | --- | --- |
| `species` | string | `cobblemon:<id>`, no spaces or underscores (`cobblemon:fluttermane`) |
| `gender` | string | `MALE`, `FEMALE` or `GENDERLESS`, uppercase |
| `level` | int | 1 to 100 |
| `nature` | string | `cobblemon:<name>` (`cobblemon:adamant`) |
| `ability` | string | Lowercase id, no spaces (`unseenfist`) |
| `moveset` | array | Lowercase ids, no spaces/hyphens/apostrophes (`willowisp`, `uturn`)|
| `ivs` | object | See below. Missing stats become **0** |
| `evs` | object | See below. List only non-zero stats |
| `heldItem` | string | `cobblemon:leftovers`, `minecraft:air`, … |
| `shiny` | bool | `false` by default |
| `aspects` | array | Form aspects (`[rapid-strike]`, `[hearthflame]`) |

### IV and EV keys

`hp`, `attack`, `defence`, `special_attack`, `special_defence`, `speed`

Note the spelling: `defence` and `special_defence`. IVs cap at 31 and EVs at 252 per stat.

## Tips

- The AI level table is in the [README](../README.md#the-ai) for more details.
- [Radical Cobblemon Trainers](https://www.curseforge.com/minecraft/mc-mods/radical-cobblemon-trainers-api) uses this same schema, so check it out for more examples.
