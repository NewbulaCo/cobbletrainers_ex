package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.abilities.Ability;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// gen 6+ type effectiveness, attacker-keyed, plus the ability-immunity layer.
// shared source of truth for AI typing reasoning.
public final class TypeChart {

    private TypeChart() {}

    private static final ElementalType[] ORDER;
    private static final Map<ElementalType, Integer> INDEX = new HashMap<>();
    private static final Map<ElementalType, double[]> CHART = new HashMap<>();

    // ability id -> the move type that ability nullifies (absorb/immunity abilities)
    private static final Map<String, ElementalType> ABSORB = new HashMap<>();

    // ability id -> move type it redirects to itself (and absorbs) in doubles
    private static final Map<String, ElementalType> REDIRECT = new HashMap<>();

    static {
        ElementalTypes t = ElementalTypes.INSTANCE;
        ElementalType nrm = t.getNORMAL(), fir = t.getFIRE(), wtr = t.getWATER(), ele = t.getELECTRIC(),
            grs = t.getGRASS(), ice = t.getICE(), fig = t.getFIGHTING(), poi = t.getPOISON(),
            grd = t.getGROUND(), fly = t.getFLYING(), psy = t.getPSYCHIC(), bug = t.getBUG(),
            rck = t.getROCK(), gho = t.getGHOST(), drg = t.getDRAGON(), drk = t.getDARK(),
            stl = t.getSTEEL(), fai = t.getFAIRY();

        ORDER = new ElementalType[]{nrm, fir, wtr, ele, grs, ice, fig, poi, grd, fly, psy, bug, rck, gho, drg, drk, stl, fai};
        for (int i = 0; i < ORDER.length; i++) INDEX.put(ORDER[i], i);

        // rows are multipliers against the defender types in ORDER
        //                        nrm  fir  wtr  ele  grs  ice  fig  poi  grd  fly  psy  bug  rck  gho  drg  drk  stl  fai
        CHART.put(nrm, new double[]{1,   1,   1,   1,   1,   1,   1,   1,   1,   1,   1,   1,  0.5,  0,   1,   1,  0.5,  1});
        CHART.put(fir, new double[]{1,  0.5, 0.5,  1,   2,   2,   1,   1,   1,   1,   1,   2,  0.5,  1,  0.5,  1,   2,   1});
        CHART.put(wtr, new double[]{1,   2,  0.5,  1,  0.5,  1,   1,   1,   2,   1,   1,   1,   2,   1,  0.5,  1,   1,   1});
        CHART.put(ele, new double[]{1,   1,   2,  0.5, 0.5,  1,   1,   1,   0,   2,   1,   1,   1,   1,  0.5,  1,   1,   1});
        CHART.put(grs, new double[]{1,  0.5,  2,   1,  0.5,  1,   1,  0.5,  2,  0.5,  1,  0.5,  2,   1,  0.5,  1,  0.5,  1});
        CHART.put(ice, new double[]{1,  0.5, 0.5,  1,   2,  0.5,  1,   1,   2,   2,   1,   1,   1,   1,   2,   1,  0.5,  1});
        CHART.put(fig, new double[]{2,   1,   1,   1,   1,   2,   1,  0.5,  1,  0.5, 0.5, 0.5,  2,   0,   1,   2,   2,  0.5});
        CHART.put(poi, new double[]{1,   1,   1,   1,   2,   1,   1,  0.5, 0.5,  1,   1,   1,  0.5, 0.5,  1,   1,   0,   2});
        CHART.put(grd, new double[]{1,   2,   1,   2,  0.5,  1,   1,   2,   1,   0,   1,  0.5,  2,   1,   1,   1,   2,   1});
        CHART.put(fly, new double[]{1,   1,   1,  0.5,  2,   1,   2,   1,   1,   1,   1,   2,  0.5,  1,   1,   1,  0.5,  1});
        CHART.put(psy, new double[]{1,   1,   1,   1,   1,   1,   2,   2,   1,   1,  0.5,  1,   1,   1,   1,   0,  0.5,  1});
        CHART.put(bug, new double[]{1,  0.5,  1,   1,   2,   1,  0.5, 0.5,  1,  0.5,  2,   1,   1,  0.5,  1,   2,  0.5, 0.5});
        CHART.put(rck, new double[]{1,   2,   1,   1,   1,   2,  0.5,  1,  0.5,  2,   1,   2,   1,   1,   1,   1,  0.5,  1});
        CHART.put(gho, new double[]{0,   1,   1,   1,   1,   1,   1,   1,   1,   1,   2,   1,   1,   2,   1,  0.5,  1,   1});
        CHART.put(drg, new double[]{1,   1,   1,   1,   1,   1,   1,   1,   1,   1,   1,   1,   1,   1,   2,   1,  0.5,  0});
        CHART.put(drk, new double[]{1,   1,   1,   1,   1,   1,  0.5,  1,   1,   1,   2,   1,   1,   2,   1,  0.5,  1,  0.5});
        CHART.put(stl, new double[]{1,  0.5, 0.5, 0.5,  1,   2,   1,   1,   1,   1,   1,   1,   2,   1,   1,   1,  0.5,  2});
        CHART.put(fai, new double[]{1,  0.5,  1,   1,   1,   1,   2,  0.5,  1,   1,   1,   1,   1,   1,   2,   2,  0.5,  1});

        ABSORB.put("waterabsorb", wtr);
        ABSORB.put("stormdrain", wtr);
        ABSORB.put("dryskin", wtr);
        ABSORB.put("voltabsorb", ele);
        ABSORB.put("lightningrod", ele);
        ABSORB.put("motordrive", ele);
        ABSORB.put("levitate", grd);
        ABSORB.put("eartheater", grd);
        ABSORB.put("flashfire", fir);
        ABSORB.put("wellbakedbody", fir);
        ABSORB.put("sapsipper", grs);

        REDIRECT.put("lightningrod", ele);
        REDIRECT.put("stormdrain", wtr);
    }

    // attacking types this defender is immune to purely by the type chart (permanent; absorbs handled separately)
    public static List<ElementalType> immuneAttackingTypes(ElementalType defPrimary, ElementalType defSecondary) {
        List<ElementalType> out = new ArrayList<>();
        for (ElementalType atk : ORDER) {
            if (effectiveness(atk, defPrimary, defSecondary) == 0.0) out.add(atk);
        }
        return out;
    }

    // move type this ability absorbs, or null if not an absorber
    public static ElementalType absorbedType(String ability) {
        return ability == null ? null : ABSORB.get(ability);
    }

    // does this ability redirect (and absorb) single-target moves of this type (doubles)
    public static boolean redirects(String ability, ElementalType moveType) {
        if (ability == null || moveType == null) return false;
        ElementalType t = REDIRECT.get(ability);
        return t != null && t.equals(moveType);
    }

    public static double multiplier(ElementalType attacking, ElementalType defending) {
        Integer di = INDEX.get(defending);
        double[] row = CHART.get(attacking);
        if (di == null || row == null) return 1.0;
        return row[di];
    }

    // combined effectiveness against a (possibly dual-typed) defender
    public static double effectiveness(ElementalType moveType, ElementalType defPrimary, ElementalType defSecondary) {
        double e = multiplier(moveType, defPrimary);
        if (defSecondary != null) e *= multiplier(moveType, defSecondary);
        return e;
    }

    // effectiveness vs live typing list; empty list = typeless, takes everything neutrally
    public static double effectiveness(ElementalType moveType, List<ElementalType> defTypes) {
        double e = 1.0;
        for (ElementalType t : defTypes) e *= multiplier(moveType, t);
        return e;
    }

    // effectiveness after defender ability; absorbs and wonder guard drop it to 0
    public static double abilityAware(ElementalType moveType, ElementalType defPrimary, ElementalType defSecondary, Ability defAbility) {
        String ability = defAbility == null ? null : defAbility.getName();
        if (ability != null) {
            ElementalType absorbed = ABSORB.get(ability);
            if (absorbed != null && absorbed.equals(moveType)) return 0.0;
        }
        double e = effectiveness(moveType, defPrimary, defSecondary);
        if ("wonderguard".equals(ability) && e <= 1.0) return 0.0;
        return e;
    }

    // list-typed variant
    public static double abilityAware(ElementalType moveType, List<ElementalType> defTypes, Ability defAbility) {
        String ability = defAbility == null ? null : defAbility.getName();
        if (ability != null) {
            ElementalType absorbed = ABSORB.get(ability);
            if (absorbed != null && absorbed.equals(moveType)) return 0.0;
        }
        double e = effectiveness(moveType, defTypes);
        if ("wonderguard".equals(ability) && e <= 1.0) return 0.0;
        return e;
    }

    // chart-only immunities for a live typing list
    public static List<ElementalType> immuneAttackingTypes(List<ElementalType> defTypes) {
        List<ElementalType> out = new ArrayList<>();
        for (ElementalType atk : ORDER) {
            if (effectiveness(atk, defTypes) == 0.0) out.add(atk);
        }
        return out;
    }
}
