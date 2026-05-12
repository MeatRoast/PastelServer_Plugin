package com.memu.servermenu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MenuManager {
    private final Main plugin;
    private final SqlDbStatusProvider sqlDbStatusProvider;
    private final ProxyStatusProvider proxyStatusProvider;
    private final Map<UUID, String> openMenuTitle = new HashMap<>();
    private final PlaceholderResolver placeholders;

    private String title;
    private int size;
    private List<MenuItem> items;
    private Map<Integer, MenuItem> slotMapping;

    public MenuManager(Main plugin) {
        this.plugin = plugin;
        this.sqlDbStatusProvider = new SqlDbStatusProvider(plugin);
        this.proxyStatusProvider = new ProxyStatusProvider(plugin);
        this.placeholders = new PlaceholderResolver(plugin);
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        String modernTitle = plugin.getConfig().getString("menu_title");
        Integer modernSize = plugin.getConfig().contains("size") ? plugin.getConfig().getInt("size") : null;

        // legacy fallback: menu.title / menu.size / menu.servers
        this.title = color(modernTitle != null ? modernTitle : plugin.getConfig().getString("menu.title", "&8서버 선택"));
        this.size = normalizeSize(modernSize != null ? modernSize : plugin.getConfig().getInt("menu.size", 27));

        ConfigurationSection modernItems = plugin.getConfig().getConfigurationSection("items");
        if (modernItems != null) {
            this.items = readItems(modernItems);
        } else {
            this.items = readLegacyItems(plugin.getConfig().getConfigurationSection("menu.servers"));
        }
        this.slotMapping = buildSlotMap(items, size);
        List<String> ids = extractServerIds(items);
        this.sqlDbStatusProvider.reload(ids);
        this.proxyStatusProvider.reload(ids);
    }

    public void openMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, size, title);
        for (Map.Entry<Integer, MenuItem> entry : slotMapping.entrySet()) {
            inventory.setItem(entry.getKey(), createItem(player, entry.getValue()));
        }
        player.openInventory(inventory);
        openMenuTitle.put(player.getUniqueId(), title);
    }

    public void clearViewer(UUID uuid) {
        openMenuTitle.remove(uuid);
    }

    public void handleClick(Player player, InventoryClickEvent event) {
        String opened = openMenuTitle.get(player.getUniqueId());
        if (opened == null || !opened.equals(event.getView().getTitle())) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= size) {
            return;
        }
        MenuItem item = slotMapping.get(slot);
        if (item == null) {
            return;
        }
        executeActions(player, item.commands, item.id);
    }

    private ItemStack createItem(Player player, MenuItem item) {
        ItemStack stack = new ItemStack(item.material, Math.max(1, item.amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        Map<String, String> context = new HashMap<>();
        context.putAll(sqlDbStatusProvider.allPlaceholders());
        context.putAll(sqlDbStatusProvider.placeholdersFor(item.id));
        // Proxy(=Velocity) 값을 우선 적용
        context.putAll(proxyStatusProvider.allPlaceholders());
        context.putAll(proxyStatusProvider.placeholdersFor(item.id));
        meta.setDisplayName(placeholders.format(player, item.displayName, context));
        List<String> lore = new ArrayList<>();
        for (String line : item.lore) {
            lore.add(placeholders.format(player, line, context));
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private void executeActions(Player player, List<String> actions, String fallbackServerId) {
        if (actions.isEmpty()) {
            connectBungee(player, fallbackServerId);
            return;
        }

        for (String raw : actions) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            String upper = line.toUpperCase();
            if (upper.startsWith("[CONNECT]")) {
                connectBungee(player, line.substring(9).trim());
                continue;
            }
            if (upper.startsWith("[BUNGEE]")) {
                connectBungee(player, line.substring(8).trim());
                continue;
            }
            if (upper.startsWith("[MESSAGE]")) {
                player.sendMessage(color(line.substring(9).trim()));
                continue;
            }
            if (upper.startsWith("[PLAYER]")) {
                String command = line.substring(8).trim();
                if (!command.isEmpty()) {
                    player.performCommand(command.replaceFirst("^/", ""));
                }
                continue;
            }
            if (upper.startsWith("[CONSOLE]")) {
                String command = line.substring(9).trim();
                if (!command.isEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
                }
            }
        }
    }

    private void connectBungee(Player player, String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(serverId);
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
        } catch (Exception ex) {
            plugin.getLogger().warning("Bungee 연결 실패: " + ex.getMessage());
        }
    }

    private List<MenuItem> readItems(ConfigurationSection section) {
        if (section == null) {
            return Collections.emptyList();
        }
        List<MenuItem> list = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection item = section.getConfigurationSection(key);
            if (item == null) {
                continue;
            }

            Material material = Material.matchMaterial(item.getString("material", "STONE"));
            if (material == null) {
                material = Material.STONE;
            }
            List<Integer> slots = parseSlots(item);
            list.add(new MenuItem(
                    key,
                    material,
                    item.getInt("amount", 1),
                    item.getString("display_name", "&f"),
                    item.getStringList("lore"),
                    item.getStringList("left_click_commands"),
                    slots
            ));
        }
        return list;
    }

    private List<MenuItem> readLegacyItems(ConfigurationSection section) {
        if (section == null) {
            return Collections.emptyList();
        }
        List<MenuItem> list = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection server = section.getConfigurationSection(key);
            if (server == null) {
                continue;
            }
            Material material = Material.matchMaterial(server.getString("material", "STONE"));
            if (material == null) {
                material = Material.STONE;
            }
            List<String> actions = server.getStringList("actions");
            if (actions.isEmpty()) {
                actions = Collections.singletonList("[connect] " + key);
            }
            list.add(new MenuItem(
                    key,
                    material,
                    server.getInt("amount", 1),
                    server.getString("name", "&f" + key),
                    server.getStringList("lore"),
                    actions,
                    Collections.singletonList(server.getInt("slot", 0))
            ));
        }
        return list;
    }

    private List<Integer> parseSlots(ConfigurationSection item) {
        if (item.contains("slot")) {
            return Collections.singletonList(item.getInt("slot"));
        }
        List<Integer> out = new ArrayList<>();
        for (String token : item.getStringList("slots")) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            String trimmed = token.trim();
            if (trimmed.contains("-")) {
                String[] parts = trimmed.split("-", 2);
                try {
                    int start = Integer.parseInt(parts[0].trim());
                    int end = Integer.parseInt(parts[1].trim());
                    int min = Math.min(start, end);
                    int max = Math.max(start, end);
                    for (int i = min; i <= max; i++) {
                        out.add(i);
                    }
                } catch (NumberFormatException ignored) {
                }
            } else {
                try {
                    out.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private Map<Integer, MenuItem> buildSlotMap(List<MenuItem> itemList, int inventorySize) {
        Map<Integer, MenuItem> map = new LinkedHashMap<>();
        for (MenuItem item : itemList) {
            for (Integer slot : item.slots) {
                if (slot != null && slot >= 0 && slot < inventorySize) {
                    map.put(slot, item);
                }
            }
        }
        return map;
    }

    private List<String> extractServerIds(List<MenuItem> itemList) {
        List<String> ids = new ArrayList<>();
        for (MenuItem item : itemList) {
            if (!item.id.equalsIgnoreCase("filler")) {
                ids.add(item.id);
            }
        }
        return ids;
    }

    public ProxyStatusProvider getProxyStatusProvider() {
        return proxyStatusProvider;
    }

    private int normalizeSize(int requested) {
        int clamped = Math.max(9, Math.min(54, requested));
        int remainder = clamped % 9;
        return remainder == 0 ? clamped : clamped + (9 - remainder);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private static final class MenuItem {
        private final String id;
        private final Material material;
        private final int amount;
        private final String displayName;
        private final List<String> lore;
        private final List<String> commands;
        private final List<Integer> slots;

        private MenuItem(
                String id,
                Material material,
                int amount,
                String displayName,
                List<String> lore,
                List<String> commands,
                List<Integer> slots
        ) {
            this.id = id;
            this.material = material;
            this.amount = amount;
            this.displayName = displayName;
            this.lore = lore;
            this.commands = commands;
            this.slots = slots;
        }
    }
}
