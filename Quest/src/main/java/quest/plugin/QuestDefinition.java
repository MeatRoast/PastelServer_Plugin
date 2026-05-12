package quest.plugin;

import java.util.List;

public record QuestDefinition(
        String id,
        String title,
        QuestType type,
        boolean randomDailyPool,
        int rewardCoin,
        List<QuestObjective> objectives
) {
}

