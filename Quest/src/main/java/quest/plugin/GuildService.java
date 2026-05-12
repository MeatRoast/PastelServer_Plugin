package quest.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GuildService {
    private final Map<UUID, String> playerGuild = new HashMap<>();
    private final Map<String, Set<UUID>> guildMembers = new HashMap<>();

    public String getGuildOf(UUID playerId) {
        return playerGuild.get(playerId);
    }

    public void setGuild(UUID playerId, String guildId) {
        String prev = playerGuild.put(playerId, guildId);
        if (prev != null && guildMembers.containsKey(prev)) {
            guildMembers.get(prev).remove(playerId);
        }
        guildMembers.computeIfAbsent(guildId, k -> new java.util.HashSet<>()).add(playerId);
    }

    public Set<UUID> members(String guildId) {
        return guildMembers.getOrDefault(guildId, Set.of());
    }
}

