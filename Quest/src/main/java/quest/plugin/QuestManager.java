package quest.plugin;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class QuestManager {
    private final JavaPlugin plugin;
    private final Map<String, QuestDefinition> quests = new HashMap<>();
    private final List<String> activeDailyQuests = new ArrayList<>();
    private final ProgressStore progressStore = new ProgressStore();
    private final GuildService guildService = new GuildService();
    private final Set<String> completedPlayerQuests = new java.util.HashSet<>();
    private final Set<String> completedGuildQuests = new java.util.HashSet<>();
    private final DiscordWebhookClient discord;
    private int dailyCount;

    public QuestManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dailyCount = plugin.getConfig().getInt("daily.random-count", 3);
        this.discord = new DiscordWebhookClient(
                plugin,
                plugin.getConfig().getString("bot.endpoint", plugin.getConfig().getString("discord.endpoint", "")),
                plugin.getConfig().getString("bot.token", plugin.getConfig().getString("discord.token", ""))
        );
    }

    public void loadAll() {
        quests.clear();
        java.io.File file = new java.io.File(plugin.getDataFolder(), "quests.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("quests");
        if (section == null) {
            plugin.getLogger().warning("No quests section found.");
            return;
        }
        boolean changed = false;
        for (String id : section.getKeys(false)) {
            ConfigurationSection q = section.getConfigurationSection(id);
            if (q == null) continue;
            String rawType = q.getString("type");
            if (rawType == null || rawType.isBlank()) {
                q.set("type", "NORMAL");
                rawType = "NORMAL";
                changed = true;
                plugin.getLogger().warning("quests." + id + ".type missing. Auto-filled NORMAL");
            }
            QuestType type;
            try {
                type = QuestType.valueOf(rawType.toUpperCase());
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("quests." + id + ".type invalid: " + rawType + " (skipped)");
                continue;
            }

            if (!q.isSet("randomDailyPool")) {
                q.set("randomDailyPool", false);
                changed = true;
            }
            if (!q.isSet("rewardCoin")) {
                q.set("rewardCoin", 0);
                changed = true;
            }
            if (!q.isSet("title")) {
                q.set("title", id);
                changed = true;
            }
            boolean randomPool = q.getBoolean("randomDailyPool", false);
            int reward = Math.max(0, q.getInt("rewardCoin", 0));
            String title = q.getString("title", id);
            List<QuestObjective> objectives = new ArrayList<>();
            List<Map<?, ?>> rawObjectives = q.getMapList("objectives");
            if (rawObjectives.isEmpty()) {
                plugin.getLogger().warning("quests." + id + ".objectives missing/empty (skipped)");
                continue;
            }
            int idx = 0;
            for (Map<?, ?> raw : rawObjectives) {
                idx++;
                if (raw.get("type") == null || raw.get("target") == null || raw.get("amount") == null) {
                    plugin.getLogger().warning("quests." + id + ".objectives[" + idx + "] missing field (skipped)");
                    continue;
                }
                ObjectiveType ot;
                try {
                    ot = ObjectiveType.valueOf(String.valueOf(raw.get("type")).toUpperCase());
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("quests." + id + ".objectives[" + idx + "].type invalid (skipped)");
                    continue;
                }
                String target = QuestObjective.normalizeTarget(String.valueOf(raw.get("target")));
                int amount;
                try {
                    amount = Integer.parseInt(String.valueOf(raw.get("amount")));
                } catch (NumberFormatException ex) {
                    plugin.getLogger().warning("quests." + id + ".objectives[" + idx + "].amount invalid (skipped)");
                    continue;
                }
                if (amount <= 0) {
                    plugin.getLogger().warning("quests." + id + ".objectives[" + idx + "].amount <= 0 (skipped)");
                    continue;
                }
                objectives.add(new QuestObjective(ot, target, amount));
            }
            if (objectives.isEmpty()) {
                plugin.getLogger().warning("quests." + id + " has no valid objectives (skipped)");
                continue;
            }
            quests.put(id, new QuestDefinition(id, title, type, randomPool, reward, objectives));
        }
        if (changed) {
            try {
                yml.save(file);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save auto-filled quests.yml: " + e.getMessage());
            }
        }
        resetDailyQuests();
    }

    public void startDailyResetTask() {
        long delay = ticksUntilNextMidnight();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            resetDailyQuests();
            progressStore.reset();
            completedPlayerQuests.clear();
            completedGuildQuests.clear();
            plugin.getLogger().info("Daily quests reset.");
        }, delay, 20L * 60 * 60 * 24);
    }

    private long ticksUntilNextMidnight() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime next = now.toLocalDate().plusDays(1).atStartOfDay();
        long seconds = Duration.between(now, next).toSeconds();
        return Math.max(20L, seconds * 20L);
    }

    public void resetDailyQuests() {
        activeDailyQuests.clear();
        List<String> pool = quests.values().stream()
                .filter(q -> q.type() == QuestType.DAILY && q.randomDailyPool())
                .map(QuestDefinition::id)
                .toList();
        List<String> mutable = new ArrayList<>(pool);
        Collections.shuffle(mutable, ThreadLocalRandom.current());
        int n = Math.min(dailyCount, mutable.size());
        activeDailyQuests.addAll(mutable.subList(0, n));
    }

    public void onMobKill(Player p, EntityType type) {
        for (QuestDefinition q : eligibleFor(p.getUniqueId(), null)) {
            for (int i = 0; i < q.objectives().size(); i++) {
                QuestObjective objective = q.objectives().get(i);
                if (objective.matchesMob(type)) {
                    pushProgressPlayer(p, q, i, 1);
                }
            }
        }
    }

    public void onBlockMine(Player p, Material material) {
        for (QuestDefinition q : eligibleFor(p.getUniqueId(), null)) {
            for (int i = 0; i < q.objectives().size(); i++) {
                QuestObjective objective = q.objectives().get(i);
                if (objective.type() == ObjectiveType.MINE_BLOCK && objective.matchesBlock(material)) {
                    pushProgressPlayer(p, q, i, 1);
                }
            }
        }
    }

    public void onItemCollect(Player p, Material material, int amount) {
        for (QuestDefinition q : eligibleFor(p.getUniqueId(), null)) {
            for (int i = 0; i < q.objectives().size(); i++) {
                QuestObjective objective = q.objectives().get(i);
                if (objective.type() == ObjectiveType.COLLECT_ITEM && objective.matchesBlock(material)) {
                    pushProgressPlayer(p, q, i, amount);
                }
            }
        }
    }

    private List<QuestDefinition> eligibleFor(UUID playerId, String guildId) {
        List<QuestDefinition> result = new ArrayList<>();
        for (QuestDefinition q : quests.values()) {
            if (q.type() == QuestType.DAILY && !activeDailyQuests.contains(q.id())) continue;
            if (q.type() == QuestType.GUILD && guildId == null && guildService.getGuildOf(playerId) == null) continue;
            result.add(q);
        }
        return result;
    }

    private void pushProgressPlayer(Player p, QuestDefinition q, int objectiveIndex, int delta) {
        String key = ProgressStore.playerObjectiveKey(p.getUniqueId(), q.id(), objectiveIndex);
        progressStore.add(key, delta);
        tryCompletePlayerQuest(p, q);
    }

    public void tryCompletePlayerQuest(Player p, QuestDefinition q) {
        String completeKey = ProgressStore.playerQuestKey(p.getUniqueId(), q.id());
        if (completedPlayerQuests.contains(completeKey)) return;
        boolean done = true;
        for (int i = 0; i < q.objectives().size(); i++) {
            QuestObjective o = q.objectives().get(i);
            if (progressStore.get(ProgressStore.playerObjectiveKey(p.getUniqueId(), q.id(), i)) < o.amount()) {
                done = false;
                break;
            }
        }
        if (!done) return;
        completedPlayerQuests.add(completeKey);
        p.sendMessage("§a퀘스트 완료: " + q.title() + " / 보상 " + q.rewardCoin() + " 코인");
        discord.sendQuestClear(p.getName(), q.title(), q.rewardCoin());
        triggerCompleteQuestObjective(p, q.id());
    }

    private void triggerCompleteQuestObjective(Player p, String completedQuestId) {
        QuestDefinition completed = quests.get(completedQuestId);
        QuestType completedType = completed != null ? completed.type() : QuestType.NORMAL;
        for (QuestDefinition q : quests.values()) {
            for (int i = 0; i < q.objectives().size(); i++) {
                QuestObjective o = q.objectives().get(i);
                if (o.matchesQuest(completedQuestId, completedType)) {
                    pushProgressPlayer(p, q, i, 1);
                }
            }
        }
    }

    public void assignGuild(Player player, String guildId) {
        guildService.setGuild(player.getUniqueId(), guildId);
    }

    public void addGuildProgress(Player player, Material mat, int amount) {
        String guildId = guildService.getGuildOf(player.getUniqueId());
        if (guildId == null) return;
        for (QuestDefinition q : quests.values()) {
            if (q.type() != QuestType.GUILD) continue;
            for (int i = 0; i < q.objectives().size(); i++) {
                QuestObjective o = q.objectives().get(i);
                if (o.matchesBlock(mat)) {
                    String key = ProgressStore.guildQuestKey(guildId, q.id());
                    if (completedGuildQuests.contains(key)) continue;
                    progressStore.add(ProgressStore.guildObjectiveKey(guildId, q.id(), i), amount);
                    if (isGuildQuestComplete(guildId, q)) {
                        completedGuildQuests.add(key);
                        rewardGuild(guildId, q);
                    }
                }
            }
        }
    }

    private boolean isGuildQuestComplete(String guildId, QuestDefinition q) {
        for (int i = 0; i < q.objectives().size(); i++) {
            QuestObjective objective = q.objectives().get(i);
            int v = progressStore.get(ProgressStore.guildObjectiveKey(guildId, q.id(), i));
            if (v < objective.amount()) return false;
        }
        return true;
    }

    private void rewardGuild(String guildId, QuestDefinition q) {
        for (UUID member : guildService.members(guildId)) {
            Player p = Bukkit.getPlayer(member);
            if (p != null && p.isOnline()) {
                p.sendMessage("§b길드 퀘스트 완료: " + q.title() + " / 보상 " + q.rewardCoin() + " 코인");
            }
        }
        discord.sendQuestClear("guild:" + guildId, q.title(), q.rewardCoin());
    }

    public Collection<QuestDefinition> getQuests() {
        return quests.values();
    }

    public QuestDefinition getQuest(String questId) {
        return quests.get(questId);
    }

    public int getPlayerObjectiveProgress(Player player, QuestDefinition q, int objectiveIndex) {
        return progressStore.get(ProgressStore.playerObjectiveKey(player.getUniqueId(), q.id(), objectiveIndex));
    }

    public int getObjectiveProgressForDisplay(Player player, QuestDefinition q, int objectiveIndex) {
        if (q.type() == QuestType.GUILD) {
            String guildId = guildService.getGuildOf(player.getUniqueId());
            if (guildId == null) return 0;
            return progressStore.get(ProgressStore.guildObjectiveKey(guildId, q.id(), objectiveIndex));
        }
        return getPlayerObjectiveProgress(player, q, objectiveIndex);
    }

    public List<String> getActiveDailyQuests() {
        return List.copyOf(activeDailyQuests);
    }

    public void shutdown() {
        // Placeholder for persistence.
    }
}
