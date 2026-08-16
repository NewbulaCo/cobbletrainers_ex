package com.selfdot.cobblemontrainers;

import com.cobblemon.mod.common.api.battles.model.ai.BattleAI;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.selfdot.cobblemontrainers.libs.io.JsonFile;
import com.selfdot.cobblemontrainers.libs.minecraft.DisableableMod;
import com.selfdot.cobblemontrainers.ai.ScoreBattleAI;
import com.selfdot.cobblemontrainers.trainer.Generation5AI;
import com.selfdot.cobblemontrainers.util.CommandExecutor;
import lombok.Getter;
import lombok.Setter;

import static com.selfdot.cobblemontrainers.util.CommandExecutor.CONSOLE;
import static com.selfdot.cobblemontrainers.util.DataKeys.*;

public class Config extends JsonFile {

    private boolean xpEnabled;
    @Getter
    private CommandExecutor commandExecutor;
    @Setter
    private int strongAILevel;
    // "legacy" keeps the strongAILevel behaviour (gen5 / strong); "score" selects the new score AI.
    @Setter
    private String aiType;
    // when on, the score AI broadcasts its per-move scores and choice to the battle chat.
    @Getter
    @Setter
    private boolean aiDebug;

    public Config(DisableableMod mod) {
        super(mod);
    }

    @Override
    protected String filename() {
        return "config/trainers/config.json";
    }

    @Override
    protected void setDefaults() {
        xpEnabled = true;
        commandExecutor = CONSOLE;
        strongAILevel = -1;
        aiType = "score";
        aiDebug = false;
    }

    @Override
    public void load() {
        super.load();
        if (!mod.isDisabled()) save();
    }

    @Override
    protected void loadFromJson(JsonElement jsonElement) {
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        if (jsonObject.has(CONFIG_XP_ENABLED)) {
            xpEnabled = jsonObject.get(CONFIG_XP_ENABLED).getAsBoolean();
        }
        if (jsonObject.has(CONFIG_COMMAND_EXECUTOR)) {
            commandExecutor = CommandExecutor.fromString(
                jsonObject.get(CONFIG_COMMAND_EXECUTOR).getAsString()
            );
        }
        if (jsonObject.has(CONFIG_STRONG_AI_LEVEL)) {
            strongAILevel = jsonObject.get(CONFIG_STRONG_AI_LEVEL).getAsInt();
        }
        if (jsonObject.has(CONFIG_AI_TYPE)) {
            aiType = jsonObject.get(CONFIG_AI_TYPE).getAsString();
        }
        if (jsonObject.has(CONFIG_AI_DEBUG)) {
            aiDebug = jsonObject.get(CONFIG_AI_DEBUG).getAsBoolean();
        }
    }

    @Override
    protected JsonElement toJson() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(CONFIG_XP_ENABLED, xpEnabled);
        jsonObject.addProperty(CONFIG_COMMAND_EXECUTOR, commandExecutor.name());
        jsonObject.addProperty(CONFIG_STRONG_AI_LEVEL, strongAILevel);
        jsonObject.addProperty(CONFIG_AI_TYPE, aiType);
        jsonObject.addProperty(CONFIG_AI_DEBUG, aiDebug);
        return jsonObject;
    }

    public boolean isXpDisabled() {
        return !xpEnabled;
    }

    public BattleAI getCurrentAI() {
        if ("score".equals(aiType)) return new ScoreBattleAI(scoreLevelFromLegacy(strongAILevel));
        return strongAILevel == -1 ? new Generation5AI() : new StrongBattleAI(strongAILevel);
    }

    // map the legacy strongAILevel knob onto a score-AI tier, so an old config that set a strong
    // level still scales the new AI's difficulty instead of silently ignoring the setting.
    public static int scoreLevelFromLegacy(int strongLevel) {
        switch (strongLevel) {
            case 0: return 0;
            case 1: return 2;
            case 2: return 4;
            case 3: return 6;
            case 4: return 8;
            case 5: return 10;
            default: return 4;
        }
    }

}
