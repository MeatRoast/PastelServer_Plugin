package com.guild.plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuildApi {
    record GuildMember(UUID uuid, String name, boolean online, long lastSeen, long joinedAt, boolean leader) {}
    record GuildProfile(String name, UUID leader, int memberCount, int maxMembers, boolean autoJoin) {}

    boolean hasGuild(UUID playerId);
    Optional<String> getGuildName(UUID playerId);
    Optional<GuildProfile> getGuildProfileByPlayer(UUID playerId);
    Optional<GuildProfile> getGuildProfileByName(String guildName);
    boolean isLeader(UUID playerId);
    List<GuildMember> getMembers(String guildName);
    List<UUID> getMemberIds(String guildName);
}
