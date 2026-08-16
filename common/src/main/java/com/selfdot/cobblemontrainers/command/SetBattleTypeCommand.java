package com.selfdot.cobblemontrainers.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class SetBattleTypeCommand extends TrainerCommand {

    @Override
    protected int runSubCommand(CommandContext<ServerCommandSource> context) {
        String battleType = StringArgumentType.getString(context, "battleType");
        if (!battleType.equals("singles") && !battleType.equals("doubles")) {
            context.getSource().sendError(Text.literal("Battle type must be 'singles' or 'doubles'"));
            return -1;
        }
        trainer.setBattleType(battleType);
        context.getSource().sendMessage(Text.literal(
            "Set trainer " + trainer.getName() + " battle type to " + battleType
        ));
        return SINGLE_SUCCESS;
    }

}
