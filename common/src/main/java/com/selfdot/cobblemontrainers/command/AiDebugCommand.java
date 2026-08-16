package com.selfdot.cobblemontrainers.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.selfdot.cobblemontrainers.CobblemonTrainers;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class AiDebugCommand implements Command<ServerCommandSource> {

    @Override
    public int run(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        boolean enabled = BoolArgumentType.getBool(context, "enabled");
        CobblemonTrainers.INSTANCE.getConfig().setAiDebug(enabled);
        CobblemonTrainers.INSTANCE.getConfig().save();
        context.getSource().sendMessage(Text.literal(
            "Score AI debug output " + (enabled ? "enabled" : "disabled")
        ));
        return SINGLE_SUCCESS;
    }

}
