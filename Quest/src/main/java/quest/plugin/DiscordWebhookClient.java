package quest.plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.bukkit.plugin.java.JavaPlugin;

public final class DiscordWebhookClient {
    private final JavaPlugin plugin;
    private final String endpoint;
    private final String token;

    public DiscordWebhookClient(JavaPlugin plugin, String endpoint, String token) {
        this.plugin = plugin;
        this.endpoint = endpoint;
        this.token = token;
    }

    public void sendQuestClear(String target, String questTitle, int rewardCoin) {
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                post("""
                        {"target":"%s","quest":"%s","coin":%d}
                        """.formatted(escape(target), escape(questTitle), rewardCoin).trim());
            } catch (Exception e) {
                plugin.getLogger().warning("Discord API call failed: " + e.getMessage());
            }
        });
    }

    private void post(String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (token != null && !token.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
        int code = conn.getResponseCode();
        if (code >= 300) {
            throw new IOException("HTTP " + code);
        }
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
