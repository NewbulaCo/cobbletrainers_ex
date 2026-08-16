package com.selfdot.cobblemontrainers.ai;

import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.ArrayList;
import java.util.List;

// a mon's current in-battle typing, split by role: defensive (what incoming moves and
// type-identity checks read) vs stab (grants the 1.5x own-move bonus). they diverge only under
// tera, which swaps defensive to the tera type but keeps original stab and adds the tera type.
public final class LiveTyping {

    private final List<ElementalType> defensive;
    private final List<ElementalType> stab;

    LiveTyping(List<ElementalType> defensive, List<ElementalType> stab) {
        this.defensive = defensive;
        this.stab = stab;
    }

    public List<ElementalType> defensive() {
        return defensive;
    }

    public List<ElementalType> stab() {
        return stab;
    }

    // has that type defensively, for legality checks.
    public boolean has(ElementalType type) {
        return type != null && defensive.contains(type);
    }

    public boolean grantsStab(ElementalType moveType) {
        return moveType != null && stab.contains(moveType);
    }

    // unmodified form-derived types (one or two entries, never null).
    static List<ElementalType> formTypes(Pokemon p) {
        List<ElementalType> out = new ArrayList<>(2);
        if (p.getPrimaryType() != null) out.add(p.getPrimaryType());
        if (p.getSecondaryType() != null) out.add(p.getSecondaryType());
        return out;
    }
}
