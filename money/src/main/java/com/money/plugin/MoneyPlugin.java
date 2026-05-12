package com.money.plugin;

import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MoneyPlugin extends JavaPlugin {
    private EconomyService economyService;
    private MessageService messageService;
    private final Map<String, PluginCommand> registeredCommands = new HashMap<>();
    private volatile boolean databaseAvailable = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messageService = new MessageService(this);
        this.messageService.reload();

        try {
            this.economyService = new EconomyService(this);
            this.economyService.init();
            this.databaseAvailable = true;
        } catch (Exception e) {
            getLogger().severe(messages().format("messages.db-connect-failed", Map.of("reason", e.getMessage())));
            this.databaseAvailable = false;
        }

        try {
            registerConfiguredCommands();
        } catch (Exception e) {
            getLogger().severe(messages().format("messages.command-register-failed", Map.of("reason", e.getMessage())));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new ChequeListener(this, economyService), this);
        getLogger().info("Money plugin enabled.");
    }

    @Override
    public void onDisable() {
        unregisterConfiguredCommands();
        if (economyService != null) {
            economyService.shutdown();
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        messageService.reload();
        if (economyService != null) {
            economyService.reload();
            databaseAvailable = economyService.isReady();
        }
        unregisterConfiguredCommands();
        try {
            registerConfiguredCommands();
        } catch (Exception e) {
            getLogger().severe(messages().format("messages.command-reregister-failed", Map.of("reason", e.getMessage())));
        }
    }

    public MessageService messages() {
        return messageService;
    }

    public EconomyService economyService() {
        return economyService;
    }

    public boolean isDatabaseAvailable() {
        return databaseAvailable;
    }

    private void registerConfiguredCommands() throws Exception {
        MoneyCommand moneyCommand = new MoneyCommand(this, economyService);
        registerDynamicCommand(
                getConfig().getString("command.name", "money"),
                getConfig().getStringList("command.aliases"),
                "Money command",
                moneyCommand,
                moneyCommand
        );
        ChequeCommand chequeCommand = new ChequeCommand(this, economyService);
        registerDynamicCommand(
                getConfig().getString("cheque.command.name", "수표"),
                getConfig().getStringList("cheque.command.aliases"),
                "Cheque command",
                chequeCommand,
                null
        );
    }

    private void registerDynamicCommand(String rawName,
                                        List<String> rawAliases,
                                        String description,
                                        org.bukkit.command.CommandExecutor executor,
                                        org.bukkit.command.TabCompleter completer) throws Exception {
        String commandName = rawName == null ? "" : rawName.trim().toLowerCase();
        if (commandName.isEmpty()) {
            return;
        }
        List<String> aliases = new ArrayList<>(rawAliases);
        aliases.removeIf(String::isBlank);
        CommandMap commandMap = getCommandMap();
        Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, org.bukkit.plugin.Plugin.class);
        constructor.setAccessible(true);
        PluginCommand pluginCommand = constructor.newInstance(commandName, this);
        pluginCommand.setAliases(aliases);
        pluginCommand.setDescription(description);
        pluginCommand.setExecutor(executor);
        if (completer != null) {
            pluginCommand.setTabCompleter(completer);
        }
        commandMap.register(getDescription().getName().toLowerCase(), pluginCommand);
        registeredCommands.put(commandName, pluginCommand);
    }

    private void unregisterConfiguredCommands() {
        if (registeredCommands.isEmpty()) {
            return;
        }
        try {
            CommandMap commandMap = getCommandMap();
            Field knownCommandsField = commandMap.getClass().getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, org.bukkit.command.Command> knownCommands =
                    (Map<String, org.bukkit.command.Command>) knownCommandsField.get(commandMap);
            for (PluginCommand registeredCommand : registeredCommands.values()) {
                List<String> labels = new ArrayList<>();
                labels.add(registeredCommand.getName().toLowerCase());
                for (String alias : registeredCommand.getAliases()) {
                    labels.add(alias.toLowerCase());
                }
                for (String label : labels) {
                    knownCommands.remove(label);
                    knownCommands.remove(getDescription().getName().toLowerCase() + ":" + label);
                }
                registeredCommand.unregister(commandMap);
            }
        } catch (Exception e) {
            getLogger().warning(messages().format("messages.command-unregister-failed", Map.of("reason", e.getMessage())));
        } finally {
            registeredCommands.clear();
        }
    }

    private CommandMap getCommandMap() throws Exception {
        Field field = getServer().getClass().getDeclaredField("commandMap");
        field.setAccessible(true);
        return (CommandMap) field.get(getServer());
    }
}
