package com.memu.servermenu;

import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
    private MenuManager menuManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        menuManager = new MenuManager(this);
        getServer().getPluginManager().registerEvents(new MenuListener(menuManager), this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", menuManager.getProxyStatusProvider());

        ServerMenuCommand command = new ServerMenuCommand(menuManager);
        if (getCommand("servermenu") != null) {
            getCommand("servermenu").setExecutor(command);
            getCommand("servermenu").setTabCompleter(command);
        }
    }

    public MenuManager getMenuManager() {
        return menuManager;
    }

    @Override
    public void onDisable() {
        if (menuManager != null) {
            menuManager.getProxyStatusProvider().shutdown();
        }
    }
}
