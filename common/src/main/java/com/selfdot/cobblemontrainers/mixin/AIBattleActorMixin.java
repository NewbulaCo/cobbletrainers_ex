package com.selfdot.cobblemontrainers.mixin;

import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// onChoiceRequested does request!!, but the deferred force-switch path (queued via doWhenClear) can
// fire after the request was already cleared, crashing the battle tick. skip when there is nothing to
// answer, as turn/upkeep already do. matches the request?.let guard cobblemon has upstream.
@Mixin(AIBattleActor.class)
public abstract class AIBattleActorMixin {

    @Inject(method = "onChoiceRequested", at = @At("HEAD"), cancellable = true, remap = false)
    private void injectOnChoiceRequested(CallbackInfo ci) {
        if (((BattleActor) (Object) this).getRequest() == null) ci.cancel();
    }

}
