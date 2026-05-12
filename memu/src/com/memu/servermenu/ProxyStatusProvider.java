package com.memu.servermenu;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ProxyStatusProvider implements PluginMessageListener {
    private final Main plugin;
    private final Map<String, Integer> playerCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> lastResponseAt = new ConcurrentHashMap<>();
    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private final Map<String, Boolean> tcpAlive = new ConcurrentHashMap<>();
    private final List<String> targets = new ArrayList<>();
    private int taskId = -1;
    private int tcpTaskId = -1;
    private long refreshTicks = 40L;
    private long onlineTimeoutMs = 12000L;
    private boolean tcpCheckEnabled = true;
    private int tcpTimeoutMs = 1200;

    public ProxyStatusProvider(Main plugin) {
        this.plugin = plugin;
    }

    public void reload(List<String> serverIds) {
        targets.clear();
        for (String id : serverIds) {
            if (id != null && !id.isEmpty() && !"filler".equalsIgnoreCase(id)) {
                targets.add(id);
            }
        }
        refreshTicks = Math.max(20L, plugin.getConfig().getLong("proxy.refresh_ticks", 40L));
        onlineTimeoutMs = Math.max(3000L, plugin.getConfig().getLong("proxy.online_timeout_ms", 12000L));
        tcpCheckEnabled = plugin.getConfig().getBoolean("proxy.tcp_check.enabled", true);
        tcpTimeoutMs = Math.max(200, plugin.getConfig().getInt("proxy.tcp_check.timeout_ms", 1200));
        loadEndpoints(serverIds);

        stopTask();
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::requestAll, refreshTicks, refreshTicks);
        if (tcpCheckEnabled) {
            tcpTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                    plugin,
                    () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, this::checkAllTcp),
                    20L,
                    20L
            );
        }
    }

    public void requestAll() {
        Player player = firstOnlinePlayer();
        if (player == null || targets.isEmpty()) {
            return;
        }
        for (String server : targets) {
            sendPlayerCountRequest(player, server);
        }
    }

    public Map<String, String> placeholdersFor(String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return Collections.emptyMap();
        }
        int count = playerCounts.getOrDefault(serverId, 0);
        boolean online = isOnline(serverId);
        Map<String, String> map = new ConcurrentHashMap<>();
        map.put("%bungee_" + serverId + "%", String.valueOf(count));
        map.put("%server_online_" + serverId + "%", String.valueOf(count));
        map.put("%server_status_" + serverId + "%", online ? "온라인" : "오프라인");
        map.put("%server_status%", online ? "온라인" : "오프라인");
        map.put("%server_online%", String.valueOf(count));
        return map;
    }

    public Map<String, String> allPlaceholders() {
        if (targets.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new ConcurrentHashMap<>();
        int total = 0;
        for (String server : targets) {
            int count = playerCounts.getOrDefault(server, 0);
            total += Math.max(0, count);
            boolean online = isOnline(server);
            out.put("%bungee_" + server + "%", String.valueOf(count));
            out.put("%server_online_" + server + "%", String.valueOf(count));
            out.put("%server_status_" + server + "%", online ? "온라인" : "오프라인");
        }
        out.put("%bungee_total%", String.valueOf(total));
        return out;
    }

    public void shutdown() {
        stopTask();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!"BungeeCord".equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String sub = in.readUTF();
            if (!"PlayerCount".equals(sub)) {
                return;
            }
            String server = in.readUTF();
            int count = in.readInt();
            playerCounts.put(server, count);
            lastResponseAt.put(server, System.currentTimeMillis());
        } catch (Exception ignored) {
        }
    }

    private void sendPlayerCountRequest(Player player, String server) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("PlayerCount");
            out.writeUTF(server);
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
        } catch (Exception ignored) {
        }
    }

    private Player firstOnlinePlayer() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            return p;
        }
        return null;
    }

    private boolean isOnline(String server) {
        Long ts = lastResponseAt.get(server);
        boolean proxyResponding = ts != null && (System.currentTimeMillis() - ts) <= onlineTimeoutMs;
        if (!proxyResponding) {
            return false;
        }
        if (!tcpCheckEnabled) {
            return true;
        }
        return tcpAlive.getOrDefault(server, false);
    }

    private void stopTask() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        if (tcpTaskId != -1) {
            Bukkit.getScheduler().cancelTask(tcpTaskId);
            tcpTaskId = -1;
        }
    }

    private void loadEndpoints(List<String> serverIds) {
        endpoints.clear();
        Map<String, Endpoint> loaded = new HashMap<>();
        for (String id : serverIds) {
            String base = "status_targets." + id;
            String host = plugin.getConfig().getString(base + ".host");
            int port = plugin.getConfig().getInt(base + ".port", -1);
            if (host != null && !host.isEmpty() && port > 0) {
                loaded.put(id, new Endpoint(host, port));
            }
        }
        endpoints.putAll(loaded);
    }

    private void checkAllTcp() {
        for (Map.Entry<String, Endpoint> entry : endpoints.entrySet()) {
            String server = entry.getKey();
            Endpoint endpoint = entry.getValue();
            tcpAlive.put(server, canConnect(endpoint.host, endpoint.port, tcpTimeoutMs));
        }
    }

    private boolean canConnect(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class Endpoint {
        private final String host;
        private final int port;

        private Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
