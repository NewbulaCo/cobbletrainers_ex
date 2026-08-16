package com.selfdot.cobblemontrainers.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class SetAILevelCommand extends TrainerCommand {

    @Override
    protected int runSubCommand(CommandContext<ServerCommandSource> context) {
        int aiLevel = IntegerArgumentType.getInteger(context, "aiLevel");
        trainer.setAiLevel(aiLevel);
        String description = aiLevel < 0
            ? "inherit the global config AI"
            : "score AI level " + aiLevel + (aiLevel >= 11 ? " (omniscient)" : "");
        context.getSource().sendMessage(Text.literal(
            "Set trainer " + trainer.getName() + " to " + description
        ));
        return SINGLE_SUCCESS;
    }

}
