package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.battles.interpreter.BattleContext;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.categories.DamageCategories;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.pokemon.status.Statuses;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.BattleSide;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.status.PersistentStatusContainer;

import java.util.Collection;
import java.util.Map;

// conservative damage / turn-order math where min-roll estimates so KO detection never over-promises.
// simplified: no crits, no forme stats, flat 1.5 stab, accuracy scored elsewhere (KO stays on-hit).
public final class DamageCalc {

    private DamageCalc() {}

    private static final double MIN_ROLL = 0.85;

    // expected damage in hp; status/null moves return 0. uses live typing for eff and stab.
    public static double estimate(BattlePokemon attacker, BattlePokemon defender, MoveTemplate move, BattleMemory memory) {
        if (move == null) return 0;
        String category = move.getDamageCategory().getName();
        if (category.equals(DamageCategories.INSTANCE.getSTATUS().getName())) return 0;
        boolean physical = category.equals(DamageCategories.INSTANCE.getPHYSICAL().getName());

        Pokemon atk = attacker.getOriginalPokemon();
        Pokemon def = defender.getOriginalPokemon();
        Stats atkStatId = physical ? Stats.ATTACK : Stats.SPECIAL_ATTACK;
        Stats defStatId = physical ? Stats.DEFENCE : Stats.SPECIAL_DEFENCE;

        // base stats scaled by live stat stages
        double atkStat = staged(atk.getStat(atkStatId), stage(attacker, atkStatId));
        double defStat = staged(def.getStat(defStatId), stage(defender, defStatId));
        if (defStat < 1) defStat = 1;

        double base = (((2.0 * atk.getLevel()) / 5.0) * move.getPower() * (atkStat / defStat)) / 50.0 + 2.0;

        ElementalType moveType = move.getElementalType();
        double eff = TypeChart.abilityAware(moveType, memory.liveTyping(defender).defensive(), def.getAbility());
        boolean stab = memory.liveTyping(attacker).grantsStab(moveType);
        double burn = (physical && isBurned(attacker)) ? 0.5 : 1.0;

        return base * eff * (stab ? 1.5 : 1.0) * burn
            * weatherMultiplier(attacker, moveType, memory)
            * screenMultiplier(defender, physical)
            * MIN_ROLL * MoveData.expectedHits(move.getName());
    }

    // stage multiplier for offensive/defensive stats, clamped to [-6, 6]
    private static double staged(int stat, int stage) {
        int s = Math.max(-6, Math.min(6, stage));
        return stat * (s >= 0 ? (2.0 + s) / 2.0 : 2.0 / (2.0 - s));
    }

    private static int stage(BattlePokemon mon, Stats stat) {
        return mon.getStatChanges().getOrDefault(stat, 0);
    }

    // weather/terrain scaling off the live field; neutral when off-field or clear
    private static double weatherMultiplier(BattlePokemon attacker, ElementalType moveType, BattleMemory memory) {
        BattleActor actor = attacker.getActor();
        PokemonBattle battle = actor == null ? null : actor.getBattle();
        if (battle == null) return 1.0;
        String weather = FieldState.fieldId(battle, BattleContext.Type.WEATHER);
        String terrain = FieldState.fieldId(battle, BattleContext.Type.TERRAIN);
        if (weather == null && terrain == null) return 1.0;
        String ability = attacker.getOriginalPokemon().getAbility() == null ? "" : attacker.getOriginalPokemon().getAbility().getName();
        boolean grounded = FieldEffect.grounded(memory.liveTyping(attacker), ability);
        return FieldEffect.damageMultiplier(moveType, weather, terrain, grounded);
    }

    // screens halving on defender's side (doubles is really 2/3 close enough lol)
    private static double screenMultiplier(BattlePokemon defender, boolean physical) {
        BattleActor actor = defender.getActor();
        BattleSide side = actor == null ? null : actor.getSide();
        if (side == null) return 1.0;
        if (FieldState.sideHas(side, BattleContext.Type.SCREEN, "auroraveil")) return 0.5;
        return FieldState.sideHas(side, BattleContext.Type.SCREEN, physical ? "reflect" : "lightscreen") ? 0.5 : 1.0;
    }

    public static boolean isBurned(BattlePokemon pokemon) {
        PersistentStatusContainer status = pokemon.getEffectedPokemon().getStatus();
        return status != null && !status.isExpired() && status.getStatus().equals(Statuses.INSTANCE.getBURN());
    }

    // hits needed to drop targetHp; MAX_VALUE if it can't dent it
    public static int hitsToKO(double perHitDamage, int targetHp) {
        if (perHitDamage <= 0) return Integer.MAX_VALUE;
        return (int) Math.ceil(targetHp / perHitDamage);
    }

    // self resolves before foe: priority first, then speed. ties go to self.
    public static boolean strikesFirst(BattlePokemon self, MoveTemplate selfMove, BattlePokemon foe, MoveTemplate foeMove) {
        int selfPriority = selfMove == null ? 0 : selfMove.getPriority();
        int foePriority = foeMove == null ? 0 : foeMove.getPriority();
        if (selfPriority != foePriority) return selfPriority > foePriority;
        double selfSpeed = effectiveSpeed(self), foeSpeed = effectiveSpeed(foe);
        return trickRoom(self) ? selfSpeed <= foeSpeed : selfSpeed >= foeSpeed;
    }

    // does a act before b at equal priority? trick room flips the comparison for both sides.
    public static boolean outspeeds(BattlePokemon a, BattlePokemon b) {
        double sa = effectiveSpeed(a), sb = effectiveSpeed(b);
        return trickRoom(a) ? sa < sb : sa > sb;
    }

    // --- speed ---

    // abilities that double speed in their own weather
    private static final Map<String, String> SPEED_WEATHER_ABILITY = Map.of(
        "swiftswim", "raindance", "chlorophyll", "sunnyday",
        "sandrush", "sandstorm", "slushrush", "snow");

    // in-battle speed: base stat through its stat stages, then paralysis, the abilities that swing it,
    // and tailwind. trick room is not a multiplier, it flips the comparison, so it stays in the two
    // callers above. items are left out on purpose: nothing public pins down a choice scarf. abilities
    // are read as known, the same assumption every other layer makes about a species.
    public static double effectiveSpeed(BattlePokemon mon) {
        Pokemon p = mon.getOriginalPokemon();
        double speed = staged(p.getStat(Stats.SPEED), stage(mon, Stats.SPEED));
        String ability = p.getAbility() == null ? "" : p.getAbility().getName();
        PersistentStatusContainer status = mon.getEffectedPokemon().getStatus();
        boolean statused = status != null && !status.isExpired();
        // quick feet trades the paralysis speed cut for a bigger boost off any status.
        if (statused && ability.equals("quickfeet")) speed *= 1.5;
        else if (statused && status.getStatus().equals(Statuses.INSTANCE.getPARALYSIS())) speed *= 0.5;
        if (weatherSuits(mon, ability)) speed *= 2.0;
        if (hasVolatile(mon, "slowstart")) speed *= 0.5;
        if (tailwind(mon)) speed *= 2.0;
        return speed;
    }

    public static boolean trickRoom(BattlePokemon mon) {
        PokemonBattle battle = battleOf(mon);
        return battle != null && "trickroom".equals(FieldState.fieldId(battle, BattleContext.Type.ROOM));
    }

    // is the weather this mon's speed ability wants on the field? slush rush takes hail as snow.
    private static boolean weatherSuits(BattlePokemon mon, String ability) {
        String wanted = SPEED_WEATHER_ABILITY.get(FieldState.normalize(ability));
        if (wanted == null) return false;
        PokemonBattle battle = battleOf(mon);
        String weather = battle == null ? null : FieldState.fieldId(battle, BattleContext.Type.WEATHER);
        if (weather == null) return false;
        return weather.equals(wanted) || (wanted.equals("snow") && weather.equals("hail"));
    }

    private static boolean tailwind(BattlePokemon mon) {
        BattleActor actor = mon.getActor();
        BattleSide side = actor == null ? null : actor.getSide();
        return side != null && FieldState.sideHas(side, BattleContext.Type.TAILWIND, "tailwind");
    }

    private static boolean hasVolatile(BattlePokemon mon, String id) {
        Collection<BattleContext> volatiles = mon.getContextManager().get(BattleContext.Type.VOLATILE);
        if (volatiles == null) return false;
        for (BattleContext c : volatiles) {
            if (FieldState.normalize(c.getId()).equals(id)) return true;
        }
        return false;
    }

    private static PokemonBattle battleOf(BattlePokemon mon) {
        BattleActor actor = mon == null ? null : mon.getActor();
        return actor == null ? null : actor.getBattle();
    }
}
