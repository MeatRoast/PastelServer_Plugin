package quest.plugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class QuestPlugin extends JavaPlugin {
    private QuestManager questManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("quests.yml", false);

        this.questManager = new QuestManager(this);
        questManager.loadAll();
        questManager.startDailyResetTask();

        getServer().getPluginManager().registerEvents(new QuestListener(questManager), this);
        getServer().getPluginManager().registerEvents(new QuestGuiListener(), this);
        getCommand("quest").setExecutor(new QuestCommand(questManager));
        getCommand("questadmin").setExecutor(new QuestAdminCommand(this, questManager));
        QuestTabCompleter completer = new QuestTabCompleter();
        getCommand("quest").setTabCompleter(completer);
        getCommand("questadmin").setTabCompleter(completer);
        getLogger().info("Quest plugin enabled.");
    }

    @Override
    public void onDisable() {
        if (questManager != null) {
            questManager.shutdown();
        }
    }
}
