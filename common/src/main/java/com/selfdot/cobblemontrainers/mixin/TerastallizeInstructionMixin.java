package com.selfdot.cobblemontrainers.mixin;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.interpreter.Effect;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.battles.interpreter.instructions.TerastallizeInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.selfdot.cobblemontrainers.ai.ScoreBattleAI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// |terastallize|POKEMON|TYPE is remembered as a type change which is non-volatile
@Mixin(TerastallizeInstruction.class)
public abstract class TerastallizeInstructionMixin {

    @Shadow(remap = false)
    public abstract BattleMessage getMessage();

    @Inject(method = "invoke", at = @At("HEAD"), remap = false)
    private void injectInvoke(PokemonBattle battle, CallbackInfo ci) {
        BattleMessage message = getMessage();
        BattlePokemon mon = message.battlePokemon(0, battle);
        Effect effect = message.effectAt(1);
        if (mon == null || effect == null) return;
        ElementalType type = ElementalTypes.INSTANCE.get(effect.getId());
        if (type == null) return;
        for (BattleActor actor : battle.getActors()) {
            if (actor instanceof AIBattleActor aiActor && aiActor.getBattleAI() instanceof ScoreBattleAI ai) {
                ai.recordTera(mon, type);
            }
        }
    }

}
