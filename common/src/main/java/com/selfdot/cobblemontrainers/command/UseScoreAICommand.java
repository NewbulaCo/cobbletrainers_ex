package com.selfdot.cobblemontrainers.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.selfdot.cobblemontrainers.CobblemonTrainers;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class UseScoreAICommand implements Command<ServerCommandSource> {

    @Override
    public int run(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        CobblemonTrainers.INSTANCE.getConfig().setAiType("score");
        CobblemonTrainers.INSTANCE.getConfig().save();
        context.getSource().sendMessage(Text.literal("Trainers will use Score AI"));
        return SINGLE_SUCCESS;
    }

}
