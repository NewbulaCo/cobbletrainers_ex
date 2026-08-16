package com.selfdot.cobblemontrainers.mixin;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.interpreter.instructions.SwitchInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.selfdot.cobblemontrainers.ai.ScoreBattleAI;
import kotlin.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SwitchInstruction.class)
public abstract class SwitchInstructionMixin {

    @Shadow(remap = false)
    public abstract BattleMessage getPublicMessage();

    @Shadow(remap = false)
    public abstract BattleActor getBattleActor();

    // before the swap resolves the slot still holds the outgoing mon; a living one means a voluntary
    // pivot, which opposing score AIs remember to feed switch prediction.
    @Inject(method = "invoke", at = @At("HEAD"), remap = false)
    private void recordVoluntarySwitch(PokemonBattle battle, CallbackInfo ci) {
        BattleActor actor = getBattleActor();
        Pair<String, String> pnxAndUuid = getPublicMessage().pnxAndUuid(0);
        if (actor == null || pnxAndUuid == null) return;
        boolean voluntary = false;
        for (ActiveBattlePokemon slot : actor.getActivePokemon()) {
            if (!slot.getPNX().equals(pnxAndUuid.component1())) continue;
            BattlePokemon outgoing = slot.getBattlePokemon();
            voluntary = outgoing != null && !slot.isGone() && outgoing.getHealth() > 0;
            break;
        }
        if (!voluntary) return;
        for (BattleActor other : battle.getActors()) {
            if (other.getSide() == actor.getSide()) continue;
            if (other instanceof AIBattleActor aiActor && aiActor.getBattleAI() instanceof ScoreBattleAI ai) {
                ai.recordFoeVoluntarySwitch();
            }
        }
    }

    @Inject(method = "invoke", at = @At("TAIL"), remap = false)
    private void injectInvoke(PokemonBattle battle, CallbackInfo ci) {
        Pair<String, String> pnxAndPokemonID = getPublicMessage().pnxAndUuid(0);
        if (pnxAndPokemonID == null) return;
        BattlePokemon battlePokemon = battle.getBattlePokemon(
            pnxAndPokemonID.component1(), pnxAndPokemonID.component2()
        );
        battlePokemon.setWillBeSwitchedIn(false);
        // a mon returning to the field is back to its base typing, so drop any volatile type change
        // we were tracking for it. a terastallization is kept, being permanent for the battle.
        for (BattleActor actor : battle.getActors()) {
            if (actor instanceof AIBattleActor aiActor && aiActor.getBattleAI() instanceof ScoreBattleAI ai) {
                ai.clearVolatileTypes(battlePokemon);
            }
        }
    }

}
