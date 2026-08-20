package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// commanding mons (tatsugiri riding dondozo) can't act or be hit. filled by RequestInstructionMixin.
public final class CommanderTracker {

    private static final Map<UUID, Map<UUID, Set<UUID>>> BY_BATTLE = new ConcurrentHashMap<>();

    private CommanderTracker() {}

    public static void record(UUID battleId, UUID actorId, Set<UUID> commanding) {
        if (battleId == null || actorId == null) return;
        BY_BATTLE.computeIfAbsent(battleId, k -> new ConcurrentHashMap<>()).put(actorId, commanding);
    }

    public static void clear(UUID battleId) {
        BY_BATTLE.remove(battleId);
    }

    public static boolean isCommanding(ActiveBattlePokemon slot) {
        if (slot == null) return false;
        BattlePokemon mon = slot.getBattlePokemon();
        return mon != null && isCommanding(slot.getBattle().getBattleId(), mon.getUuid());
    }

    // a request only carries its owner's side, so check every actor's report.
    public static boolean isCommanding(UUID battleId, UUID pokemonId) {
        Map<UUID, Set<UUID>> byActor = battleId == null ? null : BY_BATTLE.get(battleId);
        if (byActor == null || pokemonId == null) return false;
        for (Set<UUID> ids : byActor.values()) {
            if (ids.contains(pokemonId)) return true;
        }
        return false;
    }
}
