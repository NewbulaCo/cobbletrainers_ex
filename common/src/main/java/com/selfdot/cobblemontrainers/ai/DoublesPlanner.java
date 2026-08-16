package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// joint turn planner for a trainer's two doubles slots. searches the paired-action space and overrides
// greedy per-slot play only when a coordinated pair clearly wins. our-side info only.
public final class DoublesPlanner {

    private DoublesPlanner() {}

    // override baseline only when a pair wins by more than this.
    private static final double OVERRIDE_MARGIN = 1.5;
    // one secured ko; above a single move's faint bonus so a second ko beats overkill.
    private static final double KO_VALUE = 6.0;
    // partial credit for non-ko chip, scaled by fraction of hp removed.
    private static final double CHIP_WEIGHT = 2.0;
    // a flinched foe loses its whole turn; worth well under a ko, and nothing on a foe we kill anyway.
    private static final double FLINCH_VALUE = 2.5;
    // both slots protecting burns a turn off a timer running for the foe's side while nothing gets
    // through. sized to beat a pair of chip hits but lose to a ko, so it only fills a dead turn.
    private static final double PAIR_PROTECT_VALUE = 5.0;

    // one slot's action: a move at a target, a spread/self move (target null), a switch, or a
    // prebuilt raw response (struggle/recharge/pass) that never joins a combo.
    public static final class Choice {
        final AiData.ScoredMove move;
        final ActiveBattlePokemon target;
        final BattlePokemon switchTo;
        final ShowdownActionResponse rawResponse;
        final String moveId;

        private Choice(AiData.ScoredMove move, ActiveBattlePokemon target, BattlePokemon switchTo,
                       ShowdownActionResponse rawResponse, String moveId) {
            this.move = move;
            this.target = target;
            this.switchTo = switchTo;
            this.rawResponse = rawResponse;
            this.moveId = moveId;
        }

        public static Choice move(AiData.ScoredMove move, ActiveBattlePokemon target) {
            return new Choice(move, target, null, null, null);
        }

        public static Choice switchTo(BattlePokemon mon) {
            return new Choice(null, null, mon, null, null);
        }

        public static Choice raw(ShowdownActionResponse response, String moveId) {
            return new Choice(null, null, null, response, moveId);
        }
    }

    // one slot: scored moves, grounded flag (terrain), bench mons whose entry sets a field, solo action.
    public static final class Slot {
        final ActiveBattlePokemon active;
        final BattlePokemon mon;
        final AiData data;
        final boolean grounded;
        final List<BattlePokemon> fieldSwitchIns;
        final Choice solo;

        public Slot(ActiveBattlePokemon active, AiData data, boolean grounded,
                    List<BattlePokemon> fieldSwitchIns, Choice solo) {
            this.active = active;
            this.mon = active.getBattlePokemon();
            this.data = data;
            this.grounded = grounded;
            this.fieldSwitchIns = fieldSwitchIns;
            this.solo = solo;
        }
    }

    public static final class Plan {
        public final Choice lead;
        public final Choice ally;

        Plan(Choice lead, Choice ally) {
            this.lead = lead;
            this.ally = ally;
        }
    }

    // best joint pair, returned only when it beats both solo plays by a clear margin. null = no
    // coordination pays; let each slot play greedy.
    public static Plan plan(Slot lead, Slot ally, List<ActiveBattlePokemon> foes,
                            String weather, String terrain, BattleMemory memory, boolean burnTimer) {
        if (foes.isEmpty() || lead.mon == null || ally.mon == null) return null;
        List<Choice> leadCandidates = candidates(lead, foes);
        List<Choice> allyCandidates = candidates(ally, foes);

        double baseline = jointValue(lead, ally, lead.solo, ally.solo, foes, weather, terrain, memory, burnTimer);
        double best = baseline;
        Choice bestLead = lead.solo;
        Choice bestAlly = ally.solo;
        for (Choice lc : leadCandidates) {
            for (Choice ac : allyCandidates) {
                // shared bench: both slots can't switch to the same mon.
                if (lc.switchTo != null && lc.switchTo == ac.switchTo) continue;
                double value = jointValue(lead, ally, lc, ac, foes, weather, terrain, memory, burnTimer);
                if (value > best + 1e-6) {
                    best = value;
                    bestLead = lc;
                    bestAlly = ac;
                }
            }
        }
        if (best <= baseline + OVERRIDE_MARGIN) return null;
        return new Plan(bestLead, bestAlly);
    }

    // candidate actions: each scored move (single-target fanned out per foe) plus swiches to
    // field-setting bench mons. kept small so the paired search stays a few dozen tuples.
    private static List<Choice> candidates(Slot slot, List<ActiveBattlePokemon> foes) {
        List<Choice> out = new ArrayList<>();
        if (slot.data != null) {
            for (AiData.ScoredMove m : slot.data.moves) {
                if (m.template == null || !m.isDamaging() || m.spread) {
                    out.add(Choice.move(m, null));
                } else {
                    for (ActiveBattlePokemon foe : foes) out.add(Choice.move(m, foe));
                }
            }
        }
        for (BattlePokemon p : slot.fieldSwitchIns) out.add(Choice.switchTo(p));
        return out;
    }

    // side value of this action pair. damage pooled per foe under the resulting field (shared ko counts
    // once, separate kos twice); non-damaging adds utility and friendly fire subtracts
    private static double jointValue(Slot lead, Slot ally, Choice leadChoice, Choice allyChoice,
                                     List<ActiveBattlePokemon> foes, String weather, String terrain,
                                     BattleMemory memory, boolean burnTimer) {
        String effWeather = weather;
        String effTerrain = terrain;
        String[] leadField = fieldSet(leadChoice);
        String[] allyField = fieldSet(allyChoice);
        if (leadField[0] != null) effWeather = leadField[0];
        if (leadField[1] != null) effTerrain = leadField[1];
        if (allyField[0] != null) effWeather = allyField[0];
        if (allyField[1] != null) effTerrain = allyField[1];

        boolean leadHelpingHand = isHelpingHand(leadChoice);
        boolean allyHelpingHand = isHelpingHand(allyChoice);

        Map<UUID, Double> received = new HashMap<>();
        contribute(lead, leadChoice, allyHelpingHand, weather, terrain, effWeather, effTerrain, foes, received);
        contribute(ally, allyChoice, leadHelpingHand, weather, terrain, effWeather, effTerrain, foes, received);
        Set<UUID> flinched = new HashSet<>();
        collectFlinch(leadChoice, flinched);
        collectFlinch(allyChoice, flinched);

        double value = 0;
        for (ActiveBattlePokemon foe : foes) {
            BattlePokemon foeMon = foe.getBattlePokemon();
            if (foeMon == null) continue;
            int hp = foeMon.getHealth();
            if (hp <= 0) continue;
            double dmg = received.getOrDefault(foeMon.getUuid(), 0.0);
            if (dmg >= hp) {
                value += KO_VALUE; // dead anyway, so a flinch on it bought nothing
            } else {
                value += CHIP_WEIGHT * Math.min(1.0, dmg / hp);
                if (flinched.contains(foeMon.getUuid())) value += FLINCH_VALUE;
            }
        }
        if (burnTimer && isProtect(leadChoice) && isProtect(allyChoice)) value += PAIR_PROTECT_VALUE;
        value += utilityValue(leadChoice) + utilityValue(allyChoice);
        value -= friendlyFire(lead, leadChoice, ally, weather, terrain, effWeather, effTerrain, memory);
        value -= friendlyFire(ally, allyChoice, lead, weather, terrain, effWeather, effTerrain, memory);
        return value;
    }

    // pool this action's damage onto the foes it hits, under the effective field and partner helping
    // hand. perTarget already carries the current field, so only the field delta is applied here.
    private static void contribute(Slot slot, Choice choice, boolean partnerHelpingHand,
                                   String curWeather, String curTerrain, String effWeather, String effTerrain,
                                   List<ActiveBattlePokemon> foes, Map<UUID, Double> received) {
        if (choice.move == null || !choice.move.isDamaging() || choice.move.template == null) return;
        ElementalType type = choice.move.template.getElementalType();
        double mult = fieldDelta(type, curWeather, curTerrain, effWeather, effTerrain, slot.grounded);
        if (partnerHelpingHand) mult *= 1.5;
        if (choice.move.spread) {
            for (AiData.TargetDamage td : choice.move.perTarget) add(received, td.target, td.damage * mult);
        } else {
            AiData.TargetDamage td = perTarget(choice.move, choice.target);
            if (td != null) add(received, td.target, td.damage * mult);
        }
    }

    private static void add(Map<UUID, Double> received, ActiveBattlePokemon foe, double dmg) {
        BattlePokemon foeMon = foe == null ? null : foe.getBattlePokemon();
        if (foeMon == null) return;
        received.merge(foeMon.getUuid(), dmg, Double::sum);
    }

    // slots read foes from separate lists, so match by mon uuid, not ActiveBattlePokemon instance.
    private static AiData.TargetDamage perTarget(AiData.ScoredMove move, ActiveBattlePokemon target) {
        if (target == null) return null;
        BattlePokemon targetMon = target.getBattlePokemon();
        UUID id = targetMon == null ? null : targetMon.getUuid();
        for (AiData.TargetDamage td : move.perTarget) {
            if (td.target == target) return td;
            BattlePokemon tdMon = td.target == null ? null : td.target.getBattlePokemon();
            if (id != null && tdMon != null && id.equals(tdMon.getUuid())) return td;
        }
        return null;
    }

    // damaging moves are counted in the pooled ko/chip; only non-damaging adds its utility delta here.
    private static double utilityValue(Choice choice) {
        if (choice.move == null || choice.move.isDamaging()) return 0;
        return choice.move.score - 100.0;
    }

    // cost of a spread move landing on our own ally, scaled by hit; a friendly ko is all but forbidden.
    // estimate carries the current field, so only the field delta is applied on top.
    private static double friendlyFire(Slot caster, Choice choice, Slot other,
                                       String curWeather, String curTerrain, String effWeather, String effTerrain,
                                       BattleMemory memory) {
        if (choice.move == null || !choice.move.hitsAlly || choice.move.template == null) return 0;
        BattlePokemon allyMon = other.mon;
        if (allyMon == null) return 0;
        double dmg = DamageCalc.estimate(caster.mon, allyMon, choice.move.template, memory)
            * fieldDelta(choice.move.template.getElementalType(), curWeather, curTerrain, effWeather, effTerrain, caster.grounded);
        if (dmg <= 0) return 0;
        if (dmg >= allyMon.getHealth()) return 12;
        return Math.min(8, dmg / Math.max(1, allyMon.getMaxHealth()) * 12);
    }

    // ratio of damage under the resulting field vs the current one; base damage already includes the
    // current field, so multiplying by this avoids double-counting weather/terrain.
    private static double fieldDelta(ElementalType type, String curWeather, String curTerrain,
                                     String effWeather, String effTerrain, boolean grounded) {
        double cur = FieldEffect.damageMultiplier(type, curWeather, curTerrain, grounded);
        double eff = FieldEffect.damageMultiplier(type, effWeather, effTerrain, grounded);
        return cur <= 0 ? eff : eff / cur;
    }

    // field this action would establish, from a switch-in's entry ability or a weather/terrain move.
    // returns {weather, terrain}, either entry null when unchanged.
    private static String[] fieldSet(Choice choice) {
        if (choice.switchTo != null) {
            String ability = abilityName(choice.switchTo);
            return new String[]{ FieldEffect.weatherFromAbility(ability), FieldEffect.terrainFromAbility(ability) };
        }
        if (choice.move != null && choice.move.template != null) {
            String id = choice.move.template.getName();
            return new String[]{ FieldEffect.weatherFromMove(id), FieldEffect.terrainFromMove(id) };
        }
        return new String[]{ null, null };
    }

    // fake out aimed at a foe the scoring layer already confirmed the flinch lands on. per-target, so
    // retargeting onto the foe without inner focus is credited and the one with it isn't.
    private static void collectFlinch(Choice choice, Set<UUID> flinched) {
        if (choice.move == null) return;
        AiData.TargetDamage td = perTarget(choice.move, choice.target);
        BattlePokemon foe = td == null || !td.flinches ? null : td.target.getBattlePokemon();
        if (foe != null) flinched.add(foe.getUuid());
    }

    private static boolean isProtect(Choice choice) {
        if (choice.move == null || choice.move.template == null) return false;
        return MoveData.PROTECT_MOVES.contains(FieldState.normalize(choice.move.template.getName()));
    }

    private static boolean isHelpingHand(Choice choice) {
        return choice.move != null && choice.move.template != null
            && "helpinghand".equals(FieldState.normalize(choice.move.template.getName()));
    }

    private static String abilityName(BattlePokemon mon) {
        var ability = mon.getOriginalPokemon().getAbility();
        return ability == null ? "" : ability.getName();
    }
}
