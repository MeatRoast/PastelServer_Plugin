package com.guild.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class GuildGui implements Listener {
    private static final String TITLE_MY_GUILD = "길드 정보";
    private static final String TITLE_GUILD_LIST = "길드 리스트";
    private static final String TITLE_UPGRADE = "길드 업그레이드";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DecimalFormat MONEY_FMT = new DecimalFormat("#,###");

    private final GuildService guildService;
    private final MessageConfig messages;

    public GuildGui(GuildService guildService, MessageConfig messages) {
        this.guildService = guildService;
        this.messages = messages;
    }

    public void openMyGuild(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_MY_GUILD);
        Optional<GuildService.Guild> guildOpt = guildService.getGuildByPlayer(player.getUniqueId());
        if (guildOpt.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, messages.get("guild.no_guild"), List.of("")));
            player.openInventory(inv);
            return;
        }
        GuildService.Guild guild = guildOpt.get();
        inv.setItem(4, item(Material.NETHER_STAR, "소속길드 : " + guild.name(), List.of("인원: " + guildService.memberCount(guild) + "/" + guild.maxMembers(), "자동가입: " + (guild.autoJoin() ? "ON" : "OFF"))));
        List<GuildService.MemberInfo> members = guildService.membersOf(guild).stream().sorted(Comparator.comparing(m -> guildService.displayName(m.uuid()))).toList();
        inv.setItem(8, item(Material.LIME_WOOL, "현재 접속자", List.of("온라인 길드원 머리 목록")));
        int onlineSlot = 9;
        for (GuildService.MemberInfo m : members) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(m.uuid());
            if (!op.isOnline() || onlineSlot > 17) continue;
            inv.setItem(onlineSlot++, head(m.uuid(), guildService.displayName(m.uuid()), memberLore(guild, m, true)));
        }
        inv.setItem(17, item(Material.GRAY_WOOL, "전체 길드원", List.of("아래 칸에서 전체 명단 확인")));
        int slot = 18;
        for (GuildService.MemberInfo m : members) {
            if (slot >= 54) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(m.uuid());
            inv.setItem(slot++, head(m.uuid(), guildService.displayName(m.uuid()), memberLore(guild, m, op.isOnline())));
        }
        player.openInventory(inv);
    }

    public void openGuildList(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, TITLE_GUILD_LIST);
        List<GuildService.Guild> guilds = guildService.getGuilds().stream().sorted(Comparator.comparing(GuildService.Guild::name)).toList();
        if (guilds.isEmpty()) {
            inv.setItem(22, item(Material.PAPER, messages.get("guild.no_guild_created"), List.of("")));
            player.openInventory(inv);
            return;
        }
        int slot = 0;
        for (GuildService.Guild g : guilds) {
            if (slot >= 54) break;
            inv.setItem(slot++, item(Material.BOOK, g.name(), List.of("길드장: " + guildService.displayName(g.leader()), "인원: " + guildService.memberCount(g) + "/" + g.maxMembers(), "자동가입: " + (g.autoJoin() ? "ON" : "OFF"))));
        }
        player.openInventory(inv);
    }

    public void openUpgrade(Player player) {
        Optional<GuildService.Guild> guildOpt = guildService.getGuildByPlayer(player.getUniqueId());
        if (guildOpt.isEmpty()) { player.sendMessage(messages.get("guild.not_in_guild")); return; }
        GuildService.Guild guild = guildOpt.get();
        if (!guildService.isLeader(guild, player.getUniqueId())) { player.sendMessage(messages.get("guild.only_leader")); return; }
        GuildService.PriceConfig prices = guildService.prices();
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_UPGRADE);
        inv.setItem(11, item(Material.PLAYER_HEAD, "인원 +5 업그레이드", List.of("비용: " + MONEY_FMT.format(prices.memberUpgradePer5()), "현재 최대 인원: " + guild.maxMembers(), "클릭 시 +5 적용")));
        inv.setItem(15, item(Material.NAME_TAG, "이름 변경", List.of("비용: " + MONEY_FMT.format(prices.rename()), "명령어: /길드 업그레이드 이름변경 <새길드명>")));
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!TITLE_MY_GUILD.equals(title) && !TITLE_GUILD_LIST.equals(title) && !TITLE_UPGRADE.equals(title)) return;
        event.setCancelled(true);
        if (!TITLE_UPGRADE.equals(title)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() != Material.PLAYER_HEAD) return;
        Optional<GuildService.Guild> guildOpt = guildService.getGuildByPlayer(player.getUniqueId());
        if (guildOpt.isEmpty()) return;
        GuildService.Guild guild = guildOpt.get();
        if (!guildService.isLeader(guild, player.getUniqueId())) return;
        long cost = guildService.prices().memberUpgradePer5();
        player.sendMessage(messages.get("upgrade.member_cost", java.util.Map.of("cost", MONEY_FMT.format(cost))));
        player.sendMessage(guildService.upgradeMemberLimit(guild, 1));
        openUpgrade(player);
    }

    private List<String> memberLore(GuildService.Guild guild, GuildService.MemberInfo member, boolean online) {
        List<String> lore = new ArrayList<>();
        lore.add("상태: " + (online ? "ON" : "OFF"));
        lore.add("마지막 접속일: " + formatTime(guildService.lastSeen(member.uuid())));
        lore.add("가입일: " + formatTime(member.joinedAt()));
        lore.add(guild.leader().equals(member.uuid()) ? "직책: 길드장" : "직책: 길드원");
        return lore;
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); meta.setLore(lore); stack.setItemMeta(meta); }
        return stack;
    }

    private ItemStack head(UUID uuid, String name, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) { meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid)); meta.setDisplayName(name); meta.setLore(lore); head.setItemMeta(meta); }
        return head;
    }

    private String formatTime(long millis) {
        if (millis <= 0) return "-";
        return TIME_FMT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime());
    }
}
