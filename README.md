# Cobblemon Trainers EX

A fork of the discontinued mod [CobblemonTrainers by selfdot](https://www.curseforge.com/minecraft/mc-mods/cobblemontrainers). For a similar mod on 1.21.1 (fabric) please check out [RCT API](https://www.curseforge.com/minecraft/mc-mods/radical-cobblemon-trainers-api).

## The AI

Trainers now consider many factors in a battle instead of clicking the biggest number. The AI reasons about type matchups, speed, switching, status, setup, weather and terrain, entry hazards, screens, and Protect, and it coordinates both slots in doubles. Difficulty is a single number (level 0 to 11): low levels play semi-random, high levels make sharp plays, and level 11 can peek at your team/moves. By default it only uses information a human opponent could see.

Set it globally or per trainer with the AI commands below.

## Commands

All commands are under `/trainers`.

**Play**
- `/trainers battle` : open a menu to pick a trainer to fight
- `/trainers battle <trainer>` : fight a trainer directly

**Create and manage** (needs edit permission)
- `/trainers setup` : open the trainer editor GUI
- `/trainers add <name> [group]` : create a new trainer
- `/trainers remove <trainer>` : delete a trainer
- `/trainers rename <trainer> <newName>` : rename a trainer
- `/trainers setgroup <trainer> <group>` : move a trainer to another group
- `/trainers addpokemon <trainer> <properties>` : add a Pokemon by properties
- `/trainers addfromparty <trainer> <slot>` : copy a Pokemon from your party

**Tune a trainer** (needs edit permission)
- `/trainers setbattletype <trainer> <singles|doubles>` : set the battle format
- `/trainers setailevel <trainer> <-1..11>` : AI level (-1 inherits global)
- `/trainers setrandomizeleads <trainer> <true|false>` : send out a random lead
- `/trainers setpartymaximumlevel <trainer> <1-100>` : cap the challenger's party level
- `/trainers setcooldownseconds <trainer> <seconds>` : re-battle cooldown
- `/trainers setcanonlybeatonce <trainer> <true|false>` : allow one win per player
- `/trainers setwincommand <trainer> <command>` : run a command when the player wins
- `/trainers setlosscommand <trainer> <command>` : run a command when the player loses
- `/trainers adddefeatrequirement <trainer> <other>` : require beating another trainer first
- `/trainers removedefeatrequirement <trainer> <other>` : remove that prerequisite

**AI (global)**
- `/trainers usescoreai` : use the new score AI for all trainers
- `/trainers usegen5ai` : use the legacy Gen 5 AI
- `/trainers usestrongai <level>` : use the legacy strong AI
- `/trainers aidebug <true|false>` : show the AI's per-move scoring in chat

**Admin**
- `/trainers reload` : reload trainer data from disk
- `/trainers makebattle <player> <trainer>` : force a player into a battle
- `/trainers resetwintracker <player> <trainer>` : clear a player's recorded win

## Trainer editor

Run `/trainers setup` to open the editor (a chest style menu). Browse Groups, then a Group, then a Trainer, click a slot to add or edit a Pokemon, and use New Pokemon to fill an empty team (up to 6). Reorder or remove team members from the trainer view. Battle rules, rewards, and AI level are set with the `/trainers set*` commands above.

Per Pokemon you can add or tune:
- Species and form
- Level (1-100)
- Nature
- Ability
- Moves (up to 4, from the legal learnset)
- EVs and IVs
- Held item
- Gender
- Shiny

## Credits

*   selfdot (CobblemonTrainers)
*   [pokeemerald-rogue](https://github.com/Pokabbie/pokeemerald-rogue/) (for scoreAI imp ideas)
