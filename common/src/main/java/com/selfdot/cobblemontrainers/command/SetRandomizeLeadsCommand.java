package com.selfdot.cobblemontrainers.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class SetRandomizeLeadsCommand extends TrainerCommand {

    @Override
    protected int runSubCommand(CommandContext<ServerCommandSource> context) {
        boolean randomizeLeads = BoolArgumentType.getBool(context, "randomizeLeads");
        trainer.setRandomizeLeads(randomizeLeads);
        context.getSource().sendMessage(Text.literal(
            "Set trainer " + trainer.getName() + " randomize leads to " + randomizeLeads
        ));
        return SINGLE_SUCCESS;
    }

}
