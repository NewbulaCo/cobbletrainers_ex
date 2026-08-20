package com.selfdot.cobblemontrainers.mixin;

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.interpreter.instructions.RequestInstruction;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.selfdot.cobblemontrainers.ai.CommanderTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// cobblemon's ShowdownPokemon drops showdown's "commanding" flag, so re-read it off the raw request.
@Mixin(RequestInstruction.class)
public abstract class RequestInstructionMixin {

    @Shadow(remap = false)
    public abstract BattleActor getBattleActor();

    @Shadow(remap = false)
    public abstract BattleMessage getMessage();

    @Inject(method = "invoke", at = @At("HEAD"), remap = false)
    private void trackCommanding(PokemonBattle battle, CallbackInfo ci) {
        String raw = getMessage().getRawMessage();
        int start = raw.indexOf("|request|");
        if (start < 0) return;

        Set<UUID> commanding = new HashSet<>();
        try {
            JsonObject root = JsonParser.parseString(raw.substring(start + "|request|".length())).getAsJsonObject();
            JsonObject side = root.getAsJsonObject("side");
            JsonArray team = side == null ? null : side.getAsJsonArray("pokemon");
            if (team == null) return;
            for (int i = 0; i < team.size(); i++) {
                JsonObject mon = team.get(i).getAsJsonObject();
                if (!mon.has("commanding") || !mon.get("commanding").getAsBoolean()) continue;
                UUID uuid = uuidFromDetails(mon);
                if (uuid != null) commanding.add(uuid);
            }
        } catch (Exception e) {
            return;
        }
        CommanderTracker.record(battle.getBattleId(), getBattleActor().getUuid(), commanding);
    }

    // details reads "Species, UUID, L50, M".
    private static UUID uuidFromDetails(JsonObject mon) {
        if (!mon.has("details")) return null;
        String[] parts = mon.get("details").getAsString().split(",");
        if (parts.length < 2) return null;
        try {
            return UUID.fromString(parts[1].trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
