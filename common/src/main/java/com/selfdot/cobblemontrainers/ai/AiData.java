package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.abilities.Ability;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.battles.MoveTarget;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.ArrayList;
import java.util.List;

// per-call analysis shared by the scoring layers, built from the deciding slot,
// its usable moves, and the live adjacent foes.
public class AiData {

    public final ActiveBattlePokemon selfActive;
    public final BattlePokemon self;
    public final List<ActiveBattlePokemon> opponents;  // live adjacent foes (empty if none)
    public final ActiveBattlePokemon opponentActive;   // primary foe (first adjacent), null if none
    public final BattlePokemon opponent;               // primary foe's mon, null if none
    public final BattleMemory memory;                  // battle-scoped memory, for live typing reads
    public final List<ScoredMove> moves = new ArrayList<>();

    // one damaging move's outcome against one specific foe.
    public static class TargetDamage {
        public final ActiveBattlePokemon target;
        public final double damage;
        public final double effectiveness;
        public final int hitsToKO;
        public final boolean canFaint;
        // fake out would flinch this foe: set by the scoring layer, which knows the first-turn window.
        public boolean flinches;

        TargetDamage(ActiveBattlePokemon target, double damage, double effectiveness, int hitsToKO, boolean canFaint) {
            this.target = target;
            this.damage = damage;
            this.effectiveness = effectiveness;
            this.hitsToKO = hitsToKO;
            this.canFaint = canFaint;
        }
    }

    public static class ScoredMove {
        public final InBattleMove inMove;
        public final MoveTemplate template;
        // vs best single target (or primary foe for spread), so single-target layers read unchanged.
        public double damage;
        public int hitsToKO = Integer.MAX_VALUE;
        public double effectiveness = 1.0;
        public boolean canFaint;
        public double score = 100.0;
        public boolean flinches;                              // denies the resolved target its turn
        // doubles bookkeeping.
        public ActiveBattlePokemon target;                    // resolved best foe; null for spread/self/status
        public boolean spread;
        public boolean hitsAlly;                              // spread that also clips our own ally (earthquake)
        public final List<TargetDamage> perTarget = new ArrayList<>();

        ScoredMove(InBattleMove inMove, MoveTemplate template) {
            this.inMove = inMove;
            this.template = template;
        }

        public boolean isDamaging() {
            return template != null && template.getPower() > 0;
        }
    }

    private AiData(ActiveBattlePokemon selfActive, List<ActiveBattlePokemon> opponents, BattleMemory memory) {
        this.selfActive = selfActive;
        this.self = selfActive.getBattlePokemon();
        this.opponents = opponents;
        this.opponentActive = opponents.isEmpty() ? null : opponents.get(0);
        this.opponent = opponentActive == null ? null : opponentActive.getBattlePokemon();
        this.memory = memory;
    }

    public static AiData build(ActiveBattlePokemon selfActive, List<InBattleMove> usableMoves,
                               List<ActiveBattlePokemon> opponents, BattleMemory memory) {
        AiData data = new AiData(selfActive, opponents, memory);
        for (InBattleMove m : usableMoves) {
            MoveTemplate template = Moves.INSTANCE.getByName(m.getId());
            ScoredMove sm = new ScoredMove(m, template);
            if (sm.isDamaging() && !data.opponents.isEmpty()) resolveTargets(data, sm);
            data.moves.add(sm);
        }
        return data;
    }

    // score against every live foe, keep each outcome, fold the best into the flat fields.
    private static void resolveTargets(AiData data, ScoredMove sm) {
        MoveTarget mt = sm.inMove.getTarget();
        sm.spread = mt == MoveTarget.allAdjacentFoes || mt == MoveTarget.allAdjacent;
        sm.hitsAlly = mt == MoveTarget.allAdjacent;
        double spreadMod = sm.spread && data.opponents.size() >= 2 ? 0.75 : 1.0;
        // only single-target moves get redirected/absorbed (lightningrod/stormdrain); spread aren't.
        boolean redirected = !sm.spread && redirectedOnThisSide(data, sm.template.getElementalType());

        TargetDamage best = null;
        for (ActiveBattlePokemon foeActive : data.opponents) {
            BattlePokemon foe = foeActive.getBattlePokemon();
            if (foe == null) continue;
            Pokemon foeMon = foe.getOriginalPokemon();
            double dmg = redirected ? 0 : DamageCalc.estimate(data.self, foe, sm.template, data.memory) * spreadMod;
            double eff = redirected ? 0 : TypeChart.abilityAware(
                sm.template.getElementalType(), data.memory.liveTyping(foe).defensive(), foeMon.getAbility());
            int htk = DamageCalc.hitsToKO(dmg, foe.getHealth());
            boolean ko = dmg >= foe.getHealth();
            TargetDamage td = new TargetDamage(foeActive, dmg, eff, htk, ko);
            sm.perTarget.add(td);
            if (best == null || betterTarget(td, best)) best = td;
        }
        if (best == null) return;
        sm.damage = best.damage;
        sm.effectiveness = best.effectiveness;
        sm.hitsToKO = best.hitsToKO;
        sm.canFaint = best.canFaint;
        // spread hits everything (null target); single-target aims at the best foe.
        sm.target = sm.spread ? null : best.target;
    }

    // any live foe whose ability absorbs single-target moves of this type (lightningrod/stormdrain)?
    private static boolean redirectedOnThisSide(AiData data, ElementalType moveType) {
        for (ActiveBattlePokemon foeActive : data.opponents) {
            BattlePokemon foe = foeActive.getBattlePokemon();
            if (foe == null || foe.getHealth() <= 0) continue;
            Ability ability = foe.getOriginalPokemon().getAbility();
            if (ability != null && TypeChart.redirects(ability.getName(), moveType)) return true;
        }
        return false;
    }

    // prefer the foe we come closest to KOing, then the one we hit hardest.
    private static boolean betterTarget(TargetDamage a, TargetDamage b) {
        if (a.hitsToKO != b.hitsToKO) return a.hitsToKO < b.hitsToKO;
        return a.damage > b.damage;
    }
}
