package com.guild.plugin;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public class GuildPlugin extends JavaPlugin {
    private GuildService guildService;
    private MessageConfig messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("message.yml", false);
        messages = new MessageConfig(this);
        messages.load();
        guildService = new GuildService(this, messages);
        guildService.load();

        GuildGui guildGui = new GuildGui(guildService, messages);
        getServer().getPluginManager().registerEvents(guildGui, this);

        GuildApi guildApi = new GuildApiImpl(guildService);
        getServer().getServicesManager().register(GuildApi.class, guildApi, this, ServicePriority.Normal);

        PluginCommand guildCommand = getCommand("guild");
        if (guildCommand == null) {
            getLogger().severe("command /guild registration failed");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        GuildCommand executor = new GuildCommand(guildService, guildGui, messages);
        guildCommand.setExecutor(executor);
        guildCommand.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (guildService != null) guildService.save();
    }
}
