package com.guild.plugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class GuildApiImpl implements GuildApi {
    private final GuildService guildService;

    public GuildApiImpl(GuildService guildService) {
        this.guildService = guildService;
    }

    @Override
    public boolean hasGuild(UUID playerId) {
        return guildService.hasGuild(playerId);
    }

    @Override
    public Optional<String> getGuildName(UUID playerId) {
        return guildService.getGuildByPlayer(playerId).map(GuildService.Guild::name);
    }

    @Override
    public Optional<GuildProfile> getGuildProfileByPlayer(UUID playerId) {
        return guildService.getGuildByPlayer(playerId).map(this::toProfile);
    }

    @Override
    public Optional<GuildProfile> getGuildProfileByName(String guildName) {
        return guildService.getGuild(guildName).map(this::toProfile);
    }

    @Override
    public boolean isLeader(UUID playerId) {
        Optional<GuildService.Guild> guildOpt = guildService.getGuildByPlayer(playerId);
        return guildOpt.filter(guild -> guild.leader().equals(playerId)).isPresent();
    }

    @Override
    public List<GuildMember> getMembers(String guildName) {
        Optional<GuildService.Guild> guildOpt = guildService.getGuild(guildName);
        if (guildOpt.isEmpty()) return Collections.emptyList();
        GuildService.Guild guild = guildOpt.get();
        return guildService.membersOf(guild).stream().map(m -> {
            OfflinePlayer p = Bukkit.getOfflinePlayer(m.uuid());
            return new GuildMember(
                    m.uuid(),
                    guildService.displayName(m.uuid()),
                    p.isOnline(),
                    guildService.lastSeen(m.uuid()),
                    m.joinedAt(),
                    guild.leader().equals(m.uuid())
            );
        }).toList();
    }

    @Override
    public List<UUID> getMemberIds(String guildName) {
        Optional<GuildService.Guild> guildOpt = guildService.getGuild(guildName);
        if (guildOpt.isEmpty()) return Collections.emptyList();
        return List.copyOf(guildOpt.get().members().keySet());
    }

    private GuildProfile toProfile(GuildService.Guild guild) {
        return new GuildProfile(
                guild.name(),
                guild.leader(),
                guildService.memberCount(guild),
                guild.maxMembers(),
                guild.autoJoin()
        );
    }
}
