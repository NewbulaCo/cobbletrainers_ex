package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.battles.interpreter.BattleContext;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.BattleSide;

import java.util.Collection;
import java.util.Locale;

// reads of field and side conditions from cobblemon's ContextManager buckets, all public info.
// context ids normalized to a bare lowercase-alnum token so showdown ids and our move-table
// tokens compare equal despite casing/spacing ("RainDance" vs "raindance").
public final class FieldState {

    private FieldState() {}

    public static String normalize(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // normalized id of the sole context in a field-level bucket (weather/room/terrain are
    // exclusive), or null.
    public static String fieldId(ActiveBattlePokemon mon, BattleContext.Type type) {
        return fieldId(mon.getBattle(), type);
    }

    // same read straight off the battle.
    public static String fieldId(PokemonBattle battle, BattleContext.Type type) {
        Collection<BattleContext> bucket = battle.getContextManager().get(type);
        if (bucket == null || bucket.isEmpty()) return null;
        return normalize(bucket.iterator().next().getId());
    }

    // does this side carry a context with the given id? (screens, tailwind)
    public static boolean sideHas(BattleSide side, BattleContext.Type type, String id) {
        Collection<BattleContext> bucket = side.getContextManager().get(type);
        if (bucket == null) return false;
        String target = normalize(id);
        for (BattleContext c : bucket) {
            if (normalize(c.getId()).equals(target)) return true;
        }
        return false;
    }

    public static boolean ownSideHas(ActiveBattlePokemon mon, BattleContext.Type type, String id) {
        return countSide(mon, type, id) > 0;
    }

    // count of contexts with this id on the slot's side; for hazards this is the layer count.
    public static int countSide(ActiveBattlePokemon sideMon, BattleContext.Type type, String id) {
        Collection<BattleContext> bucket = sideMon.getSide().getContextManager().get(type);
        if (bucket == null) return 0;
        String target = normalize(id);
        int count = 0;
        for (BattleContext c : bucket) {
            if (normalize(c.getId()).equals(target)) count++;
        }
        return count;
    }
}
