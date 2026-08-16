package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.pokemon.status.Statuses;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// static move-knowledge tables keyed by showdown move id.
public final class MoveData {

    private MoveData() {}

    // move id -> {minHits, maxHits}
    public static final Map<String, int[]> MULTI_HIT = new HashMap<>();
    // move id -> status it inflicts
    public static final Map<String, String> STATUS_MOVES = new HashMap<>();
    // move id -> stat-stage deltas
    public static final Map<String, Map<Stats, Integer>> BOOST_FROM_MOVES = new HashMap<>();

    // move id -> weather it sets
    public static final Map<String, String> WEATHER_SETUP = Map.of(
        "chillyreception", "Snow", "hail", "Hail", "raindance", "RainDance",
        "sandstorm", "Sandstorm", "snowscape", "Snow", "sunnyday", "SunnyDay"
    );
    public static final Set<String> PIVOT = Set.of(
        "uturn", "flipturn", "partingshot", "batonpass", "chillyreception", "shedtail", "voltswitch", "teleport"
    );
    public static final Set<String> ENTRY_HAZARDS = Set.of("spikes", "stealthrock", "stickyweb", "toxicspikes");
    // moves that clear entry hazards off a side
    public static final Set<String> ANTI_HAZARDS = Set.of("rapidspin", "mortalspin", "defog", "tidyup", "courtchange");
    public static final Set<String> ANTI_BOOST = Set.of("clearsmog", "haze");
    // moves that bypass protect and its clones
    public static final Set<String> PROTECT_BYPASS = Set.of(
        "feint", "phantomforce", "shadowforce", "hyperspacefury", "hyperspacehole");
    public static final Set<String> SELF_RECOVERY = Set.of("healorder", "milkdrink", "recover", "rest", "roost", "slackoff", "softboiled");
    // single-turn protection moves (excludes wideguard/quickguard/endure)
    // todo: implement those exclusions.
    public static final Set<String> PROTECT_MOVES = Set.of(
        "protect", "detect", "kingsshield", "spikyshield", "banefulbunker", "obstruct", "silktrap", "burningbulwark", "maxguard");
    // moves that only land if the foe attacks this turn; scored with a random per-turn nudge
    public static final Set<String> PREDICTION_MOVES = Set.of("suckerpunch", "thunderclap", "upperhand");
    // moves that fail unless the user came in this turn
    public static final Set<String> FIRST_TURN_ONLY = Set.of("fakeout", "firstimpression");
    // abilities that shrug off fake out's flinch (shield dust eats the secondary outright)
    private static final Set<String> FLINCH_PROOF = Set.of("innerfocus", "shielddust");

    public static boolean flinchProof(String ability) {
        return ability != null && FLINCH_PROOF.contains(FieldState.normalize(ability));
    }

    // average hits for damage estimation; 2-5 averages 3.2
    public static double expectedHits(String moveId) {
        int[] range = MULTI_HIT.get(moveId);
        if (range == null) return 1.0;
        if (range[0] == 2 && range[1] == 5) return 3.2;
        return (range[0] + range[1]) / 2.0;
    }

    private static void mapTo(Map<String, String> map, String value, String... ids) {
        for (String id : ids) map.put(id, value);
    }

    private static void multiHit(int min, int max, String... ids) {
        for (String id : ids) MULTI_HIT.put(id, new int[]{min, max});
    }

    static {
        multiHit(2, 5, "armthrust", "barrage", "bonerush", "bulletseed", "cometpunch", "doubleslap",
            "furyattack", "furyswipes", "iciclespear", "pinmissile", "rockblast", "scaleshot",
            "spikecannon", "tailslap", "watershuriken");
        multiHit(2, 2, "bonemerang", "doublehit", "doubleironbash", "doublekick", "dragondarts",
            "dualchop", "dualwingbeat", "geargrind", "twinbeam", "twineedle");
        multiHit(3, 3, "surgingstrikes", "tripledive");
        multiHit(1, 3, "tripleaxel", "triplekick");
        multiHit(1, 10, "populationbomb");

        Statuses s = Statuses.INSTANCE;
        String brn = s.getBURN().getShowdownName();
        String par = s.getPARALYSIS().getShowdownName();
        String slp = s.getSLEEP().getShowdownName();
        String psn = s.getPOISON().getShowdownName();
        String tox = s.getPOISON_BADLY().getShowdownName();
        mapTo(STATUS_MOVES, brn, "willowisp", "scald", "scorchingsands");
        mapTo(STATUS_MOVES, par, "glare", "nuzzle", "stunspore", "thunderwave");
        mapTo(STATUS_MOVES, slp, "darkvoid", "hypnosis", "lovelykiss", "relicsong", "sing", "sleeppowder", "spore", "yawn");
        mapTo(STATUS_MOVES, "confusion", "chatter", "confuseray", "dynamicpunch", "flatter", "supersonic", "swagger", "sweetkiss", "teeterdance");
        mapTo(STATUS_MOVES, psn, "poisongas", "poisonpowder", "toxicthread");
        STATUS_MOVES.put("toxic", tox);
        STATUS_MOVES.put("curse", "cursed");
        STATUS_MOVES.put("leechseed", "leech");

        BOOST_FROM_MOVES.put("bellydrum", Map.of(Stats.ATTACK, 6));
        BOOST_FROM_MOVES.put("bulkup", Map.of(Stats.ATTACK, 1, Stats.DEFENCE, 1));
        BOOST_FROM_MOVES.put("clangoroussoul", Map.of(Stats.ATTACK, 1, Stats.DEFENCE, 1, Stats.SPECIAL_ATTACK, 1, Stats.SPECIAL_DEFENCE, 1, Stats.SPEED, 1));
        BOOST_FROM_MOVES.put("coil", Map.of(Stats.ATTACK, 1, Stats.DEFENCE, 1, Stats.ACCURACY, 1));
        BOOST_FROM_MOVES.put("dragondance", Map.of(Stats.ATTACK, 1, Stats.SPEED, 1));
        BOOST_FROM_MOVES.put("extremeevoboost", Map.of(Stats.ATTACK, 2, Stats.DEFENCE, 2, Stats.SPECIAL_ATTACK, 2, Stats.SPECIAL_DEFENCE, 2, Stats.SPEED, 2));
        BOOST_FROM_MOVES.put("clangoroussoulblaze", Map.of(Stats.ATTACK, 1, Stats.DEFENCE, 1, Stats.SPECIAL_ATTACK, 1, Stats.SPECIAL_DEFENCE, 1, Stats.SPEED, 1));
        BOOST_FROM_MOVES.put("filletaway", Map.of(Stats.ATTACK, 2, Stats.SPECIAL_ATTACK, 2, Stats.SPEED, 2));
        BOOST_FROM_MOVES.put("honeclaws", Map.of(Stats.ATTACK, 1, Stats.ACCURACY, 1));
        BOOST_FROM_MOVES.put("noretreat", Map.of(Stats.ATTACK, 1, Stats.DEFENCE, 1, Stats.SPECIAL_ATTACK, 1, Stats.SPECIAL_DEFENCE, 1, Stats.SPEED, 1));
        BOOST_FROM_MOVES.put("shellsmash", Map.of(Stats.ATTACK, 2, Stats.DEFENCE, -1, Stats.SPECIAL_ATTACK, 2, Stats.SPECIAL_DEFENCE, -1, Stats.SPEED, 2));
        BOOST_FROM_MOVES.put("shiftgear", Map.of(Stats.ATTACK, 1, Stats.SPEED, 2));
        BOOST_FROM_MOVES.put("swordsdance", Map.of(Stats.ATTACK, 2));
        BOOST_FROM_MOVES.put("tidyup", Map.of(Stats.ATTACK, 1, Stats.SPEED, 1));
        BOOST_FROM_MOVES.put("victorydance", Map.of(Stats.ATTACK, 1, Stats.DEFENCE, 1, Stats.SPEED, 1));
        BOOST_FROM_MOVES.put("acidarmor", Map.of(Stats.DEFENCE, 2));
        BOOST_FROM_MOVES.put("barrier", Map.of(Stats.DEFENCE, 2));
        BOOST_FROM_MOVES.put("cottonguard", Map.of(Stats.DEFENCE, 3));
        BOOST_FROM_MOVES.put("defensecurl", Map.of(Stats.DEFENCE, 1));
        BOOST_FROM_MOVES.put("irondefense", Map.of(Stats.DEFENCE, 2));
        BOOST_FROM_MOVES.put("shelter", Map.of(Stats.DEFENCE, 2, Stats.EVASION, 1));
        BOOST_FROM_MOVES.put("stockpile", Map.of(Stats.DEFENCE, 1, Stats.SPECIAL_DEFENCE, 1));
        BOOST_FROM_MOVES.put("stuffcheeks", Map.of(Stats.DEFENCE, 2));
        BOOST_FROM_MOVES.put("amnesia", Map.of(Stats.SPECIAL_DEFENCE, 2));
        BOOST_FROM_MOVES.put("calmmind", Map.of(Stats.SPECIAL_ATTACK, 1, Stats.SPECIAL_DEFENCE, 1));
        BOOST_FROM_MOVES.put("geomancy", Map.of(Stats.SPECIAL_ATTACK, 2, Stats.SPECIAL_DEFENCE, 2, Stats.SPEED, 2));
        BOOST_FROM_MOVES.put("nastyplot", Map.of(Stats.SPECIAL_ATTACK, 2));
        BOOST_FROM_MOVES.put("quiverdance", Map.of(Stats.SPECIAL_ATTACK, 1, Stats.SPECIAL_DEFENCE, 1, Stats.SPEED, 1));
        BOOST_FROM_MOVES.put("tailglow", Map.of(Stats.SPECIAL_ATTACK, 3));
        BOOST_FROM_MOVES.put("takeheart", Map.of(Stats.SPECIAL_ATTACK, 1, Stats.SPECIAL_DEFENCE, 1));
        BOOST_FROM_MOVES.put("agility", Map.of(Stats.SPEED, 2));
        BOOST_FROM_MOVES.put("autotomize", Map.of(Stats.SPEED, 2));
        BOOST_FROM_MOVES.put("rockpolish", Map.of(Stats.SPEED, 2));
        BOOST_FROM_MOVES.put("curse", Map.of(Stats.ATTACK, 1, Stats.DEFENCE, 1, Stats.SPEED, -1));
        BOOST_FROM_MOVES.put("minimize", Map.of(Stats.EVASION, 2));
    }
}
