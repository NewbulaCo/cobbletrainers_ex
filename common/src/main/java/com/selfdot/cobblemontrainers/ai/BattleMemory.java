package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// live per-battle facts.
// records only our own actions and public field/revealed state, never the opponent's hidden data.
public final class BattleMemory {

    private final Map<UUID, Integer> protectStreak = new HashMap<>();

    // mon that last committed an action in each slot, by pnx. a slot still holding someone else means
    // its current occupant has not acted yet, which is the window fake out and first impression need.
    private final Map<String, UUID> slotActed = new HashMap<>();

    // move types the foe's seen team can neutralize. hard = type-chart immunities (permanent); ability =
    // absorb abilities, suppressible by mold breaker so the caller treats them as a softer signal.
    private final Set<String> hardImmuneTypes = new HashSet<>();
    private final Set<String> abilityImmuneTypes = new HashSet<>();

    // moves foes used in the open (public |move| text), keyed per mon: some reads care who holds it
    // (feint through protect), others just that the team has it (hazard removal).
    private final Map<UUID, Set<String>> foeRevealedMoves = new HashMap<>();

    // toxic-spikes read on foes seen: they bite only grounded poiosonable targets, and a grounded poison
    // type clears the layers on entry.
    private final Set<UUID> seenFoes = new HashSet<>();
    private int toxicSpikesProofFoes;
    private boolean toxicSpikesAbsorberSeen;

    // healthy foe mons pivoted out: a switch-happy opponent we can bait with hazards/setup.
    private int foeVoluntarySwitches;

    // in-battle type changes by uuid, rebuilt from public |terastallize|/|-start| broadcasts. tera is
    // permanent; volatile (soak/conversion) reverts on switch; an empty list is a typeless mon.
    private final Map<UUID, ElementalType> teraType = new HashMap<>();
    private final Map<UUID, List<ElementalType>> volatileTypes = new HashMap<>();

    public int protectStreak(UUID uuid) {
        return uuid == null ? 0 : protectStreak.getOrDefault(uuid, 0);
    }

    // has this mon yet to act since arriving in the slot? unseen slots read as fresh, which is right
    // for the battle's opening leads.
    public boolean firstTurnOut(String pnx, UUID uuid) {
        return uuid != null && !uuid.equals(slotActed.get(pnx));
    }

    public void recordSlotAction(String pnx, UUID uuid) {
        if (pnx != null && uuid != null) slotActed.put(pnx, uuid);
    }

    public void recordHardImmunity(ElementalType type) {
        if (type != null) hardImmuneTypes.add(type.getName());
    }

    public void recordAbilityImmunity(ElementalType type) {
        if (type != null) abilityImmuneTypes.add(type.getName());
    }

    public boolean opponentHardImmune(ElementalType moveType) {
        return moveType != null && hardImmuneTypes.contains(moveType.getName());
    }

    public boolean opponentAbilityImmune(ElementalType moveType) {
        return moveType != null && abilityImmuneTypes.contains(moveType.getName());
    }

    // from the public |move| broadcast, never a moveset peek.
    public void recordFoeMove(UUID uuid, String moveId) {
        if (uuid == null || moveId == null) return;
        foeRevealedMoves.computeIfAbsent(uuid, k -> new HashSet<>()).add(moveId);
    }

    // counts even from a mon that has since switched out (it can return).
    public boolean foeRevealedHazardRemoval() {
        for (Set<String> shown : foeRevealedMoves.values()) {
            for (String id : shown) {
                if (MoveData.ANTI_HAZARDS.contains(id)) return true;
            }
        }
        return false;
    }

    public boolean foeRevealed(UUID uuid, Set<String> moveIds) {
        Set<String> shown = uuid == null ? null : foeRevealedMoves.get(uuid);
        if (shown == null) return false;
        for (String id : shown) {
            if (moveIds.contains(id)) return true;
        }
        return false;
    }

    public void recordSeenFoe(UUID uuid, boolean toxicSpikesProof, boolean toxicSpikesAbsorber) {
        if (uuid == null || !seenFoes.add(uuid)) return;
        if (toxicSpikesProof) toxicSpikesProofFoes++;
        if (toxicSpikesAbsorber) toxicSpikesAbsorberSeen = true;
    }

    public int seenFoeCount() {
        return seenFoes.size();
    }

    public int toxicSpikesProofFoeCount() {
        return toxicSpikesProofFoes;
    }

    public boolean toxicSpikesAbsorberSeen() {
        return toxicSpikesAbsorberSeen;
    }

    public void recordFoeVoluntarySwitch() {
        foeVoluntarySwitches++;
    }

    public int foeVoluntarySwitchCount() {
        return foeVoluntarySwitches;
    }

    // --- in-battle type changes ---

    public void recordTera(UUID uuid, ElementalType type) {
        if (uuid != null && type != null) teraType.put(uuid, type);
    }

    // full retype (soak/conversion/burn up); an empty list is stored as-is for a typeless mon.
    public void recordTypeChange(UUID uuid, List<ElementalType> types) {
        if (uuid != null && types != null) volatileTypes.put(uuid, List.copyOf(types));
    }

    // add one type (trick-or-treat) onto any volatile change already in force, else onto form types.
    public void recordTypeAdd(UUID uuid, ElementalType added, List<ElementalType> formTypes) {
        if (uuid == null || added == null) return;
        List<ElementalType> base = volatileTypes.getOrDefault(uuid, formTypes);
        if (base.contains(added)) return;
        List<ElementalType> next = new ArrayList<>(base);
        next.add(added);
        volatileTypes.put(uuid, List.copyOf(next));
    }

    // switch-out
    public void clearVolatileTypes(UUID uuid) {
        if (uuid != null) volatileTypes.remove(uuid);
    }

    // current typing for scoring: tera > volatile > form.
    public LiveTyping liveTyping(BattlePokemon mon) {
        List<ElementalType> form = LiveTyping.formTypes(mon.getOriginalPokemon());
        UUID uuid = mon.getUuid();
        ElementalType tera = teraType.get(uuid);
        if (tera != null) {
            List<ElementalType> stab = new ArrayList<>(form);
            if (!stab.contains(tera)) stab.add(tera);
            return new LiveTyping(List.of(tera), List.copyOf(stab));
        }
        List<ElementalType> vol = volatileTypes.get(uuid);
        if (vol != null) return new LiveTyping(vol, vol);
        return new LiveTyping(form, form);
    }

    // lasting typing only (tera else form), so a one-turn soak isn't mistaken for a wall across switches.
    public LiveTyping permanentTyping(BattlePokemon mon) {
        ElementalType tera = teraType.get(mon.getUuid());
        if (tera != null) return new LiveTyping(List.of(tera), List.of(tera));
        List<ElementalType> form = LiveTyping.formTypes(mon.getOriginalPokemon());
        return new LiveTyping(form, form);
    }

    // protect chains extend the streak (each protect likelier to fail); any other move breaks it.
    public void recordMove(UUID uuid, String moveId) {
        if (uuid == null) return;
        if (moveId != null && MoveData.PROTECT_MOVES.contains(moveId)) {
            protectStreak.merge(uuid, 1, Integer::sum);
        } else {
            protectStreak.remove(uuid);
        }
    }
}
