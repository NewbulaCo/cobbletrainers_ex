package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;

import java.util.Map;

// what field a switch-in or move puts up, and how a field scales damage, for the doubles planner.
// predicted from public info (bench abilities, move tables); ids normalized to match FieldState.
public final class FieldEffect {

    private FieldEffect() {}

    // ability -> weather set on entry (primal pair map to the same damage-relevant weather).
    private static final Map<String, String> WEATHER_ABILITY = Map.of(
        "drizzle", "raindance", "primordialsea", "raindance",
        "drought", "sunnyday", "desolateland", "sunnyday",
        "sandstream", "sandstorm", "snowwarning", "snow");
    // ability -> terrain set on entry.
    private static final Map<String, String> TERRAIN_ABILITY = Map.of(
        "electricsurge", "electricterrain", "grassysurge", "grassyterrain",
        "psychicsurge", "psychicterrain", "mistysurge", "mistyterrain");
    // move id -> terrain set. weather-setting moves live in MoveData.WEATHER_SETUP instead.
    private static final Map<String, String> TERRAIN_MOVE = Map.of(
        "electricterrain", "electricterrain", "grassyterrain", "grassyterrain",
        "psychicterrain", "psychicterrain", "mistyterrain", "mistyterrain");

    public static String weatherFromAbility(String ability) {
        return ability == null ? null : WEATHER_ABILITY.get(FieldState.normalize(ability));
    }

    public static String terrainFromAbility(String ability) {
        return ability == null ? null : TERRAIN_ABILITY.get(FieldState.normalize(ability));
    }

    public static String weatherFromMove(String moveId) {
        if (moveId == null) return null;
        String weather = MoveData.WEATHER_SETUP.get(FieldState.normalize(moveId));
        return weather == null ? null : FieldState.normalize(weather);
    }

    public static String terrainFromMove(String moveId) {
        return moveId == null ? null : TERRAIN_MOVE.get(FieldState.normalize(moveId));
    }

    // damage multiplier under weather+terrain; terrain only lifts a grounded attacker's matching type.
    public static double damageMultiplier(ElementalType moveType, String weather, String terrain, boolean attackerGrounded) {
        if (moveType == null) return 1.0;
        double mult = weatherMultiplier(moveType, weather);
        if (attackerGrounded) mult *= terrainMultiplier(moveType, terrain);
        return mult;
    }

    private static double weatherMultiplier(ElementalType moveType, String weather) {
        if (weather == null) return 1.0;
        ElementalTypes t = ElementalTypes.INSTANCE;
        if (weather.equals("raindance")) {
            if (moveType == t.getWATER()) return 1.5;
            if (moveType == t.getFIRE()) return 0.5;
        } else if (weather.equals("sunnyday")) {
            if (moveType == t.getFIRE()) return 1.5;
            if (moveType == t.getWATER()) return 0.5;
        }
        return 1.0;
    }

    private static double terrainMultiplier(ElementalType moveType, String terrain) {
        if (terrain == null) return 1.0;
        ElementalTypes t = ElementalTypes.INSTANCE;
        if (terrain.equals("electricterrain") && moveType == t.getELECTRIC()) return 1.3;
        if (terrain.equals("grassyterrain") && moveType == t.getGRASS()) return 1.3;
        if (terrain.equals("psychicterrain") && moveType == t.getPSYCHIC()) return 1.3;
        return 1.0;
    }

    // not grounded (floats over terrain and ground moves) if flying type or levitate.
    public static boolean grounded(LiveTyping types, String ability) {
        return !types.has(ElementalTypes.INSTANCE.getFLYING()) && !"levitate".equals(ability);
    }
}
