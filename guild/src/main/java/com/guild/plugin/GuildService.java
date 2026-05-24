package com.guild.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class GuildService {
    public record MemberInfo(UUID uuid, long joinedAt) {}
    public record Guild(String name, UUID leader, boolean autoJoin, int maxMembers, Map<UUID, Long> members, Set<UUID> blocked) {}
    public record PriceConfig(long memberUpgradePer5, long rename) {}

    private static final int BASE_MAX_MEMBERS = 5;

    private final JavaPlugin plugin;
    private final MessageConfig messages;
    private final File dataFile;
    private final Map<String, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, String> playerGuild = new HashMap<>();
    private final Map<String, Set<UUID>> joinRequests = new HashMap<>();
    private final Map<UUID, Set<String>> invites = new HashMap<>();

    public GuildService(JavaPlugin plugin, MessageConfig messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.dataFile = new File(plugin.getDataFolder(), "guilds.yml");
    }

    public void load() {
        guilds.clear();
        playerGuild.clear();
        joinRequests.clear();
        invites.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yaml.getConfigurationSection("guilds");
        if (root == null) return;
        for (String guildName : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(guildName);
            if (sec == null) continue;
            UUID leader = parseUuid(sec.getString("leader")).orElse(null);
            if (leader == null) continue;
            boolean autoJoin = sec.getBoolean("autoJoin", false);
            int maxMembers = Math.max(BASE_MAX_MEMBERS, sec.getInt("maxMembers", BASE_MAX_MEMBERS));
            Map<UUID, Long> members = new LinkedHashMap<>();
            ConfigurationSection membersSec = sec.getConfigurationSection("members");
            if (membersSec != null) {
                for (String uuidText : membersSec.getKeys(false)) {
                    Optional<UUID> id = parseUuid(uuidText);
                    if (id.isEmpty()) continue;
                    members.put(id.get(), membersSec.getLong(uuidText, Instant.now().toEpochMilli()));
                }
            }
            members.putIfAbsent(leader, Instant.now().toEpochMilli());
            Set<UUID> blocked = new HashSet<>();
            for (String b : sec.getStringList("blocked")) parseUuid(b).ifPresent(blocked::add);
            Guild guild = new Guild(guildName, leader, autoJoin, maxMembers, members, blocked);
            guilds.put(guildName.toLowerCase(), guild);
            for (UUID member : members.keySet()) playerGuild.put(member, guildName.toLowerCase());
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("guilds");
        for (Guild guild : guilds.values()) {
            ConfigurationSection sec = root.createSection(guild.name());
            sec.set("leader", guild.leader().toString());
            sec.set("autoJoin", guild.autoJoin());
            sec.set("maxMembers", guild.maxMembers());
            ConfigurationSection membersSec = sec.createSection("members");
            for (Map.Entry<UUID, Long> e : guild.members().entrySet()) {
                membersSec.set(e.getKey().toString(), e.getValue());
            }
            List<String> blocked = new ArrayList<>();
            for (UUID id : guild.blocked()) blocked.add(id.toString());
            sec.set("blocked", blocked);
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("guilds.yml save failed: " + e.getMessage());
        }
    }

    public Collection<Guild> getGuilds() { return Collections.unmodifiableCollection(guilds.values()); }
    public Optional<Guild> getGuildByPlayer(UUID playerId) { return Optional.ofNullable(playerGuild.get(playerId)).map(guilds::get); }
    public Optional<Guild> getGuild(String name) { return Optional.ofNullable(guilds.get(name.toLowerCase())); }
    public boolean hasGuild(UUID playerId) { return playerGuild.containsKey(playerId); }

    public String createGuild(String guildName, Player leader, boolean consumeTicket) {
        String key = guildName.toLowerCase();
        if (guilds.containsKey(key)) {
            return messages.get("guild.already_exists");
        }
        if (hasGuild(leader.getUniqueId())) {
            return messages.get("guild.already_in_guild");
        }
        if (consumeTicket && !removeCreateTicketItem(leader, 1)) {
            return messages.get("ticket.required");
        }
        Map<UUID, Long> members = new LinkedHashMap<>();
        members.put(leader.getUniqueId(), Instant.now().toEpochMilli());
        guilds.put(key, new Guild(guildName, leader.getUniqueId(), false, BASE_MAX_MEMBERS, members, new HashSet<>()));
        playerGuild.put(leader.getUniqueId(), key);
        save();
        return messages.get("guild.created", Map.of("guild", guildName));
    }

    public String disbandGuild(UUID leaderId) {
        Optional<Guild> guildOpt = getGuildByPlayer(leaderId);
        if (guildOpt.isEmpty()) return messages.get("guild.not_in_guild");
        Guild guild = guildOpt.get();
        if (!guild.leader().equals(leaderId)) return messages.get("guild.only_leader");
        String key = guild.name().toLowerCase();
        for (UUID member : guild.members().keySet()) playerGuild.remove(member);
        guilds.remove(key);
        joinRequests.remove(key);
        invites.values().forEach(set -> set.remove(key));
        save();
        return messages.get("guild.disbanded");
    }

    public String renameGuild(Guild guild, String newName) {
        String oldKey = guild.name().toLowerCase();
        String newKey = newName.toLowerCase();
        if (guilds.containsKey(newKey)) return messages.get("guild.name_exists");
        Guild renamed = new Guild(newName, guild.leader(), guild.autoJoin(), guild.maxMembers(), guild.members(), guild.blocked());
        guilds.remove(oldKey);
        guilds.put(newKey, renamed);
        for (UUID id : guild.members().keySet()) playerGuild.put(id, newKey);
        Set<UUID> req = joinRequests.remove(oldKey);
        if (req != null) joinRequests.put(newKey, req);
        for (Set<String> invSet : invites.values()) if (invSet.remove(oldKey)) invSet.add(newKey);
        save();
        return messages.get("guild.renamed", Map.of("guild", newName));
    }

    public String upgradeMemberLimit(Guild guild, int steps) {
        int newMax = guild.maxMembers() + (Math.max(1, steps) * 5);
        guilds.put(guild.name().toLowerCase(), new Guild(guild.name(), guild.leader(), guild.autoJoin(), newMax, guild.members(), guild.blocked()));
        save();
        return messages.get("guild.member_upgraded", Map.of("max", String.valueOf(newMax)));
    }

    public boolean isLeader(Guild guild, UUID playerId) { return guild.leader().equals(playerId); }
    public int memberCount(Guild guild) { return guild.members().size(); }

    public String requestJoin(UUID playerId, String guildName) {
        Optional<Guild> guildOpt = getGuild(guildName);
        if (guildOpt.isEmpty()) return messages.get("guild.not_found");
        Guild guild = guildOpt.get();
        if (hasGuild(playerId)) return messages.get("guild.already_in_guild");
        if (guild.blocked().contains(playerId)) return messages.get("guild.blocked");
        if (memberCount(guild) >= guild.maxMembers()) return messages.get("guild.full");
        if (guild.autoJoin()) {
            guild.members().put(playerId, Instant.now().toEpochMilli());
            playerGuild.put(playerId, guild.name().toLowerCase());
            save();
            return messages.get("guild.auto_joined");
        }
        joinRequests.computeIfAbsent(guild.name().toLowerCase(), k -> new HashSet<>()).add(playerId);
        return messages.get("guild.join_requested");
    }

    public Set<UUID> getJoinRequests(Guild guild) { return joinRequests.getOrDefault(guild.name().toLowerCase(), Collections.emptySet()); }

    public String handleJoinRequest(Guild guild, UUID targetId, boolean accept) {
        Set<UUID> requests = joinRequests.getOrDefault(guild.name().toLowerCase(), Collections.emptySet());
        if (!requests.contains(targetId)) return messages.get("guild.join_request_not_found");
        requests.remove(targetId);
        if (requests.isEmpty()) joinRequests.remove(guild.name().toLowerCase());
        if (!accept) return messages.get("guild.join_rejected");
        if (hasGuild(targetId)) return messages.get("guild.target_already_in_guild");
        if (memberCount(guild) >= guild.maxMembers()) return messages.get("guild.full");
        guild.members().put(targetId, Instant.now().toEpochMilli());
        playerGuild.put(targetId, guild.name().toLowerCase());
        save();
        return messages.get("guild.join_accepted");
    }

    public String setAutoJoin(Guild guild, boolean enabled) {
        guilds.put(guild.name().toLowerCase(), new Guild(guild.name(), guild.leader(), enabled, guild.maxMembers(), guild.members(), guild.blocked()));
        save();
        return messages.get(enabled ? "guild.autojoin_on" : "guild.autojoin_off");
    }

    public String kick(Guild guild, UUID targetId) {
        if (!guild.members().containsKey(targetId)) return messages.get("guild.target_not_member");
        if (guild.leader().equals(targetId)) return messages.get("guild.cannot_kick_leader");
        guild.members().remove(targetId);
        playerGuild.remove(targetId);
        save();
        return messages.get("guild.kicked");
    }

    public String block(Guild guild, UUID targetId) {
        if (guild.members().containsKey(targetId)) {
            guild.members().remove(targetId);
            playerGuild.remove(targetId);
        }
        guild.blocked().add(targetId);
        save();
        return messages.get("guild.blocked_target");
    }

    public String invite(Guild guild, UUID targetId) {
        if (guild.blocked().contains(targetId)) return messages.get("guild.target_blocked");
        if (hasGuild(targetId)) return messages.get("guild.target_already_in_guild");
        invites.computeIfAbsent(targetId, k -> new HashSet<>()).add(guild.name().toLowerCase());
        return messages.get("guild.invite_sent");
    }

    public Set<String> getInvites(UUID playerId) { return invites.getOrDefault(playerId, Collections.emptySet()); }

    public String handleInvite(UUID playerId, String guildName, boolean accept) {
        Set<String> userInvites = invites.getOrDefault(playerId, Collections.emptySet());
        String key = guildName.toLowerCase();
        if (!userInvites.contains(key)) return messages.get("guild.invite_not_found");
        userInvites.remove(key);
        if (userInvites.isEmpty()) invites.remove(playerId);
        if (!accept) return messages.get("guild.invite_rejected");
        if (hasGuild(playerId)) return messages.get("guild.already_in_guild");
        Guild guild = guilds.get(key);
        if (guild == null) return messages.get("guild.not_found");
        if (memberCount(guild) >= guild.maxMembers()) return messages.get("guild.full");
        if (guild.blocked().contains(playerId)) return messages.get("guild.blocked");
        guild.members().put(playerId, Instant.now().toEpochMilli());
        playerGuild.put(playerId, key);
        save();
        return messages.get("guild.invite_accepted");
    }

    public ItemStack createTicketItem(int amount) {
        ItemStack item = new ItemStack(Material.BOOK, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(messages.get("ticket.item_name"));
            meta.setLore(List.of(messages.get("ticket.item_lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isCreateTicketItem(ItemStack item) {
        if (item == null || item.getType() != Material.BOOK) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && messages.get("ticket.item_name").equals(meta.getDisplayName());
    }

    public boolean removeCreateTicketItem(Player player, int amount) {
        int need = Math.max(1, amount);
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) if (isCreateTicketItem(item)) total += item.getAmount();
        if (total < need) return false;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize() && need > 0; i++) {
            ItemStack item = inv.getItem(i);
            if (!isCreateTicketItem(item)) continue;
            int take = Math.min(item.getAmount(), need);
            int left = item.getAmount() - take;
            if (left <= 0) inv.setItem(i, null); else item.setAmount(left);
            need -= take;
        }
        return true;
    }

    public List<MemberInfo> membersOf(Guild guild) {
        List<MemberInfo> list = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : guild.members().entrySet()) list.add(new MemberInfo(e.getKey(), e.getValue()));
        return list;
    }

    public String displayName(UUID playerId) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(playerId);
        return p.getName() != null ? p.getName() : playerId.toString();
    }

    public long lastSeen(UUID playerId) { return Bukkit.getOfflinePlayer(playerId).getLastPlayed(); }

    public PriceConfig prices() {
        return new PriceConfig(plugin.getConfig().getLong("prices.member_upgrade_per_5", 10000L), plugin.getConfig().getLong("prices.rename", 10000L));
    }
    public void setPriceMemberUpgrade(long value) { plugin.getConfig().set("prices.member_upgrade_per_5", Math.max(0, value)); plugin.saveConfig(); }
    public void setPriceRename(long value) { plugin.getConfig().set("prices.rename", Math.max(0, value)); plugin.saveConfig(); }

    private Optional<UUID> parseUuid(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        try { return Optional.of(UUID.fromString(text)); } catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
