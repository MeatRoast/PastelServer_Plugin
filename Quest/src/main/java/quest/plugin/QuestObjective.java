package quest.plugin;

import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

public record QuestObjective(
        ObjectiveType type,
        String target,
        int amount
) {
    public boolean matchesMob(EntityType entityType) {
        return type == ObjectiveType.KILL_MOB
                && target.equalsIgnoreCase(entityType.name());
    }

    public boolean matchesBlock(Material material) {
        return (type == ObjectiveType.MINE_BLOCK || type == ObjectiveType.COLLECT_ITEM)
                && target.equalsIgnoreCase(material.name());
    }

    public boolean matchesQuest(String questId, QuestType questType) {
        if (type != ObjectiveType.COMPLETE_QUEST) return false;
        if (target.equalsIgnoreCase("daily:*")) return questType == QuestType.DAILY;
        if (target.equalsIgnoreCase("normal:*")) return questType == QuestType.NORMAL;
        if (target.equalsIgnoreCase("guild:*")) return questType == QuestType.GUILD;
        return target.equalsIgnoreCase(questId);
    }

    public static String normalizeTarget(String raw) {
        return raw.toUpperCase(Locale.ROOT);
    }
}
