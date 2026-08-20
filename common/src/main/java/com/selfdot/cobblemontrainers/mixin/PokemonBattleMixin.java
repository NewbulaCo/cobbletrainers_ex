package com.selfdot.cobblemontrainers.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ActiveBattlePokemon;
import com.cobblemon.mod.common.battles.actor.TrainerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.selfdot.cobblemontrainers.ai.CommanderTracker;
import com.selfdot.cobblemontrainers.trainer.EntityBackerTrainerBattleActor;
import com.selfdot.cobblemontrainers.util.PokemonUtility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(PokemonBattle.class)
public abstract class PokemonBattleMixin {

    @Shadow(remap = false)
    public abstract Iterable<BattleActor> getActors();

    @Shadow(remap = false)
    public abstract UUID getBattleId();

    @Inject(method = "end", at = @At("HEAD"), remap = false)
    private void injectEnd(CallbackInfo ci) {
        CommanderTracker.clear(getBattleId());
        getActors().forEach(actor -> {
            // both shapes createTrainerBattle can produce, depending on whether a living entity backs it.
            if (actor instanceof EntityBackerTrainerBattleActor || actor instanceof TrainerBattleActor) {
                recallActives(actor);
            }
            actor.getPlayerUUIDs().forEach(PokemonUtility.IN_TRAINER_BATTLE::remove);
        });
    }

    // trainer mons have a no-op postBattleEntityOperation, so every standing slot is recalled here.
    private static void recallActives(BattleActor actor) {
        for (ActiveBattlePokemon slot : actor.getActivePokemon()) {
            BattlePokemon mon = slot == null ? null : slot.getBattlePokemon();
            PokemonEntity entity = mon == null ? null : mon.getEntity();
            if (entity != null) entity.recallWithAnimation();
        }
    }

}
