package com.selfdot.cobblemontrainers.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.battles.interpreter.instructions.MoveInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.selfdot.cobblemontrainers.ai.ScoreBattleAI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// |move|POKEMON|MOVE|TARGET is the public "X used Y" broadcats every battler reads, so a score AI
// on the opposing side may honestly remember it. this is the only door revealed foe moves come
// through, which keeps the honest tiers from ever touching an unrevealed moveset.
@Mixin(MoveInstruction.class)
public abstract class MoveInstructionMixin {

    @Shadow(remap = false)
    public abstract MoveTemplate getMove();

    @Shadow(remap = false)
    public abstract BattlePokemon getUserPokemon();

    @Inject(method = "invoke", at = @At("TAIL"), remap = false)
    private void injectInvoke(PokemonBattle battle, CallbackInfo ci) {
        MoveTemplate move = getMove();
        BattlePokemon user = getUserPokemon();
        if (move == null || user == null) return;
        BattleActor userActor = user.getActor();
        for (BattleActor actor : battle.getActors()) {
            if (actor.getSide() == userActor.getSide()) continue;
            if (actor instanceof AIBattleActor aiActor && aiActor.getBattleAI() instanceof ScoreBattleAI ai) {
                ai.recordFoeMove(user.getUuid(), move.getName());
            }
        }
    }

}
