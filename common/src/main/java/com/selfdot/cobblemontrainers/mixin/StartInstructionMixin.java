package com.selfdot.cobblemontrainers.mixin;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.interpreter.Effect;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.battles.interpreter.instructions.StartInstruction;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.selfdot.cobblemontrainers.ai.ScoreBattleAI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// |-start| monitors for type changes which are used for BattleMemory logic
@Mixin(StartInstruction.class)
public abstract class StartInstructionMixin {

    @Shadow(remap = false)
    public abstract BattleMessage getMessage();

    @Inject(method = "invoke", at = @At("HEAD"), remap = false)
    private void injectInvoke(PokemonBattle battle, CallbackInfo ci) {
        BattleMessage message = getMessage();
        Effect effect = message.effectAt(1);
        if (effect == null) return;
        String kind = effect.getId();
        boolean replace = "typechange".equals(kind);
        boolean add = "typeadd".equals(kind);
        if (!replace && !add) return;

        BattlePokemon mon = message.battlePokemon(0, battle);
        String raw = message.argumentAt(2);
        if (mon == null || raw == null) return;
        List<ElementalType> parsed = parseTypes(raw);
        if (add && parsed.isEmpty()) return;

        for (BattleActor actor : battle.getActors()) {
            if (actor instanceof AIBattleActor aiActor && aiActor.getBattleAI() instanceof ScoreBattleAI ai) {
                if (replace) {
                    ai.recordTypeChange(mon, parsed);
                } else {
                    ai.recordTypeAdd(mon, parsed.get(0));
                }
            }
        }
    }

    // a typechange arg is one or two "/"-joined showdown type names ("Water", "Water/Ground"). burn
    // up sends "???" for the now-typeless mon, which maps to nothing and leaves an empty list.
    private static List<ElementalType> parseTypes(String raw) {
        List<ElementalType> out = new ArrayList<>(2);
        for (String part : raw.split("/")) {
            ElementalType type = ElementalTypes.INSTANCE.get(part.trim().toLowerCase(Locale.ROOT));
            if (type != null) out.add(type);
        }
        return out;
    }

}
