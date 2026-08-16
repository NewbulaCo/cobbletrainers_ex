package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;

import java.util.List;

// decides voluntary switches and picks replacements. incoming danger proxied from the foe's visible
// STAB typing. offensive mode prefers a revenge killer, defensive prefers a resist-and-threaten mon.
// todo(later): survivability sim (hazards/weather/items), stat stages, tracked foe moves.
public final class SwitchPlanner {

    private SwitchPlanner() {}

    // matchup improvement needed to justify a switch; stops ping-ponging between mediocre matchups.
    private static final double SWITCH_MARGIN = 1.5;

    public static boolean shouldSwitchOut(AiData data, boolean trapped) {
        return shouldSwitchOut(data, trapped, false, false);
    }

    // foeThreatensOverride flags a real incoming ko the STAB proxy would miss; still gated by the
    // stay-in suppressor and bench-improvement check, so it never switches into a worse mon.
    // threatNeutralized drops the threat half entirely (we hold a landing fake out, so the foe doesn't
    // get to act); being walled still sends us out, since a flinch only buys the one turn.
    public static boolean shouldSwitchOut(AiData data, boolean trapped, boolean foeThreatensOverride,
                                          boolean threatNeutralized) {
        if (trapped || data.opponent == null) return false;

        List<BattlePokemon> bench = benchOf(data.selfActive);
        if (bench.isEmpty()) return false;

        // stay in if we can KO the foe this turn and move first.
        for (AiData.ScoredMove m : data.moves) {
            if (m.canFaint && DamageCalc.strikesFirst(data.self, m.template, data.opponent, null)) return false;
        }

        boolean walled = isWalled(data);
        boolean foeThreatens = !threatNeutralized
            && (foeThreatensOverride || stabThreat(data.opponent, data.self, data.memory) >= 2.0);
        if (!walled && !foeThreatens) return false;

        double current = matchup(data.self, data.opponent, data.memory);
        BattlePokemon best = bestByMatchup(bench, data.opponent, data.memory);
        double bestScore = best == null ? Double.NEGATIVE_INFINITY : matchup(best, data.opponent, data.memory);
        return best != null && bestScore > current + SWITCH_MARGIN && bestScore > 0;
    }

    public static BattlePokemon chooseReplacement(ActiveBattlePokemon selfActive, BattlePokemon foe, boolean offensive, BattleMemory memory) {
        List<BattlePokemon> bench = benchOf(selfActive);
        if (bench.isEmpty()) return null;
        if (foe == null) return bench.get(0);
        if (offensive) {
            for (BattlePokemon p : bench) {
                if (canFaint(p, foe, memory) && DamageCalc.outspeeds(p, foe)) return p;
            }
        }
        return bestByMatchup(bench, foe, memory);
    }

    // higher is better: our damage into the foe minus foe STAB danger, plus a small speed edge.
    public static double matchup(BattlePokemon mon, BattlePokemon foe, BattleMemory memory) {
        int hits = minHitsToKO(mon, foe, memory);
        double offense = hits <= 1 ? 3 : hits == 2 ? 2 : hits <= 3 ? 1 : 0;
        double danger = stabThreat(foe, mon, memory);
        return offense - danger + (DamageCalc.outspeeds(mon, foe) ? 0.5 : 0);
    }

    // best effectiveness of the attacker's STAB types vs the defender (immunity-ability aware); proxy
    // for danger when the moveset is unknown. both sides use live typing, so soak/tera judged as-is.
    public static double stabThreat(BattlePokemon attacker, BattlePokemon defender, BattleMemory memory) {
        LiveTyping a = memory.liveTyping(attacker);
        LiveTyping d = memory.liveTyping(defender);
        double worst = 0;
        for (ElementalType t : a.stab()) {
            worst = Math.max(worst, TypeChart.abilityAware(t, d.defensive(), defender.getOriginalPokemon().getAbility()));
        }
        return worst;
    }

    private static boolean isWalled(AiData data) {
        boolean anyEffective = false;
        int bestHits = Integer.MAX_VALUE;
        for (AiData.ScoredMove m : data.moves) {
            if (!m.isDamaging()) continue;
            if (m.effectiveness > 0) anyEffective = true;
            bestHits = Math.min(bestHits, m.hitsToKO);
        }
        return !anyEffective || bestHits >= 4;
    }

    private static BattlePokemon bestByMatchup(List<BattlePokemon> bench, BattlePokemon foe, BattleMemory memory) {
        BattlePokemon best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BattlePokemon p : bench) {
            double s = matchup(p, foe, memory);
            if (s > bestScore) {
                bestScore = s;
                best = p;
            }
        }
        return best;
    }

    private static int minHitsToKO(BattlePokemon attacker, BattlePokemon foe, BattleMemory memory) {
        int best = Integer.MAX_VALUE;
        for (var move : attacker.getMoveSet().getMoves()) {
            MoveTemplate t = Moves.INSTANCE.getByName(move.getName());
            if (t == null) continue;
            best = Math.min(best, DamageCalc.hitsToKO(DamageCalc.estimate(attacker, foe, t, memory), foe.getHealth()));
        }
        return best;
    }

    private static boolean canFaint(BattlePokemon attacker, BattlePokemon foe, BattleMemory memory) {
        for (var move : attacker.getMoveSet().getMoves()) {
            MoveTemplate t = Moves.INSTANCE.getByName(move.getName());
            if (t != null && DamageCalc.estimate(attacker, foe, t, memory) >= foe.getHealth()) return true;
        }
        return false;
    }

    private static List<BattlePokemon> benchOf(ActiveBattlePokemon selfActive) {
        return selfActive.getActor().getPokemonList().stream()
            .filter(BattlePokemon::canBeSentOut)
            .toList();
    }
}
