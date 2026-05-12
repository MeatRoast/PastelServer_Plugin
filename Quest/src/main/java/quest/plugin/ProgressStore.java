package quest.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ProgressStore {
    private final Map<String, Integer> values = new HashMap<>();

    public int add(String key, int delta) {
        int next = values.getOrDefault(key, 0) + delta;
        values.put(key, next);
        return next;
    }

    public int get(String key) {
        return values.getOrDefault(key, 0);
    }

    public void reset() {
        values.clear();
    }

    public static String playerQuestKey(UUID playerId, String questId) {
        return playerId + "::" + questId;
    }

    public static String playerObjectiveKey(UUID playerId, String questId, int objectiveIndex) {
        return playerId + "::" + questId + "::obj:" + objectiveIndex;
    }

    public static String guildQuestKey(String guildId, String questId) {
        return guildId + "::" + questId;
    }

    public static String guildObjectiveKey(String guildId, String questId, int objectiveIndex) {
        return guildId + "::" + questId + "::obj:" + objectiveIndex;
    }
}
