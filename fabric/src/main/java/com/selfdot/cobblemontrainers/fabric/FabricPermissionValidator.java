package com.selfdot.cobblemontrainers.fabric;

import com.cobblemon.mod.common.api.permission.Permission;
import com.selfdot.cobblemontrainers.command.permission.PermissionValidator;
import net.minecraft.command.CommandSource;

public class FabricPermissionValidator implements PermissionValidator {

    @Override
    public boolean hasPermission(CommandSource source, Permission permission) {
        return source.hasPermissionLevel(permission.getLevel().getNumericalValue());
    }

}
