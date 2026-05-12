package io.github.dohwan.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;

public final class CrossChatPlugin extends JavaPlugin implements Listener, TabExecutor {
    private final PlainTextComponentSerializer plainSerializer = PlainTextComponentSerializer.plainText();
    private final LegacyComponentSerializer legacyAmpersand = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)&?#([0-9a-f]{6})");
    private static final Pattern BRACKET_HEX_PATTERN = Pattern.compile("(?i)<#([0-9a-f]{6})>");
    private static final Pattern LEGACY_COLOR_PATTERN = Pattern.compile("(?i)&[0-9A-FK-ORX]");
    private static final Pattern ANY_HEX_PATTERN = Pattern.compile("(?i)(?:&?#|<#)([0-9a-f]{6})>?");

    private final Map<UUID, ChatMode> playerModes = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNicknames = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> colorChatUnlocked = new ConcurrentHashMap<>();
    private final Map<String, String> messages = new ConcurrentHashMap<>();

    private ChatSyncService chatSyncService;
    private String serverName;
    private int maxContentLength;
    private double localRadius;
    private String globalFormat;
    private String serverFormat;
    private String localFormat;
    private String remoteGlobalFormat;

    private ItemTemplate colorChatTemplate;
    private ItemTemplate nicknameTemplate;
    private ItemTemplate coloredNicknameTemplate;

    private NamespacedKey tokenTypeKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureConfigDefaults();
        loadSettings();
        this.tokenTypeKey = new NamespacedKey(this, "token_type");

        this.chatSyncService = new ChatSyncService(this, serverName);
        if (!chatSyncService.initialize()) {
            getLogger().severe("SQLDB initialization failed.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommand("채팅모드");
        registerCommand("닉네임");
        registerCommand("채팅아이템");

        chatSyncService.startPolling();
    }

    private void ensureConfigDefaults() {
        InputStream in = getResource("config.yml");
        if (in == null) {
            return;
        }
        YamlConfiguration defaultYaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        FileConfiguration cfg = getConfig();
        cfg.setDefaults(defaultYaml);
        cfg.options().copyDefaults(true);
        saveConfig();
    }

    @Override
    public void onDisable() {
        if (chatSyncService != null) {
            chatSyncService.shutdown();
        }
        playerModes.clear();
        playerNicknames.clear();
        colorChatUnlocked.clear();
    }

    private void registerCommand(String name) {
        if (getCommand(name) != null) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
    }

    private void loadSettings() {
        FileConfiguration cfg = getConfig();
        this.serverName = cfg.getString("server-name", "로비");
        this.maxContentLength = Math.max(1, cfg.getInt("chat.max-content-length", 256));
        this.localRadius = Math.max(1.0D, cfg.getDouble("chat.local-radius", 200.0D));
        this.globalFormat = cfg.getString("formats.global-format", "[전체/{server}] [닉네임 {nickname}<{username}>] : {message}");
        this.serverFormat = cfg.getString("formats.server-format", "[서버/{server}] [닉네임 {nickname}<{username}>] : {message}");
        this.localFormat = cfg.getString("formats.local-format", "[지역/{server}] [닉네임 {nickname}<{username}>] : {message}");
        this.remoteGlobalFormat = cfg.getString("formats.remote-global-format", "[전체/{server}] [닉네임 {nickname}<{username}>] : {message}");

        this.colorChatTemplate = readItemTemplate(cfg, "items.color-chat");
        this.nicknameTemplate = readItemTemplate(cfg, "items.nickname-change");
        this.coloredNicknameTemplate = readItemTemplate(cfg, "items.colored-nickname-change");

        this.messages.clear();
        ConfigurationSection section = cfg.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                messages.put(key, section.getString(key, ""));
            }
        }
    }

    private ItemTemplate readItemTemplate(FileConfiguration cfg, String path) {
        String name = cfg.getString(path + ".name", "&f[아이템]");
        List<String> lore = cfg.getStringList(path + ".lore");
        return new ItemTemplate(name, lore);
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            ChatMode dbMode = chatSyncService.loadPlayerMode(uuid);
            if (dbMode != null) {
                playerModes.put(uuid, dbMode);
            }
            String nickname = chatSyncService.loadNickname(uuid);
            if (nickname != null && !nickname.isBlank()) {
                playerNicknames.put(uuid, nickname);
            }
            if (chatSyncService.loadColorChatUnlocked(uuid)) {
                colorChatUnlocked.put(uuid, true);
            }
        });
    }

    @EventHandler
    public void onRightClick(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        TokenType tokenType = readTokenType(item);
        if (tokenType != TokenType.COLOR_CHAT) {
            return;
        }
        event.setCancelled(true);
        UUID uuid = event.getPlayer().getUniqueId();
        if (Boolean.TRUE.equals(colorChatUnlocked.get(uuid))) {
            sendMsg(event.getPlayer(), "color-chat-already-active");
            return;
        }
        consumeOne(event.getPlayer(), item);
        colorChatUnlocked.put(uuid, true);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> chatSyncService.saveColorChatUnlocked(uuid, true));
        sendMsg(event.getPlayer(), "color-chat-armed");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(@NotNull AsyncChatEvent event) {
        Player sender = event.getPlayer();
        UUID uuid = sender.getUniqueId();
        String rawContent = plainSerializer.serialize(event.message()).trim();
        if (rawContent.isEmpty()) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);

        boolean canUseColor = Boolean.TRUE.equals(colorChatUnlocked.get(uuid));
        String storedMessage = canUseColor ? normalizeHexCodes(rawContent) : stripColorCodes(rawContent);
        if (storedMessage.length() > maxContentLength) {
            storedMessage = storedMessage.substring(0, maxContentLength);
        }

        String username = sender.getName();
        String nickname = getNickname(uuid, username);
        ChatMode mode = getMode(uuid);
        String finalStoredMessage = storedMessage;

        Bukkit.getScheduler().runTask(this, () -> {
            switch (mode) {
                case GLOBAL -> {
                    sendGlobalLocal(sender, nickname, finalStoredMessage);
                    chatSyncService.appendChatLog("GLOBAL", username, finalStoredMessage);
                    chatSyncService.publishGlobal(username, nickname, finalStoredMessage);
                }
                case SERVER -> {
                    sendServerLocal(sender, nickname, finalStoredMessage);
                    chatSyncService.appendChatLog("SERVER", username, finalStoredMessage);
                }
                case LOCAL -> {
                    sendLocalRadius(sender, nickname, finalStoredMessage);
                    chatSyncService.appendChatLog("LOCAL", username, finalStoredMessage);
                }
            }
        });
    }

    public void broadcastRemoteGlobal(String sourceServer, String username, String nickname, String content) {
        String rendered = applyFormat(remoteGlobalFormat, sourceServer, username, nickname, content);
        Component message = deserializeColored(rendered);
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (getMode(player.getUniqueId()) == ChatMode.GLOBAL) {
                    player.sendMessage(message);
                }
            }
            sendToConsole(rendered);
        });
    }

    private void sendGlobalLocal(Player sender, String nickname, String content) {
        String rendered = applyFormat(globalFormat, serverName, sender.getName(), nickname, content);
        Component msg = deserializeColored(rendered);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (getMode(player.getUniqueId()) == ChatMode.GLOBAL) {
                player.sendMessage(msg);
            }
        }
        sendToConsole(rendered);
    }

    private void sendServerLocal(Player sender, String nickname, String content) {
        String rendered = applyFormat(serverFormat, serverName, sender.getName(), nickname, content);
        Component msg = deserializeColored(rendered);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (getMode(player.getUniqueId()) == ChatMode.SERVER) {
                player.sendMessage(msg);
            }
        }
        sendToConsole(rendered);
    }

    private void sendLocalRadius(Player sender, String nickname, String content) {
        Location senderLoc = sender.getLocation();
        double radiusSquared = localRadius * localRadius;
        String rendered = applyFormat(localFormat, serverName, sender.getName(), nickname, content);
        Component msg = deserializeColored(rendered);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (getMode(player.getUniqueId()) != ChatMode.LOCAL) {
                continue;
            }
            if (player.getWorld() != sender.getWorld()) {
                continue;
            }
            if (player.getLocation().distanceSquared(senderLoc) <= radiusSquared) {
                player.sendMessage(msg);
            }
        }
        sendToConsole(rendered);
    }

    private ChatMode getMode(UUID uuid) {
        return playerModes.getOrDefault(uuid, ChatMode.GLOBAL);
    }

    private String getNickname(UUID uuid, String username) {
        return playerNicknames.getOrDefault(uuid, username);
    }

    private String applyFormat(String template, String server, String username, String nickname, String message) {
        return template
                .replace("{server}", server)
                .replace("{username}", username)
                .replace("{nickname}", nickname)
                .replace("{message}", message);
    }

    private void sendToConsole(String text) {
        Bukkit.getConsoleSender().sendMessage(plainSerializer.serialize(deserializeColored(text)));
    }

    private void sendMsg(CommandSender sender, String key) {
        String msg = messages.getOrDefault(key, "");
        if (!msg.isBlank()) {
            sender.sendMessage(deserializeColored(msg));
        }
    }

    private void sendMsg(CommandSender sender, String key, Map<String, String> values) {
        String msg = messages.getOrDefault(key, "");
        for (Map.Entry<String, String> e : values.entrySet()) {
            msg = msg.replace("{" + e.getKey() + "}", e.getValue());
        }
        if (!msg.isBlank()) {
            sender.sendMessage(deserializeColored(msg));
        }
    }

    private Component deserializeColored(String input) {
        return legacyAmpersand.deserialize(normalizeHexCodes(input));
    }

    private String normalizeHexCodes(String input) {
        String afterBracketHex = BRACKET_HEX_PATTERN.matcher(input).replaceAll("&#$1");
        Matcher matcher = HEX_PATTERN.matcher(afterBracketHex);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement("&#" + hex));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean containsColorCode(String input) {
        return LEGACY_COLOR_PATTERN.matcher(input).find() || ANY_HEX_PATTERN.matcher(input).find();
    }

    private String stripColorCodes(String input) {
        String noLegacy = LEGACY_COLOR_PATTERN.matcher(input).replaceAll("");
        return ANY_HEX_PATTERN.matcher(noLegacy).replaceAll("");
    }

    private TokenType readTokenType(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String raw = pdc.get(tokenTypeKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return TokenType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void consumeOne(Player player, ItemStack stack) {
        int amount = stack.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        stack.setAmount(amount - 1);
        player.getInventory().setItemInMainHand(stack);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return switch (command.getName()) {
            case "채팅모드" -> handleChatModeCommand(sender, args);
            case "닉네임" -> handleNicknameCommand(sender, args);
            case "채팅아이템" -> handleItemCommand(sender, args);
            default -> false;
        };
    }

    private boolean handleChatModeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMsg(sender, "player-only");
            return true;
        }
        if (args.length != 1) {
            sendMsg(player, "chatmode-usage");
            return true;
        }
        ChatMode mode = ChatMode.fromInput(args[0]);
        if (mode == null) {
            sendMsg(player, "chatmode-invalid");
            return true;
        }
        UUID uuid = player.getUniqueId();
        playerModes.put(uuid, mode);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> chatSyncService.savePlayerMode(uuid, mode));
        sendMsg(player, "chatmode-changed", Map.of("mode", mode.korean));
        return true;
    }

    private boolean handleNicknameCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMsg(sender, "player-only");
            return true;
        }
        if (args.length != 2 || !"변경".equals(args[0])) {
            sendMsg(player, "nickname-usage");
            return true;
        }

        String requested = args[1].trim();
        if (requested.isEmpty() || requested.length() > 16) {
            sendMsg(player, "nickname-length");
            return true;
        }

        boolean hasColored = hasToken(player, TokenType.COLORED_NICKNAME_CHANGE);
        boolean hasNormal = hasToken(player, TokenType.NICKNAME_CHANGE);
        if (!hasColored && !hasNormal) {
            sendMsg(player, "nickname-need-ticket");
            return true;
        }

        String finalNickname;
        if (hasColored) {
            consumeTokenFromInventory(player, TokenType.COLORED_NICKNAME_CHANGE);
            finalNickname = normalizeHexCodes(requested);
        } else {
            if (containsColorCode(requested)) {
                sendMsg(player, "nickname-color-not-allowed");
                return true;
            }
            consumeTokenFromInventory(player, TokenType.NICKNAME_CHANGE);
            finalNickname = stripColorCodes(requested);
        }

        if (finalNickname.isBlank()) {
            sendMsg(player, "nickname-invalid");
            return true;
        }

        UUID uuid = player.getUniqueId();
        playerNicknames.put(uuid, finalNickname);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> chatSyncService.saveNickname(uuid, finalNickname));
        sendMsg(player, "nickname-changed", Map.of("nickname", finalNickname));
        return true;
    }

    private boolean handleItemCommand(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sendMsg(sender, "no-permission");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            sendMsg(sender, "giveitem-usage");
            return true;
        }

        Player target;
        if (args.length == 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sendMsg(sender, "giveitem-console-needs-target");
            return true;
        }
        if (target == null) {
            sendMsg(sender, "giveitem-target-not-found");
            return true;
        }

        TokenType type = TokenType.fromGiveArg(args[0]);
        if (type == null) {
            sendMsg(sender, "giveitem-invalid-type");
            return true;
        }

        target.getInventory().addItem(createTokenItem(type));
        String itemName = switch (type) {
            case COLOR_CHAT -> colorChatTemplate.name;
            case NICKNAME_CHANGE -> nicknameTemplate.name;
            case COLORED_NICKNAME_CHANGE -> coloredNicknameTemplate.name;
        };
        sendMsg(sender, "giveitem-success", Map.of("item", plainSerializer.serialize(deserializeColored(itemName)), "player", target.getName()));
        return true;
    }

    private ItemStack createTokenItem(TokenType type) {
        ItemTemplate template = switch (type) {
            case COLOR_CHAT -> colorChatTemplate;
            case NICKNAME_CHANGE -> nicknameTemplate;
            case COLORED_NICKNAME_CHANGE -> coloredNicknameTemplate;
        };
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(deserializeColored(template.name));
            List<Component> loreComponents = new ArrayList<>();
            for (String line : template.lore) {
                loreComponents.add(deserializeColored(line));
            }
            meta.lore(loreComponents);
            meta.getPersistentDataContainer().set(tokenTypeKey, PersistentDataType.STRING, type.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean hasToken(Player player, TokenType targetType) {
        ItemStack[] contents = player.getInventory().getContents();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            if (readTokenType(stack) == targetType) {
                return true;
            }
        }
        return false;
    }

    private boolean consumeTokenFromInventory(Player player, TokenType targetType) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            if (readTokenType(stack) != targetType) {
                continue;
            }
            int amount = stack.getAmount();
            if (amount <= 1) {
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(amount - 1);
                player.getInventory().setItem(i, stack);
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if ("채팅모드".equals(command.getName())) {
            if (args.length != 1) {
                return List.of();
            }
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (ChatMode mode : ChatMode.values()) {
                if (mode.korean.startsWith(input)) {
                    out.add(mode.korean);
                }
            }
            return out;
        }
        if ("닉네임".equals(command.getName())) {
            if (args.length == 1 && "변경".startsWith(args[0])) {
                return List.of("변경");
            }
            return List.of();
        }
        if ("채팅아이템".equals(command.getName())) {
            if (args.length == 1) {
                return List.of("색채팅", "닉변", "색닉변");
            }
            if (args.length == 2) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            }
            return List.of();
        }
        return List.of();
    }

    public enum ChatMode {
        GLOBAL("전체"),
        SERVER("서버"),
        LOCAL("지역");

        private final String korean;

        ChatMode(String korean) {
            this.korean = korean;
        }

        private static ChatMode fromInput(String input) {
            return switch (input) {
                case "전체" -> GLOBAL;
                case "서버" -> SERVER;
                case "지역" -> LOCAL;
                default -> null;
            };
        }
    }

    private enum TokenType {
        COLOR_CHAT,
        NICKNAME_CHANGE,
        COLORED_NICKNAME_CHANGE;

        private static TokenType fromGiveArg(String input) {
            return switch (input) {
                case "색채팅" -> COLOR_CHAT;
                case "닉변" -> NICKNAME_CHANGE;
                case "색닉변" -> COLORED_NICKNAME_CHANGE;
                default -> null;
            };
        }
    }

    private record ItemTemplate(String name, List<String> lore) {
    }
}
