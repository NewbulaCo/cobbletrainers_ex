package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.abilities.Ability;
import com.cobblemon.mod.common.api.battles.interpreter.BattleContext;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.moves.categories.DamageCategories;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.pokemon.status.Statuses;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.battles.*;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.selfdot.cobblemontrainers.CobblemonTrainers;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

// score-accumulation battle AI: moves start at 100, additive layers nudge the
// scores, highest wins with a random tie-break. difficulty = which layers are on (see solve()).
// honest by default, the omniscient tier reads the active foe's real moveset. deferred: item use and
// gimmick triggering (tera/dynamax/z), todo for now
@Slf4j
public class ScoreBattleAI implements BattleAI {

    private static final Random RANDOM = new Random();

    public static final int MAX_LEVEL = 11;
    public static final int OMNISCIENT_LEVEL = 11;
    
    // unspecified level: strong all-around honest AI, every honest layer on but not omniscience.
    private static final int DEFAULT_LEVEL = 6;
    private final int level;
    private final boolean omniscient;

    // battle-scoped so one AI instance == one battle.
    private final BattleMemory memory = new BattleMemory();
    private static final int DOUBLES_LEVEL = 10;

    // one ai drives both doubles slots: the lead plans the turn and stashes the ally's action here by
    // mon uuid for its own call to serve. plannedRequest scopes the stash to one request.
    private final Map<UUID, ShowdownActionResponse> plannedActions = new HashMap<>();
    private ShowdownActionRequest plannedRequest;

    // request we last cleared stale reservations for, so it runs once per request.
    private ShowdownActionRequest reservationRequest;

    public ScoreBattleAI() {
        this(DEFAULT_LEVEL);
    }

    public ScoreBattleAI(int level) {
        this.level = Math.max(0, Math.min(MAX_LEVEL, level));
        this.omniscient = this.level >= OMNISCIENT_LEVEL;
    }

    @NotNull
    @Override
    public ShowdownActionResponse choose(
        @NotNull ActiveBattlePokemon self,
        @Nullable ShowdownMoveset moveset,
        boolean forceSwitch
    ) {
        try {
            return dispatch(self, moveset, forceSwitch);
        } catch (Exception e) {
            // a bug should never cost a turn, so fall back to a real action.
            try {
                return safeFallback(self, moveset, forceSwitch);
            } catch (Exception ignored) {
                return PassActionResponse.INSTANCE;
            }
        }
    }

    // route a single slot's choice. forced switches and singles go straight to the solo pipeline. in
    // doubles coordination the lead slot plans both slots and caches the other's action; follower serves it.
    private ShowdownActionResponse dispatch(ActiveBattlePokemon self, ShowdownMoveset moveset, boolean forceSwitch) {
        clearStaleReservations(self);
        if (forceSwitch || self.isGone()) return chooseSwitch(self, firstLiveOpponent(self));
        if (moveset == null) return PassActionResponse.INSTANCE;

        AllySlot ally = level >= DOUBLES_LEVEL ? coChoosingAlly(self) : null;
        if (ally == null) return soloTurn(self, moveset); // singles / no live ally

        // follower slot: this request's plan was computed on the lead call.
        ShowdownActionRequest request = self.getActor().getRequest();
        if (request != null && request == plannedRequest) {
            BattlePokemon me = self.getBattlePokemon();
            ShowdownActionResponse served = me == null ? null : plannedActions.remove(me.getUuid());
            if (served != null) return served;
            return soloTurn(self, moveset); // defensive: nothing planned for us
        }

        // lead slot: plan this side's whole turn now, while both slots' state is clean.
        plannedActions.clear();
        plannedRequest = request;
        return leadPlan(self, moveset, ally);
    }

    // score one slot in isolation and pick its action, no side effects: runs for the lead and for its
    // ally on the lead's call, so it must not touch battle state or memory. commit applies the result.
    private Decision solve(ActiveBattlePokemon self, ShowdownMoveset moveset) {
        List<ActiveBattlePokemon> opponents = liveOpponents(self);
        ActiveBattlePokemon opponent = opponents.isEmpty() ? null : opponents.get(0);

        if (moveset == null) return Decision.other(PassActionResponse.INSTANCE, null);
        List<InBattleMove> usable = usableMoves(self, moveset);
        if (usable.isEmpty()) return Decision.other(new MoveActionResponse("struggle", null, null), "struggle");
        if (usable.size() == 1 && usable.get(0).getId().equals("recharge")) {
            return Decision.other(new MoveActionResponse("recharge", null, null), "recharge");
        }

        AiData data = AiData.build(self, usable, opponents, memory);
        recordSeenImmunities(data);
        // omniscient only: foe's real best hit on us, -1 when honest.
        double omniThreat = omniscient ? foeBestOnSelf(data) : -1;
        boolean omniThreatens = omniThreat >= 0 && data.self != null && omniThreat >= data.self.getHealth();
        // tier 9+: the foe is cornered and will probably pivot, giving us a free turn (not when a hit
        // we now know is lethal makes staying in its own gamble).
        boolean foeMaySwitch = level >= 9 && !omniThreatens && foeLikelyToSwitch(data);

        boolean fresh = firstTurnOut(data);
        checkBadMove(data);                                  // tier 0: don't click into immunities
        firstTurnMoveScore(data, fresh);                     // tier 0: fake out only works on arrival
        if (level >= 1) checkViability(data);                // pick the best damaging move
        if (level >= 2) preferStrongest(data);               // value OHKO / 2HKO
        if (level >= 2) accuracyScore(data);                 // prefer reliable moves
        if (level >= 3) tryToFaint(data);                    // secure the KO, speed-aware
        if (level >= 4) selfRecoveryScore(data);             // heal when it helps
        if (level >= 5) statusMoveScore(data);               // use status
        if (level >= 6) antiBoostScore(data);                // punish a boosted foe
        if (level >= 7) setupBoostScore(data, foeMaySwitch); // set up when safe, or on a baited switch
        if (level >= 8) {                                    // field control
            screenScore(data);
            hazardScore(data, foeMaySwitch);                 // bait the switch into hazards
            hazardRemovalScore(data);                        // sweep our own side
            weatherRoomScore(data);
            fakeOutScore(data, fresh);                       // the flinch is a free turn
            protectScore(data, omniThreat, flinchReady(data));
            predictionMoveScore(data);                       // commit to the sucker-punch read
        }
        if (level >= 9) antiWallMemoryScore(data);           // don't feed a known immune/absorber mon

        // voluntary switching (tier 9+): don't flee a good matchup or a productive utility play.
        // omniscient also flees a hit we now know will KO us. a landing fake out takes the foe's turn
        // away, so its threat stops counting; only being walled still sends us out.
        boolean wantsOut = level >= 9 && opponent != null
            && SwitchPlanner.shouldSwitchOut(data, moveset.getTrapped(), omniThreatens, flinchReady(data));

        if (level >= 9) pivotScore(data, wantsOut);          // leave on our own terms, acting on the way
        // doubles coordination isn't per-slot DoublesPlanner runs across both slots on the lead call.

        // a raw switch spends the whole turn: only when no pivot can do the same job and still hit.
        if (wantsOut && !hasGoodUtilityMove(data) && !hasGoodPivot(data)) {
            BattlePokemon replacement = SwitchPlanner.chooseReplacement(self, opponent.getBattlePokemon(), false, memory);
            if (replacement != null) return Decision.voluntarySwitch(replacement, data);
        }

        AiData.ScoredMove chosen = pickHighest(data.moves);
        return Decision.move(chosen, chosen.target, data);
    }

    // decide and commit one slot on its own. singles, and whenever coordination doesn't apply.
    private ShowdownActionResponse soloTurn(ActiveBattlePokemon self, ShowdownMoveset moveset) {
        Decision dec = solve(self, moveset);
        DoublesPlanner.Choice choice = dec.toChoice();
        ShowdownActionResponse response = commit(self, choice);
        debugChoice(self, dec.data, choice);
        return response;
    }

    // --- doubles coordination ---

    // on the lead call, score both slots, look for a coordinated override, then commit each once.
    // the ally's response is cached for its own call to serve.
    private ShowdownActionResponse leadPlan(ActiveBattlePokemon self, ShowdownMoveset moveset, AllySlot ally) {
        Decision selfDec = solve(self, moveset);
        // reserve the lead's switch before the ally decides so both slots can't pick the same mon.
        reserveSwitch(selfDec);
        Decision allyDec = solve(ally.active, ally.moveset);
        DoublesPlanner.Choice selfChoice = selfDec.toChoice();
        DoublesPlanner.Choice allyChoice = allyDec.toChoice();

        boolean coordinated = false;
        // only attacking mons can combo; a slot switching or struggling keeps its own decision.
        if (selfDec.kind == Kind.MOVE && allyDec.kind == Kind.MOVE) {
            DoublesPlanner.Plan plan = coordinate(self, selfDec, ally.active, allyDec);
            if (plan != null) {
                selfChoice = plan.lead;
                allyChoice = plan.ally;
                coordinated = true;
            }
        }

        cacheFor(ally.active, commit(ally.active, allyChoice));
        ShowdownActionResponse selfResponse = commit(self, selfChoice);
        debugPlan(self, selfDec.data, selfChoice, ally.active, allyDec.data, allyChoice, coordinated);
        return selfResponse;
    }

    // flag a switch's incoming mon so a later replacement search skips it.
    private void reserveSwitch(Decision dec) {
        if (dec.kind == Kind.SWITCH && dec.switchTarget != null) dec.switchTarget.setWillBeSwitchedIn(true);
    }

    private DoublesPlanner.Plan coordinate(ActiveBattlePokemon self, Decision selfDec,
                                           ActiveBattlePokemon allyActive, Decision allyDec) {
        List<ActiveBattlePokemon> foes = liveOpponents(self);
        String weather = FieldState.fieldId(self, BattleContext.Type.WEATHER);
        String terrain = FieldState.fieldId(self, BattleContext.Type.TERRAIN);
        DoublesPlanner.Slot lead = buildSlot(self, selfDec);
        DoublesPlanner.Slot ally = buildSlot(allyActive, allyDec);
        // a timer running for the foe's side is worth stalling out with both slots at once.
        boolean burnTimer = foeTempAdvantage(selfDec.data) || foeTempAdvantage(allyDec.data);
        return DoublesPlanner.plan(lead, ally, foes, weather, terrain, memory, burnTimer);
    }

    private DoublesPlanner.Slot buildSlot(ActiveBattlePokemon active, Decision dec) {
        BattlePokemon mon = active.getBattlePokemon();
        boolean grounded = mon != null
            && FieldEffect.grounded(memory.liveTyping(mon), abilityName(mon.getOriginalPokemon()));
        return new DoublesPlanner.Slot(active, dec.data, grounded, fieldSwitchIns(active), dec.toChoice());
    }

    // bench mons whose entry ability sets weather or terrain: the switches worth coordinating on.
    private List<BattlePokemon> fieldSwitchIns(ActiveBattlePokemon slot) {
        List<BattlePokemon> out = new ArrayList<>();
        for (BattlePokemon p : slot.getActor().getPokemonList()) {
            if (!p.canBeSentOut()) continue;
            String ability = abilityName(p.getOriginalPokemon());
            if (FieldEffect.weatherFromAbility(ability) != null || FieldEffect.terrainFromAbility(ability) != null) {
                out.add(p);
            }
        }
        return out;
    }

    // apply a final action and turn it into a response. switches flag the incoming mon; every action
    // records what this slot committed to (protect-streak tracking, and that the mon has now acted).
    private ShowdownActionResponse commit(ActiveBattlePokemon slot, DoublesPlanner.Choice choice) {
        BattlePokemon me = slot.getBattlePokemon();
        UUID uuid = me == null ? null : me.getUuid();
        memory.recordSlotAction(slot.getPNX(), uuid);
        if (choice.switchTo != null) {
            choice.switchTo.setWillBeSwitchedIn(true);
            if (uuid != null) memory.recordMove(uuid, null);
            return new SwitchActionResponse(choice.switchTo.getUuid());
        }
        if (choice.rawResponse != null) {
            if (uuid != null) memory.recordMove(uuid, choice.moveId);
            return choice.rawResponse;
        }
        String id = choice.move.inMove.getId();
        if (uuid != null) memory.recordMove(uuid, id);
        return new MoveActionResponse(id, pnxForMove(slot, choice.move.inMove, choice.target), null);
    }

    private void cacheFor(ActiveBattlePokemon slot, ShowdownActionResponse response) {
        BattlePokemon me = slot.getBattlePokemon();
        if (me != null && response != null) plannedActions.put(me.getUuid(), response);
    }

    // the other slot on our actor choosing a move this turn (not gone, not force-switching), paired
    // with its real in-battle moveset. null in singles.
    private AllySlot coChoosingAlly(ActiveBattlePokemon self) {
        BattleActor actor = self.getActor();
        ShowdownActionRequest request = actor.getRequest();
        if (request == null) return null;
        List<ActiveBattlePokemon> actives = actor.getActivePokemon();
        List<ShowdownMoveset> movesets = request.getActive();
        List<Boolean> forced = request.getForceSwitch();
        if (actives == null || movesets == null) return null;
        for (int i = 0; i < actives.size(); i++) {
            ActiveBattlePokemon a = actives.get(i);
            if (a == self || a == null || a.isGone() || a.getBattlePokemon() == null) continue;
            if (forced != null && i < forced.size() && Boolean.TRUE.equals(forced.get(i))) continue;
            ShowdownMoveset ms = i < movesets.size() ? movesets.get(i) : null;
            if (ms != null) return new AllySlot(a, ms);
        }
        return null;
    }

    private static final class AllySlot {
        final ActiveBattlePokemon active;
        final ShowdownMoveset moveset;

        AllySlot(ActiveBattlePokemon active, ShowdownMoveset moveset) {
            this.active = active;
            this.moveset = moveset;
        }
    }

    private enum Kind { MOVE, SWITCH, OTHER }

    // a slot's solved action before commit. MOVE and SWITCH can combo; OTHER (struggle/recharge/pass)
    // carries a ready response and never coordinates.
    private static final class Decision {
        final Kind kind;
        final AiData data;
        final AiData.ScoredMove move;
        final ActiveBattlePokemon target;
        final BattlePokemon switchTarget;
        final ShowdownActionResponse rawResponse;
        final String moveId;

        private Decision(Kind kind, AiData data, AiData.ScoredMove move, ActiveBattlePokemon target,
                         BattlePokemon switchTarget, ShowdownActionResponse rawResponse, String moveId) {
            this.kind = kind;
            this.data = data;
            this.move = move;
            this.target = target;
            this.switchTarget = switchTarget;
            this.rawResponse = rawResponse;
            this.moveId = moveId;
        }

        static Decision move(AiData.ScoredMove move, ActiveBattlePokemon target, AiData data) {
            return new Decision(Kind.MOVE, data, move, target, null, null, move.inMove.getId());
        }

        static Decision voluntarySwitch(BattlePokemon switchTarget, AiData data) {
            return new Decision(Kind.SWITCH, data, null, null, switchTarget, null, null);
        }

        static Decision other(ShowdownActionResponse response, String moveId) {
            return new Decision(Kind.OTHER, null, null, null, null, response, moveId);
        }

        DoublesPlanner.Choice toChoice() {
            if (kind == Kind.MOVE) return DoublesPlanner.Choice.move(move, target);
            if (kind == Kind.SWITCH) return DoublesPlanner.Choice.switchTo(switchTarget);
            return DoublesPlanner.Choice.raw(rawResponse, moveId);
        }
    }

    // --- scoring layers ---

    // penalize moves that do little or nothing against the current foe.
    private void checkBadMove(AiData data) {
        if (data.opponent == null) return;
        for (AiData.ScoredMove m : data.moves) {
            if (!m.isDamaging()) continue;
            if (m.effectiveness == 0.0) m.score -= 20;
            else if (m.effectiveness <= 0.25) m.score -= 10;
            else if (m.effectiveness <= 0.5) m.score -= 2;
        }
    }

    // mold-breaker-class abilities ignore the target's ability, so ability-based immunities
    // (lightningrod, levitate, ...) don't stop us. type-chart immunities are unaffected.
    private static final Set<String> MOLD_BREAKERS = Set.of("moldbreaker", "teravolt", "turboblaze");

    // foe revealed a mon that walls this move's type and can switch it back in: devalue the move. type
    // immunities are a hard penalty, ability immunities softer and only when in force. spread moves exempt.
    private void antiWallMemoryScore(AiData data) {
        boolean abilityWallsLive = abilityImmunitiesApply(data);
        for (AiData.ScoredMove m : data.moves) {
            if (!m.isDamaging() || m.spread || m.template == null) continue;
            if (m.effectiveness <= 0) continue; // already walled vs current foe; checkBadMove has it
            ElementalType type = m.template.getElementalType();
            if (memory.opponentHardImmune(type)) m.score -= 6;
            else if (abilityWallsLive && memory.opponentAbilityImmune(type)) m.score -= 5;
        }
    }

    // are ability-based immunities in force this turn? our mold breaker ignores them, and a
    // neutralizing gas mon switches every ability off. judged only from what's visible now.
    private boolean abilityImmunitiesApply(AiData data) {
        Ability mine = data.self == null ? null : data.self.getOriginalPokemon().getAbility();
        if (mine != null && MOLD_BREAKERS.contains(mine.getName())) return false;
        return !neutralizingGasActive(data);
    }

    private boolean neutralizingGasActive(AiData data) {
        if (isNeutralizingGas(data.self)) return true;
        for (ActiveBattlePokemon foeActive : data.opponents) {
            if (isNeutralizingGas(foeActive.getBattlePokemon())) return true;
        }
        return false;
    }

    private boolean isNeutralizingGas(BattlePokemon mon) {
        if (mon == null) return false;
        Ability ability = mon.getOriginalPokemon().getAbility();
        return ability != null && "neutralizinggas".equals(ability.getName());
    }

    // remember every immunity visible on the current foes (type-chart and absorb abilities), field
    // only. uses permanentTyping so it keeps a tera (a wall the foe can bring back) but drops volatile
    // changes like soak, which would leave a phantom wall after the mon switches out.
    private void recordSeenImmunities(AiData data) {
        for (ActiveBattlePokemon foeActive : data.opponents) {
            BattlePokemon foe = foeActive.getBattlePokemon();
            if (foe == null) continue;
            Pokemon foeMon = foe.getOriginalPokemon();
            for (ElementalType t : TypeChart.immuneAttackingTypes(memory.permanentTyping(foe).defensive())) {
                memory.recordHardImmunity(t);
            }
            Ability ability = foeMon.getAbility();
            if (ability != null) memory.recordAbilityImmunity(TypeChart.absorbedType(ability.getName()));
            memory.recordSeenFoe(foe.getUuid(), toxicSpikesProof(foe), toxicSpikesAbsorber(foe));
        }
    }

    // a foe the layers can't touch: floats over them or can't be poisoned. reads permanentTyping since
    // toxic spikes are a lasting side condition.
    private boolean toxicSpikesProof(BattlePokemon foe) {
        LiveTyping types = memory.permanentTyping(foe);
        String ability = abilityName(foe.getOriginalPokemon());
        return !grounded(types, ability) || !canAfflict(types, ability, TOX, "toxicspikes", false, false);
    }

    // a grounded poison type absorbs the toxic spikes layers on entry.
    private boolean toxicSpikesAbsorber(BattlePokemon foe) {
        LiveTyping types = memory.permanentTyping(foe);
        return grounded(types, abilityName(foe.getOriginalPokemon())) && types.has(ElementalTypes.INSTANCE.getPOISON());
    }

    private boolean grounded(LiveTyping types, String ability) {
        return !types.has(ElementalTypes.INSTANCE.getFLYING()) && !ability.equals("levitate");
    }

    // from the |move| broadcast: public battle text, so honest to keep.
    public void recordFoeMove(UUID uuid, String moveId) {
        memory.recordFoeMove(uuid, moveId);
    }

    // a healthy foe pivoting out (public |switch|): builds the switch-happy read behind switch prediction.
    public void recordFoeVoluntarySwitch() {
        memory.recordFoeVoluntarySwitch();
    }

    // type-change hooks from |terastallize| and |-start| typechange/typeadd broadcasts: public battle
    // text, so recording them stays within the honest-info boundary.
    public void recordTera(BattlePokemon mon, ElementalType type) {
        if (mon != null) memory.recordTera(mon.getUuid(), type);
    }

    public void recordTypeChange(BattlePokemon mon, List<ElementalType> types) {
        if (mon != null) memory.recordTypeChange(mon.getUuid(), types);
    }

    public void recordTypeAdd(BattlePokemon mon, ElementalType added) {
        if (mon != null) memory.recordTypeAdd(mon.getUuid(), added, LiveTyping.formTypes(mon.getOriginalPokemon()));
    }

    public void clearVolatileTypes(BattlePokemon mon) {
        if (mon != null) memory.clearVolatileTypes(mon.getUuid());
    }

    // reward a move that KOs this turn, more when we move first.
    private void tryToFaint(AiData data) {
        if (data.opponent == null) return;
        for (AiData.ScoredMove m : data.moves) {
            if (!m.isDamaging() || !m.canFaint) continue;
            m.score += DamageCalc.strikesFirst(data.self, m.template, data.opponent, null) ? 5 : 4;
        }
    }

    private void preferStrongest(AiData data) {
        for (AiData.ScoredMove m : data.moves) {
            if (!m.isDamaging()) continue;
            if (m.hitsToKO == 1) m.score += 3;
            else if (m.hitsToKO == 2) m.score += 2;
        }
    }

    // discount a damaging move by its miss chance. kept out of the damage estimmate so KO detection
    // stays on-hit and never goes soft on a move that faints when it lands.
    private static final double ACCURACY_PENALTY = 5.0;

    private void accuracyScore(AiData data) {
        for (AiData.ScoredMove m : data.moves) {
            if (!m.isDamaging() || m.template == null) continue;
            double acc = m.template.getAccuracy();
            if (acc <= 0 || acc >= 100) continue; // never-miss (sentinel) or already reliable
            m.score -= (1.0 - acc / 100.0) * ACCURACY_PENALTY;
        }
    }

    // +1 to the best damaging move: fewest hits to KO, then higher accuracy, then raw damage.
    private void checkViability(AiData data) {
        AiData.ScoredMove best = null;
        for (AiData.ScoredMove m : data.moves) {
            if (!m.isDamaging() || m.damage <= 0) continue;
            if (best == null || better(m, best)) best = m;
        }
        if (best != null) best.score += 1;
    }

    private boolean better(AiData.ScoredMove a, AiData.ScoredMove b) {
        if (a.hitsToKO != b.hitsToKO) return a.hitsToKO < b.hitsToKO;
        double aAcc = a.template.getAccuracy(), bAcc = b.template.getAccuracy();
        if (aAcc != bAcc) return aAcc > bAcc;
        return a.damage > b.damage;
    }

    // --- non-damaging move layers ---
    // lift utility moves above baseline when they pay off, push below when they can't. spam is
    // prevented by natural guards (foe already statused, hp thresholds, boost caped), not randomness.

    private static final String BRN = Statuses.INSTANCE.getBURN().getShowdownName();
    private static final String PAR = Statuses.INSTANCE.getPARALYSIS().getShowdownName();
    private static final String SLP = Statuses.INSTANCE.getSLEEP().getShowdownName();
    private static final String PSN = Statuses.INSTANCE.getPOISON().getShowdownName();
    private static final String TOX = Statuses.INSTANCE.getPOISON_BADLY().getShowdownName();
    private static final Set<String> POWDER = Set.of("spore", "sleeppowder", "stunspore", "poisonpowder");

    private void statusMoveScore(AiData data) {
        if (data.opponent == null) return;
        Pokemon foe = data.opponent.getOriginalPokemon();
        boolean foeStatused = foe.getStatus() != null;
        boolean foeFaster = DamageCalc.outspeeds(data.opponent, data.self); // acts first, trick room aware
        boolean statusLover = statusBuffsFoe(foe); // quick feet / marvel scale gain from any status
        for (AiData.ScoredMove m : data.moves) {
            String status = m.template == null ? null : MoveData.STATUS_MOVES.get(m.template.getName());
            if (status == null) continue;
            boolean statusMove = !m.isDamaging();
            if (!canAfflict(memory.liveTyping(data.opponent), abilityName(foe), status, m.template.getName(), foeStatused, statusMove)) {
                if (statusMove) m.score -= 8; // a pure status move that can't land is wasted
                continue;
            }
            double value = statusValue(status, foe, foeFaster);
            if (statusLover) value -= 2; // any status hands these abilities a stat boost
            m.score += value;
        }
    }

    private double statusValue(String status, Pokemon foe, boolean foeFaster) {
        if (status.equals(SLP)) return 3;
        if (status.equals(BRN)) return foe.getStat(Stats.ATTACK) > foe.getStat(Stats.SPECIAL_ATTACK) ? 2.5 : 1.0;
        if (status.equals(PAR)) return foeFaster ? 2.5 : 1.5;
        if (status.equals(TOX)) return 2.0;
        if (status.equals(PSN)) return 1.5;
        return 1.0; // confusion, leech, etc.
    }

    private void selfRecoveryScore(AiData data) {
        double hp = hpFraction(data.self);
        for (AiData.ScoredMove m : data.moves) {
            if (m.template == null || !MoveData.SELF_RECOVERY.contains(m.template.getName())) continue;
            if (hp >= 0.85) m.score -= 8;
            else if (hp <= 0.4) m.score += 3;
            else if (hp <= 0.6) m.score += 1.5;
        }
    }

    private void setupBoostScore(AiData data, boolean foeMaySwitch) {
        if (data.opponent == null) return;
        // a baited switch is a free turn, so the usual threat gate doesn't apply.
        boolean safe = foeMaySwitch
            || (hpFraction(data.self) >= 0.9 && SwitchPlanner.stabThreat(data.opponent, data.self, memory) < 2.0);
        boolean hasPhysical = hasCategory(data, true);
        boolean hasSpecial = hasCategory(data, false);
        Map<Stat, Integer> ownBoosts = data.self.getStatChanges();
        for (AiData.ScoredMove m : data.moves) {
            Map<Stats, Integer> boosts = m.template == null ? null : MoveData.BOOST_FROM_MOVES.get(m.template.getName());
            if (boosts == null) continue;
            boolean offensiveUse = (positive(boosts, Stats.ATTACK) && hasPhysical)
                || (positive(boosts, Stats.SPECIAL_ATTACK) && hasSpecial)
                || (positive(boosts, Stats.SPEED) && (hasPhysical || hasSpecial));
            boolean defensiveUse = positive(boosts, Stats.DEFENCE) || positive(boosts, Stats.SPECIAL_DEFENCE);
            if (!offensiveUse && !defensiveUse) {
                m.score -= 8; // boosting a stat we can't cash in
            } else if (!safe || alreadyStacked(ownBoosts, boosts)) {
                m.score -= 4; // wrong time, or already near the cap
            } else {
                m.score += offensiveUse ? 2 : 1;
                if (foeMaySwitch && offensiveUse) m.score += switchProneBonus();
            }
        }
    }

    private void antiBoostScore(AiData data) {
        if (data.opponent == null) return;
        int foeBoost = positiveSum(data.opponent.getStatChanges());
        for (AiData.ScoredMove m : data.moves) {
            if (m.template == null || !MoveData.ANTI_BOOST.contains(m.template.getName())) continue;
            m.score += foeBoost >= 2 ? Math.min(4, 1 + foeBoost) : -2;
        }
    }

    // --- field-condition layers ---
    // each first checks "is this already up?" via FieldState, so the AI never re-sets a live condition.

    // reflect (vs physical), light screen (vs special), aurora veil (snow/hail only). skip when the
    // screens is already up.
    private void screenScore(AiData data) {
        if (data.opponent == null) return;
        Pokemon foe = data.opponent.getOriginalPokemon();
        boolean foePhysical = foe.getStat(Stats.ATTACK) >= foe.getStat(Stats.SPECIAL_ATTACK);
        String weather = FieldState.fieldId(data.selfActive, BattleContext.Type.WEATHER);
        boolean snowOrHail = "snow".equals(weather) || "hail".equals(weather);
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id == null) continue;
            switch (id) {
                case "reflect":
                    if (FieldState.ownSideHas(data.selfActive, BattleContext.Type.SCREEN, "reflect")) m.score -= 6;
                    else m.score += foePhysical ? 2 : 0.5;
                    break;
                case "lightscreen":
                    if (FieldState.ownSideHas(data.selfActive, BattleContext.Type.SCREEN, "lightscreen")) m.score -= 6;
                    else m.score += foePhysical ? 0.5 : 2;
                    break;
                case "auroraveil":
                    if (!snowOrHail) m.score -= 8; // fails outside snow/hail
                    else if (FieldState.ownSideHas(data.selfActive, BattleContext.Type.SCREEN, "auroraveil")) m.score -= 6;
                    else m.score += 2;
                    break;
                default:
            }
        }
    }

    // entry hazards. they only pay off across switch-ins, so value tracks the foe's remaining bench
    // and collapses when the chip can't be collected: no bench, maxed layers, revealed removal, or
    // (toxic spikes) a poison-immune side. doubles pays less (fewer switches, shorter games).
    private void hazardScore(AiData data, boolean foeMaySwitch) {
        if (data.opponentActive == null) return;
        int foeBench = benchCount(data.opponentActive);
        boolean doubles = data.opponents.size() >= 2;
        boolean removalShown = memory.foeRevealedHazardRemoval();
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id == null || !MoveData.ENTRY_HAZARDS.contains(id)) continue;
            int max = id.equals("spikes") ? 3 : id.equals("toxicspikes") ? 2 : 1;
            if (FieldState.countSide(data.opponentActive, BattleContext.Type.HAZARD, id) >= max) {
                m.score -= 6; // already maxed on the foe's side
                continue;
            }
            if (foeBench <= 0) {
                m.score -= 6; // foe's last mon is out, nothing left to punish
                continue;
            }
            double value = foeBench >= 4 ? 3.5 : foeBench >= 2 ? 2.5 : 1.5;
            if (doubles) value *= 0.5;
            if (removalShown) value -= 4;
            if (id.equals("toxicspikes")) value -= toxicSpikesPenalty();
            if (foeMaySwitch) value += 1 + switchProneBonus(); // an incoming mon to soak the layer
            m.score += value;
        }
    }

    // toxic spikes need grounded, poisonable targets so a revealed grounded poison type removes them
    // outright; otherwise each shrug-off foe is one less mon punished. judged only from seen foes.
    private double toxicSpikesPenalty() {
        if (memory.toxicSpikesAbsorberSeen()) return 5;
        int seen = memory.seenFoeCount();
        return seen == 0 ? 0 : 3.0 * memory.toxicSpikesProofFoeCount() / seen;
    }

    // mirror of hazardScore: sweep our own side, worth more with more layers and bench. removers
    // differ in reach:
    //  - spin clears our side only (and hits), so a clean field leaves it to the damage layers.
    //  - defog / tidy up clear both sides, throwing away hazards we set on the foe.
    //  - court change swaps sides instead of clearing: a gift when we're the only one buried.
    private void hazardRemovalScore(AiData data) {
        int ourLayers = hazardLayers(data.selfActive);
        int foeLayers = data.opponentActive == null ? 0 : hazardLayers(data.opponentActive);
        int ourBench = benchCount(data.selfActive);
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id == null || !MoveData.ANTI_HAZARDS.contains(id)) continue;
            if (id.equals("courtchange")) {
                int swing = ourLayers - foeLayers;
                if (swing > 0) m.score += Math.min(4, swing * 1.5);
                else if (swing < 0) m.score += Math.max(-6, swing * 2.0); // hands us the foe's layers
                else m.score -= 4; // nothing moves either way
                continue;
            }
            if (ourLayers == 0) {
                if (id.equals("defog")) m.score -= 6; // pure removal with nothing to remove
                continue;
            }
            double value = Math.min(5, 1.5 + ourLayers + (ourBench >= 3 ? 1.5 : 0));
            if (ourBench <= 0) value -= 2; // last mon in, the chip won't be paid again
            if (foeLayers > 0 && (id.equals("defog") || id.equals("tidyup"))) {
                value -= Math.min(3, foeLayers * 1.5); // sweeps our own investment too
            }
            m.score += value;
        }
    }

    private int hazardLayers(ActiveBattlePokemon sideMon) {
        int total = 0;
        for (String id : MoveData.ENTRY_HAZARDS) {
            total += FieldState.countSide(sideMon, BattleContext.Type.HAZARD, id);
        }
        return total;
    }

    // weather, trick room, tailwind. skip when already active; trick room and tailwind both pay off
    // for the slower side, judged across our whole side.
    private void weatherRoomScore(AiData data) {
        String weather = FieldState.fieldId(data.selfActive, BattleContext.Type.WEATHER);
        boolean slower = sideSlower(data);
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id == null) continue;
            String setWeather = MoveData.WEATHER_SETUP.get(id);
            if (setWeather != null) {
                m.score += FieldState.normalize(setWeather).equals(weather) ? -6 : 1;
            } else if (id.equals("trickroom")) {
                m.score += trickRoomValue(data, slower);
            } else if (id.equals("tailwind")) {
                if (FieldState.ownSideHas(data.selfActive, BattleContext.Type.TAILWIND, "tailwind")) m.score -= 6;
                else m.score += slower ? 2 : 1;
            }
        }
    }

    // trick room flips turn order for both sides at once, so its worth is just whether we're the slower
    // side. re-using the move while one is up cancels it: a strong play when the flip is the foe's gain,
    // and throwing away our own when it isn't.
    private double trickRoomValue(AiData data, boolean slower) {
        if (trickRoomUp(data)) return slower ? -6 : 4;
        return slower ? 2 : -2;
    }

    // --- fake out ---

    // has our mon yet to act since arriving in this slot? the window fake out and first impression need.
    private boolean firstTurnOut(AiData data) {
        return data.self != null && memory.firstTurnOut(data.selfActive.getPNX(), data.self.getUuid());
    }

    // both first-turn moves simply fail once we've already acted, so bury them then.
    private void firstTurnMoveScore(AiData data, boolean fresh) {
        if (fresh) return;
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id != null && MoveData.FIRST_TURN_ONLY.contains(id)) m.score -= 12;
        }
    }

    // fake out's flinch takes the target's whole turn away for chip damage and +3 priority. flag every
    // foe it actually lands on (a ghost is immune outright, inner focus / shield dust eat the flinch) so
    // the doubles planner can credit the denial wherever it aims, then pay for the foe we picked. an
    // aimed foe that shrugs it off scores neutral: the planner may still retarget onto one that doesn't.
    private void fakeOutScore(AiData data, boolean fresh) {
        if (!fresh || data.self == null) return;
        boolean abilitiesLive = abilityImmunitiesApply(data);
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id == null || !id.equals("fakeout")) continue;
            AiData.TargetDamage aimed = null;
            boolean anyFlinch = false;
            for (AiData.TargetDamage td : m.perTarget) {
                td.flinches = td.effectiveness > 0 && flinchable(td.target, abilitiesLive);
                anyFlinch |= td.flinches;
                if (td.target == m.target) aimed = td;
            }
            if (!anyFlinch) {
                m.score -= 4; // the chip still lands, but the free turn doesn't
                continue;
            }
            if (aimed == null || !aimed.flinches) continue;
            m.flinches = true;
            double value = 2;
            BattlePokemon foe = aimed.target.getBattlePokemon();
            if (SwitchPlanner.stabThreat(foe, data.self, memory) >= 2.0) value += 1; // denies a real hit
            if (data.opponents.size() >= 2) value += 1;                              // our ally hits free
            m.score += value;
        }
    }

    private boolean flinchable(ActiveBattlePokemon foeActive, boolean abilitiesLive) {
        BattlePokemon foe = foeActive == null ? null : foeActive.getBattlePokemon();
        if (foe == null) return false;
        return !abilitiesLive || !MoveData.flinchProof(abilityName(foe.getOriginalPokemon()));
    }

    // are we holding a fake out that lands this turn? then the foe never gets to act, and the layers
    // that flee or hide from its threat should stand down.
    private boolean flinchReady(AiData data) {
        for (AiData.ScoredMove m : data.moves) if (m.flinches) return true;
        return false;
    }

    // protect and its clones. discouraged when chained (each repeat likelier to fail), worth it to
    // stall residual damgege on the foe or scout a probable heavy hit.
    private void protectScore(AiData data, double omniThreat, boolean flinchReady) {
        UUID uuid = data.self == null ? null : data.self.getUuid();
        boolean residual = data.opponent != null && foeResidualTicking(data.opponent);
        boolean bigHitLikely;
        if (omniThreat >= 0) {
            // omniscient: predict the actual incoming hit.
            bigHitLikely = data.self != null && omniThreat >= data.self.getMaxHealth() * 0.5;
        } else {
            // honest: a foe moving first with a super-effective STAB type is the best proxy.
            bigHitLikely = data.opponent != null
                && DamageCalc.outspeeds(data.opponent, data.self)
                && SwitchPlanner.stabThreat(data.opponent, data.self, memory) >= 2.0;
        }
        // a fake out in hand blanks the foe's turn outright and chips it, so hiding is strictly worse.
        if (flinchReady) bigHitLikely = false;
        int streak = memory.protectStreak(uuid);
        boolean bypassed = protectBypassedByFoe(data);
        boolean tempAdvantage = foeTempAdvantage(data);
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id == null || !MoveData.PROTECT_MOVES.contains(id)) continue;
            if (bypassed) {
                m.score -= 6; // punched straight through, so the turn is simply gone
                continue;
            }
            if (streak > 0) {
                // each consecutive protect is far likelier to fail: steepening penalty, no upside buys
                // the chain back.
                m.score -= Math.min(12, 4 * streak);
                continue;
            }
            if (residual) m.score += 2;
            else if (bigHitLikely) m.score += 1;
            else if (tempAdvantage) m.score += 1;
            else if (hpFraction(data.self) >= 0.95) m.score -= 1;
        }
    }

    // a counted-down effect handing the foe the turn order: protecting burns one of its turns for free.
    // tailwind is theirs alone, so it counts whenever it actually wins them the turn. trick room is
    // shared, so it only counts against us when ours is the faster side it flips. honest public info.
    private boolean foeTempAdvantage(AiData data) {
        if (data == null || data.opponentActive == null || data.opponent == null || data.self == null) return false;
        if (FieldState.ownSideHas(data.opponentActive, BattleContext.Type.TAILWIND, "tailwind")
            && DamageCalc.outspeeds(data.opponent, data.self)) {
            return true;
        }
        return trickRoomAgainstUs(data);
    }

    private boolean trickRoomAgainstUs(AiData data) {
        return trickRoomUp(data) && !sideSlower(data);
    }

    private boolean trickRoomUp(AiData data) {
        return "trickroom".equals(FieldState.fieldId(data.selfActive, BattleContext.Type.ROOM));
    }

    // is our side the slower one? trick room and tailwind pay off for the slower side, and in doubles
    // that's a question about both slots, not just the one deciding. reads speed without the trick room
    // flip, since it asks what the field effect would do, not who moves first under one already up.
    private boolean sideSlower(AiData data) {
        return averageSpeed(data.selfActive.getActor().getActivePokemon()) < averageSpeed(data.opponents);
    }

    private double averageSpeed(List<ActiveBattlePokemon> slots) {
        double total = 0;
        int count = 0;
        for (ActiveBattlePokemon slot : slots) {
            BattlePokemon mon = slot == null || slot.isGone() ? null : slot.getBattlePokemon();
            if (mon == null || mon.getHealth() <= 0) continue;
            total += DamageCalc.effectiveSpeed(mon);
            count++;
        }
        return count == 0 ? 0 : total / count;
    }

    // can a live foe punch through protect? unseen fist sends contact moves through (ability, so gas
    // cuts it); feint and phantom/shadow force go through on their own, per revealed mon.
    private boolean protectBypassedByFoe(AiData data) {
        boolean gassed = neutralizingGasActive(data);
        for (ActiveBattlePokemon foeActive : data.opponents) {
            BattlePokemon foe = foeActive.getBattlePokemon();
            if (foe == null) continue;
            if (!gassed && abilityName(foe.getOriginalPokemon()).equals("unseenfist")) return true;
            if (memory.foeRevealed(foe.getUuid(), MoveData.PROTECT_BYPASS)) return true;
        }
        return false;
    }

    // does the foe have recurring damage ticking that protect can stall on?
    private boolean foeResidualTicking(BattlePokemon foe) {
        Pokemon p = foe.getOriginalPokemon();
        if (p.getStatus() != null) {
            String s = p.getStatus().getStatus().getShowdownName();
            if (s.equals(TOX) || s.equals(PSN) || s.equals(BRN)) return true;
        }
        Collection<BattleContext> volatiles = foe.getContextManager().get(BattleContext.Type.VOLATILE);
        if (volatiles != null) {
            for (BattleContext c : volatiles) {
                String id = c.getId();
                if (id.equals("leechseed") || id.equals("curse") || id.equals("perishsong") || id.equals("nightmare")) return true;
            }
        }
        return false;
    }

    // sucker punch and kin only connect if the foe attacks this turn, make it a coin-flip read. swing the
    // score up or down at random to model committing to the guess.
    private void predictionMoveScore(AiData data) {
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id == null || !MoveData.PREDICTION_MOVES.contains(id)) continue;
            // upper hand needs the foe to attack with priority, a narrower read, so smaller upside.
            double up = id.equals("upperhand") ? 1 : 3;
            m.score += RANDOM.nextBoolean() ? up : -3;
        }
    }

    private int benchCount(ActiveBattlePokemon active) {
        return (int) active.getActor().getPokemonList().stream().filter(BattlePokemon::canBeSentOut).count();
    }

    // --- switch prediction (tier 9+) ---
    // honest read that the foe is cornered and will likely pivot: we pressure it (a KO in hand or a
    // super-effective STAB) while it can't hit back super-effectively, and it has a bench to run to.
    private boolean foeLikelyToSwitch(AiData data) {
        if (data.opponent == null || data.self == null || benchCount(data.opponentActive) <= 0) return false;
        boolean weCanKO = false;
        for (AiData.ScoredMove m : data.moves) if (m.canFaint) { weCanKO = true; break; }
        boolean ourStabSuperEffective = SwitchPlanner.stabThreat(data.self, data.opponent, memory) >= 2.0;
        if (!weCanKO && !ourStabSuperEffective) return false;
        return SwitchPlanner.stabThreat(data.opponent, data.self, memory) < 2.0;
    }

    // lean in harder when this foe has already shown it bails from bad matchups.
    private int switchProneBonus() {
        return Math.min(2, memory.foeVoluntarySwitchCount());
    }

    // --- omniscient tier (level 11) ---
    // crosses the honest-info boundary and reads the active foe's real moveset for its hardest hit onn us.
    // active foe only, bench stays hidden even here.
    private double foeBestOnSelf(AiData data) {
        if (data.opponent == null || data.self == null) return 0;
        double best = 0;
        for (var mv : data.opponent.getMoveSet().getMoves()) {
            MoveTemplate t = Moves.INSTANCE.getByName(mv.getName());
            if (t == null || t.getPower() <= 0) continue;
            best = Math.max(best, DamageCalc.estimate(data.opponent, data.self, t, memory));
        }
        return best;
    }

    private boolean hasGoodUtilityMove(AiData data) {
        for (AiData.ScoredMove m : data.moves) if (!m.isDamaging() && m.score > 100) return true;
        return false;
    }

    // pivot moves act then leave, best exactly when the switch planner wants out: free exit, still
    // hit on the way. an empty bench leaves the switch half doing nothing (pure switchers fail).
    private void pivotScore(AiData data, boolean wantsOut) {
        boolean canFollowUp = benchCount(data.selfActive) > 0;
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id == null || !MoveData.PIVOT.contains(id)) continue;
            if (!canFollowUp) {
                m.score -= m.isDamaging() ? 1 : 8;
                continue;
            }
            if (id.equals("batonpass") && positiveSum(data.self.getStatChanges()) <= 0) {
                m.score -= 6; // nothing worth passing on
                continue;
            }
            m.score += wantsOut ? 3 : (m.isDamaging() ? -1 : -3);
        }
    }

    // did a pivot land above baseline? then a raw switch is strictly worse and the pivot leaves too.
    private boolean hasGoodPivot(AiData data) {
        for (AiData.ScoredMove m : data.moves) {
            String id = m.template == null ? null : m.template.getName();
            if (id != null && MoveData.PIVOT.contains(id) && m.score > 100) return true;
        }
        return false;
    }

    // --- score readout (/trainers aidebug true) ---
    // emits each move's final score to the game log and battle chat. read-only and fully guarded.

    private boolean debugEnabled() {
        try {
            return CobblemonTrainers.INSTANCE.getConfig().isAiDebug();
        } catch (Exception e) {
            return false;
        }
    }

    private void debugReport(ActiveBattlePokemon self, AiData data, AiData.ScoredMove chosen) {
        if (!debugEnabled()) return;
        String report = formatReport(data, chosen);
        log.info("ai debug:\n{}", report);
        broadcast(self, report);
    }

    // aligned and sorted score list
    private String formatReport(AiData data, AiData.ScoredMove chosen) {
        List<AiData.ScoredMove> sorted = new ArrayList<>(data.moves);
        sorted.sort((a, b) -> Double.compare(b.score, a.score));
        int idWidth = 0;
        for (AiData.ScoredMove m : sorted) {
            idWidth = Math.max(idWidth, m.inMove.getId().length());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[AI L").append(level).append(omniscient ? "*" : "").append("] ")
            .append(nameOf(data.self)).append(" vs ").append(nameOf(data.opponent));
        for (AiData.ScoredMove m : sorted) {
            sb.append("\n  ").append(m == chosen ? "> " : "  ")
                .append(String.format(Locale.ROOT, "%-" + idWidth + "s", m.inMove.getId()))
                .append(" = ").append(String.format(Locale.ROOT, "%6s", fmt(m.score)));
        }
        String target = chosen.target == null ? null : nameOf(chosen.target.getBattlePokemon());
        sb.append("\n  => ").append(chosen.inMove.getId());
        if (target != null) sb.append(" -> ").append(target);
        return sb.toString();
    }

    private void debugPlan(ActiveBattlePokemon self, AiData selfData, DoublesPlanner.Choice selfChoice,
                           ActiveBattlePokemon ally, AiData allyData, DoublesPlanner.Choice allyChoice,
                           boolean coordinated) {
        if (!debugEnabled()) return;
        if (coordinated) broadcast(self, "[AI L" + level + (omniscient ? "*" : "") + "] doubles coordination");
        debugChoice(self, selfData, selfChoice);
        debugChoice(ally, allyData, allyChoice);
    }

    private void debugChoice(ActiveBattlePokemon slot, AiData data, DoublesPlanner.Choice choice) {
        if (!debugEnabled()) return;
        if (choice.move != null && data != null) {
            debugReport(slot, data, choice.move);
        } else if (choice.switchTo != null) {
            String message = "[AI L" + level + (omniscient ? "*" : "") + "] " + nameOf(slot.getBattlePokemon())
                + " switches out -> " + nameOf(choice.switchTo);
            log.info("ai debug: {}", message);
            broadcast(slot, message);
        }
    }

    private void broadcast(ActiveBattlePokemon self, String message) {
        try {
            self.getBattle().broadcastChatMessage(Text.literal(message));
        } catch (Exception ignored) {
        }
    }

    private String nameOf(BattlePokemon p) {
        return p == null ? "none" : p.getName().getString();
    }

    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // --- status legality ---

    // live typing, so a foe soaked to water becomes burnable and one soaked off electric paralyzable.
    private boolean canAfflict(LiveTyping types, String ability, String status, String moveId, boolean foeStatused, boolean statusMove) {
        // good as gold blocks status moves but not a status carried by a damaging move (scald burns it).
        if (statusMove && ability.equals("goodasgold")) return false;
        boolean major = status.equals(BRN) || status.equals(PAR) || status.equals(SLP) || status.equals(PSN) || status.equals(TOX);
        if (major && foeStatused) return false;
        ElementalTypes t = ElementalTypes.INSTANCE;
        // guts / flare boost turn a burn into a net buff, so burning them helps.
        if (status.equals(BRN)) return !types.has(t.getFIRE()) && !ability.equals("waterveil")
            && !ability.equals("thermalexchange") && !ability.equals("waterbubble")
            && !ability.equals("guts") && !ability.equals("flareboost");
        if (status.equals(PAR)) {
            if (types.has(t.getELECTRIC()) || ability.equals("limber")) return false;
            return !(moveId.equals("thunderwave") && types.has(t.getGROUND()));
        }
        if (status.equals(SLP)) {
            if (ability.equals("insomnia") || ability.equals("vitalspirit") || ability.equals("comatose") || ability.equals("sweetveil")) return false;
            return !(POWDER.contains(moveId) && (types.has(t.getGRASS()) || ability.equals("overcoat")));
        }
        if (status.equals(PSN) || status.equals(TOX)) {
            return !types.has(t.getPOISON()) && !types.has(t.getSTEEL()) && !ability.equals("immunity") && !ability.equals("pastelveil");
        }
        if (status.equals("leech")) return !types.has(t.getGRASS());
        return true;
    }

    // --- basic helpers ---

    private double hpFraction(BattlePokemon p) {
        int max = p.getMaxHealth();
        return max <= 0 ? 0 : (double) p.getHealth() / max;
    }

    private String abilityName(Pokemon p) {
        return p.getAbility() == null ? "" : p.getAbility().getName();
    }

    // quick feet / marvel scale turn any status into a stat boost so inflicting one is s a partial gift.
    private boolean statusBuffsFoe(Pokemon foe) {
        String a = abilityName(foe);
        return a.equals("quickfeet") || a.equals("marvelscale");
    }

    private boolean hasCategory(AiData data, boolean physical) {
        String target = physical ? DamageCategories.INSTANCE.getPHYSICAL().getName() : DamageCategories.INSTANCE.getSPECIAL().getName();
        for (AiData.ScoredMove m : data.moves) {
            if (m.isDamaging() && m.template.getDamageCategory().getName().equals(target)) return true;
        }
        return false;
    }

    private boolean positive(Map<Stats, Integer> boosts, Stats stat) {
        return boosts.getOrDefault(stat, 0) > 0;
    }

    private boolean alreadyStacked(Map<Stat, Integer> ownBoosts, Map<Stats, Integer> boosts) {
        for (Stats stat : boosts.keySet()) {
            if (boosts.get(stat) > 0 && ownBoosts.getOrDefault(stat, 0) >= 4) return true;
        }
        return false;
    }

    private int positiveSum(Map<Stat, Integer> boosts) {
        int sum = 0;
        for (int v : boosts.values()) if (v > 0) sum += v;
        return sum;
    }

    // --- selection & targeting ---

    private AiData.ScoredMove pickHighest(List<AiData.ScoredMove> moves) {
        double max = Double.NEGATIVE_INFINITY;
        for (AiData.ScoredMove m : moves) max = Math.max(max, m.score);
        List<AiData.ScoredMove> tied = new ArrayList<>();
        for (AiData.ScoredMove m : moves) if (m.score == max) tied.add(m);
        return tied.get(RANDOM.nextInt(tied.size()));
    }

    // resolve the response target: preferred foe if legal, else first legal opponent, else first legal
    // target, else null (self / spread / field move).
    private String pnxForMove(ActiveBattlePokemon self, InBattleMove move, ActiveBattlePokemon preferred) {
        if (move.mustBeUsed()) return null;
        List<Targetable> targets = move.getTarget().getTargetList().invoke(self);
        if (targets == null || targets.isEmpty()) return null;
        if (preferred != null && targets.contains(preferred)) return preferred.getPNX();
        for (Targetable t : targets) {
            if (t instanceof ActiveBattlePokemon abp && !abp.isAllied(self) && abp.getBattlePokemon() != null) {
                return abp.getPNX();
            }
        }
        Targetable t = targets.get(0);
        return t instanceof ActiveBattlePokemon ? ((ActiveBattlePokemon) t).getPNX() : null;
    }

    // --- forced switching (fainted / dragged out): offensive replacement pick ---

    // willBeSwitchedIn reserves a bench mon so two slots can't pick it twice which is normally cleared when the
    // switch executes. a dropped half of a simultaneous double switch-in leaves it stuck forever, so on
    // a fresh request clear any flag still set on a live benched mon.
    private void clearStaleReservations(ActiveBattlePokemon self) {
        ShowdownActionRequest request = self.getActor().getRequest();
        if (request == null || request == reservationRequest) return;
        reservationRequest = request;
        for (BattlePokemon p : self.getActor().getPokemonList()) {
            if (p.getWillBeSwitchedIn() && !p.isSentOut() && p.getHealth() > 0) p.setWillBeSwitchedIn(false);
        }
    }

    private ShowdownActionResponse chooseSwitch(ActiveBattlePokemon self, ActiveBattlePokemon opponent) {
        BattlePokemon foe = opponent == null ? null : opponent.getBattlePokemon();
        BattlePokemon pick = SwitchPlanner.chooseReplacement(self, foe, true, memory);
        if (pick == null) return new DefaultActionResponse();
        pick.setWillBeSwitchedIn(true);
        return new SwitchActionResponse(pick.getUuid());
    }

    // --- helpers ---

    // live foes adjacent to this slot, in target order. from getAdjacentOpponents(), not fixed indices.
    private List<ActiveBattlePokemon> liveOpponents(ActiveBattlePokemon self) {
        List<ActiveBattlePokemon> out = new ArrayList<>();
        for (Targetable t : self.getAdjacentOpponents()) {
            if (t instanceof ActiveBattlePokemon abp && abp.getBattlePokemon() != null && !abp.isGone()) {
                out.add(abp);
            }
        }
        return out;
    }

    private List<InBattleMove> usableMoves(ActiveBattlePokemon self, ShowdownMoveset moveset) {
        return moveset.moves.stream()
            .filter(InBattleMove::canBeUsed)
            .filter(m -> {
                List<Targetable> targets = m.getTarget().getTargetList().invoke(self);
                return m.mustBeUsed() || targets == null || !targets.isEmpty();
            })
            .toList();
    }

    // switch only when we can't move, pass only when nothing else works. every step guarded.
    private ShowdownActionResponse safeFallback(ActiveBattlePokemon self, ShowdownMoveset moveset, boolean forceSwitch) {
        if (!forceSwitch && moveset != null) {
            ActiveBattlePokemon foe = firstLiveOpponent(self);
            BattlePokemon me = self.getBattlePokemon();
            InBattleMove best = null;
            InBattleMove anyUsable = null;
            double bestDamage = -1;
            for (InBattleMove m : moveset.moves) {
                if (!m.canBeUsed() || !hasLegalTarget(self, m)) continue;
                if (anyUsable == null) anyUsable = m;
                double dmg = safeDamage(me, foe, m);
                if (dmg > bestDamage) {
                    bestDamage = dmg;
                    best = m;
                }
            }
            InBattleMove pick = best != null ? best : anyUsable;
            if (pick != null) return new MoveActionResponse(pick.getId(), pnxForMove(self, pick, foe), null);
            return new MoveActionResponse("struggle", null, null); // no usable move, but never pass
        }
        List<BattlePokemon> bench = self.getActor().getPokemonList().stream().filter(BattlePokemon::canBeSentOut).toList();
        if (!bench.isEmpty()) {
            bench.get(0).setWillBeSwitchedIn(true);
            return new SwitchActionResponse(bench.get(0).getUuid());
        }
        return PassActionResponse.INSTANCE;
    }

    private ActiveBattlePokemon firstLiveOpponent(ActiveBattlePokemon self) {
        List<ActiveBattlePokemon> foes = liveOpponents(self);
        return foes.isEmpty() ? null : foes.get(0);
    }

    private boolean hasLegalTarget(ActiveBattlePokemon self, InBattleMove move) {
        if (move.mustBeUsed()) return true;
        List<Targetable> targets = move.getTarget().getTargetList().invoke(self);
        return targets == null || !targets.isEmpty();
    }

    // low-roll damage of a usabble move at the foe, 0 on failure. must never throw so it runs inside the
    // exception fallback.
    private double safeDamage(BattlePokemon me, ActiveBattlePokemon foe, InBattleMove move) {
        if (me == null || foe == null || foe.getBattlePokemon() == null) return 0;
        try {
            MoveTemplate t = Moves.INSTANCE.getByName(move.getId());
            return t == null ? 0 : DamageCalc.estimate(me, foe.getBattlePokemon(), t, memory);
        } catch (Exception e) {
            return 0;
        }
    }
}
