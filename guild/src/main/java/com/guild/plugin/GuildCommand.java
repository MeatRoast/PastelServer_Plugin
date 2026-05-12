package com.guild.plugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class GuildCommand implements CommandExecutor, TabCompleter {
    private static final DecimalFormat MONEY_FMT = new DecimalFormat("#,###");
    private final GuildService guildService;
    private final GuildGui guildGui;
    private final MessageConfig messages;

    public GuildCommand(GuildService guildService, GuildGui guildGui, MessageConfig messages) {
        this.guildService = guildService;
        this.guildGui = guildGui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("common.player_only"));
            return true;
        }
        if (args.length == 0) { guildGui.openMyGuild(player); return true; }
        if (eq(args[0], "리스트")) { guildGui.openGuildList(player); return true; }
        if (eq(args[0], "해산")) { player.sendMessage(guildService.disbandGuild(player.getUniqueId())); return true; }
        if (eq(args[0], "생성") || eq(args[0], "창설")) { handleCreate(player, args); return true; }
        if (eq(args[0], "업그레이드")) { handleUpgrade(player, args); return true; }
        if (eq(args[0], "가격")) { handlePrice(player, args); return true; }
        if (eq(args[0], "창설권")) { handleTicket(player, args); return true; }
        if (eq(args[0], "가입")) { handleJoin(player, args); return true; }
        if (eq(args[0], "설정")) { handleSettings(player, args); return true; }
        if (eq(args[0], "초대")) { handleInvite(player, args); return true; }
        player.sendMessage(messages.get("common.unknown_command"));
        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(messages.get("usage.create")); return; }
        player.sendMessage(guildService.createGuild(args[1], player, true));
    }

    private void handleUpgrade(Player player, String[] args) {
        Optional<GuildService.Guild> guildOpt = guildService.getGuildByPlayer(player.getUniqueId());
        if (guildOpt.isEmpty()) { player.sendMessage(messages.get("guild.not_in_guild")); return; }
        GuildService.Guild guild = guildOpt.get();
        if (!guildService.isLeader(guild, player.getUniqueId())) { player.sendMessage(messages.get("guild.only_leader")); return; }
        if (args.length == 1) { guildGui.openUpgrade(player); return; }
        if (eq(args[1], "인원")) {
            int steps = 1;
            if (args.length >= 3) {
                try { steps = Math.max(1, Integer.parseInt(args[2])); }
                catch (NumberFormatException ignored) { player.sendMessage(messages.get("common.number_only")); return; }
            }
            long cost = guildService.prices().memberUpgradePer5() * steps;
            player.sendMessage(messages.get("upgrade.member_cost", Map.of("cost", MONEY_FMT.format(cost))));
            player.sendMessage(guildService.upgradeMemberLimit(guild, steps));
            return;
        }
        if (eq(args[1], "이름변경")) {
            if (args.length < 3) { player.sendMessage(messages.get("usage.upgrade_rename")); return; }
            long cost = guildService.prices().rename();
            player.sendMessage(messages.get("upgrade.rename_cost", Map.of("cost", MONEY_FMT.format(cost))));
            player.sendMessage(guildService.renameGuild(guild, args[2]));
            return;
        }
        player.sendMessage(messages.get("usage.upgrade"));
    }

    private void handlePrice(Player player, String[] args) {
        if (!player.isOp()) { player.sendMessage(messages.get("common.op_only")); return; }
        if (args.length == 1 || (args.length >= 2 && eq(args[1], "보기"))) {
            GuildService.PriceConfig p = guildService.prices();
            player.sendMessage(messages.get("price.header"));
            player.sendMessage(messages.get("price.member", Map.of("cost", MONEY_FMT.format(p.memberUpgradePer5()))));
            player.sendMessage(messages.get("price.rename", Map.of("cost", MONEY_FMT.format(p.rename()))));
            return;
        }
        if (args.length >= 4 && eq(args[1], "설정")) {
            long value;
            try { value = Long.parseLong(args[3]); }
            catch (NumberFormatException e) { player.sendMessage(messages.get("common.number_only")); return; }
            if (eq(args[2], "인원5추가")) { guildService.setPriceMemberUpgrade(value); player.sendMessage(messages.get("price.member_set", Map.of("cost", MONEY_FMT.format(value)))); return; }
            if (eq(args[2], "이름변경")) { guildService.setPriceRename(value); player.sendMessage(messages.get("price.rename_set", Map.of("cost", MONEY_FMT.format(value)))); return; }
        }
        player.sendMessage(messages.get("usage.price"));
    }

    private void handleTicket(Player player, String[] args) {
        if (!player.isOp()) { player.sendMessage(messages.get("common.op_only")); return; }
        if (args.length < 2) { player.sendMessage(messages.get("usage.ticket")); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { player.sendMessage(messages.get("common.target_not_found")); return; }
        target.getInventory().addItem(guildService.createTicketItem(1));
        player.sendMessage(messages.get("ticket.given_sender", Map.of("player", target.getName())));
        target.sendMessage(messages.get("ticket.given_target"));
    }

    private void handleJoin(Player player, String[] args) {
        if (args.length >= 2 && eq(args[1], "수락")) { handleJoinRequestDecision(player, args, true); return; }
        if (args.length >= 2 && eq(args[1], "거절")) { handleJoinRequestDecision(player, args, false); return; }
        if (args.length < 2) { player.sendMessage(messages.get("usage.join")); return; }
        String result = guildService.requestJoin(player.getUniqueId(), args[1]);
        player.sendMessage(result);
        if (result.equals(messages.get("guild.join_requested"))) {
            guildService.getGuild(args[1]).ifPresent(g -> {
                Player leader = Bukkit.getPlayer(g.leader());
                if (leader != null) leader.sendMessage(messages.get("guild.join_request_notify", Map.of("player", player.getName())));
            });
        }
    }

    private void handleJoinRequestDecision(Player player, String[] args, boolean accept) {
        Optional<GuildService.Guild> guildOpt = guildService.getGuildByPlayer(player.getUniqueId());
        if (guildOpt.isEmpty()) { player.sendMessage(messages.get("guild.not_in_guild")); return; }
        GuildService.Guild guild = guildOpt.get();
        if (!guildService.isLeader(guild, player.getUniqueId())) { player.sendMessage(messages.get("guild.only_leader")); return; }
        Set<UUID> requests = guildService.getJoinRequests(guild);
        if (requests.isEmpty()) { player.sendMessage(messages.get("guild.no_join_requests")); return; }
        UUID targetId;
        if (args.length >= 3) {
            targetId = resolveUuid(args[2]);
            if (targetId == null) { player.sendMessage(messages.get("common.target_not_found")); return; }
        } else if (requests.size() == 1) targetId = requests.iterator().next();
        else { player.sendMessage(messages.get("usage.join_decision")); return; }
        String result = guildService.handleJoinRequest(guild, targetId, accept);
        player.sendMessage(result);
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) target.sendMessage(messages.get("guild.join_request_result", Map.of("result", result)));
    }

    private void handleSettings(Player player, String[] args) {
        Optional<GuildService.Guild> guildOpt = guildService.getGuildByPlayer(player.getUniqueId());
        if (guildOpt.isEmpty()) { player.sendMessage(messages.get("guild.not_in_guild")); return; }
        GuildService.Guild guild = guildOpt.get();
        if (!guildService.isLeader(guild, player.getUniqueId())) { player.sendMessage(messages.get("guild.only_leader")); return; }
        if (args.length >= 2 && eq(args[1], "자동가입")) {
            boolean newState = !guild.autoJoin();
            if (args.length >= 3) newState = parseOnOff(args[2], guild.autoJoin());
            player.sendMessage(guildService.setAutoJoin(guild, newState));
            return;
        }
        if (args.length >= 3 && eq(args[1], "추방")) {
            UUID target = resolveUuid(args[2]);
            if (target == null) { player.sendMessage(messages.get("common.target_not_found")); return; }
            String result = guildService.kick(guild, target);
            player.sendMessage(result);
            Player tp = Bukkit.getPlayer(target);
            if (tp != null) tp.sendMessage(messages.get("guild.kicked_notify"));
            return;
        }
        if (args.length >= 3 && eq(args[1], "차단")) {
            UUID target = resolveUuid(args[2]);
            if (target == null) { player.sendMessage(messages.get("common.target_not_found")); return; }
            String result = guildService.block(guild, target);
            player.sendMessage(result);
            Player tp = Bukkit.getPlayer(target);
            if (tp != null) tp.sendMessage(messages.get("guild.blocked_notify"));
            return;
        }
        player.sendMessage(messages.get("usage.settings"));
    }

    private void handleInvite(Player player, String[] args) {
        if (args.length >= 2 && eq(args[1], "수락")) { handleInviteDecision(player, args, true); return; }
        if (args.length >= 2 && eq(args[1], "거절")) { handleInviteDecision(player, args, false); return; }
        Optional<GuildService.Guild> guildOpt = guildService.getGuildByPlayer(player.getUniqueId());
        if (guildOpt.isEmpty()) { player.sendMessage(messages.get("guild.not_in_guild")); return; }
        GuildService.Guild guild = guildOpt.get();
        if (!guildService.isLeader(guild, player.getUniqueId())) { player.sendMessage(messages.get("guild.only_leader")); return; }
        if (args.length < 2) { player.sendMessage(messages.get("usage.invite")); return; }
        UUID target = resolveUuid(args[1]);
        if (target == null) { player.sendMessage(messages.get("common.target_not_found")); return; }
        String result = guildService.invite(guild, target);
        player.sendMessage(result);
        Player targetP = Bukkit.getPlayer(target);
        if (targetP != null) {
            targetP.sendMessage(messages.get("guild.invite_notify", Map.of("guild", guild.name())));
            targetP.sendMessage(messages.get("guild.invite_notify_usage"));
        }
    }

    private void handleInviteDecision(Player player, String[] args, boolean accept) {
        Set<String> inviteSet = guildService.getInvites(player.getUniqueId());
        if (inviteSet.isEmpty()) { player.sendMessage(messages.get("guild.no_invites")); return; }
        String guildName;
        if (args.length >= 3) guildName = args[2];
        else if (inviteSet.size() == 1) guildName = inviteSet.iterator().next();
        else { player.sendMessage(messages.get("usage.invite_decision")); return; }
        player.sendMessage(guildService.handleInvite(player.getUniqueId(), guildName, accept));
    }

    private UUID resolveUuid(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) return online.getUniqueId();
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(playerName);
        if (offline != null && offline.getName() != null) return offline.getUniqueId();
        return null;
    }

    private boolean parseOnOff(String input, boolean defaultValue) {
        if (input.equalsIgnoreCase("on")) return true;
        if (input.equalsIgnoreCase("off")) return false;
        return defaultValue;
    }

    private boolean eq(String v, String k) { return v.equalsIgnoreCase(k); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> s = new ArrayList<>();
        if (args.length == 1) return filter(List.of("리스트", "창설", "해산", "업그레이드", "가격", "창설권", "가입", "설정", "초대"), args[0]);
        if (args.length == 2 && eq(args[0], "가입")) return filter(List.of("수락", "거절"), args[1]);
        if (args.length == 2 && eq(args[0], "설정")) return filter(List.of("자동가입", "추방", "차단"), args[1]);
        if (args.length == 2 && eq(args[0], "초대")) return filter(List.of("수락", "거절"), args[1]);
        if (args.length == 2 && eq(args[0], "업그레이드")) return filter(List.of("인원", "이름변경"), args[1]);
        if (args.length == 2 && eq(args[0], "가격")) return filter(List.of("보기", "설정"), args[1]);
        if (args.length == 3 && eq(args[0], "가격") && eq(args[1], "설정")) return filter(List.of("인원5추가", "이름변경"), args[2]);
        if (args.length == 3 && eq(args[0], "설정") && eq(args[1], "자동가입")) return filter(List.of("on", "off"), args[2]);
        return s;
    }

    private List<String> filter(List<String> source, String token) { return source.stream().filter(v -> v.startsWith(token)).toList(); }
}
