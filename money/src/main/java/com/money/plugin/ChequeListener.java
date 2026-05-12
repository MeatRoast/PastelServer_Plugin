package com.money.plugin;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

public class ChequeListener implements Listener {
    private static final DecimalFormat MONEY_FORMAT;
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        MONEY_FORMAT = new DecimalFormat("#,##0.##", symbols);
    }

    private final MoneyPlugin plugin;
    private final EconomyService economyService;
    private final NamespacedKey key;

    public ChequeListener(MoneyPlugin plugin, EconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.key = new NamespacedKey(plugin, "cheque_amount");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        Double amountValue = meta.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        if (amountValue == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!plugin.isDatabaseAvailable()) {
            player.sendMessage(plugin.messages().get("messages.db-not-connected"));
            plugin.economyService().logAction(
                    player.getUniqueId().toString(),
                    player.getName(),
                    "PLAYER",
                    "CHEQUE_REDEEM_BLOCKED_DB_OFFLINE",
                    player.getUniqueId().toString(),
                    player.getName(),
                    null,
                    "right_click_redeem",
                    false
            );
            return;
        }
        try {
            BigDecimal amount = BigDecimal.valueOf(amountValue).setScale(2, RoundingMode.DOWN);
            if (item.getAmount() <= 1) {
                if (event.getHand() == EquipmentSlot.HAND) {
                    player.getInventory().setItemInMainHand(null);
                } else {
                    player.getInventory().setItemInOffHand(null);
                }
            } else {
                item.setAmount(item.getAmount() - 1);
                if (event.getHand() == EquipmentSlot.HAND) {
                    player.getInventory().setItemInMainHand(item);
                } else {
                    player.getInventory().setItemInOffHand(item);
                }
            }
            economyService.add(player.getUniqueId(), amount);
            player.sendMessage(plugin.messages().format("messages.cheque-redeemed",
                    Map.of("amount", MONEY_FORMAT.format(amount))));
            plugin.economyService().logAction(
                    player.getUniqueId().toString(),
                    player.getName(),
                    "PLAYER",
                    "CHEQUE_REDEEMED",
                    player.getUniqueId().toString(),
                    player.getName(),
                    amount.toPlainString(),
                    "right_click_redeem",
                    true
            );
        } catch (Exception e) {
            player.sendMessage(plugin.messages().format("messages.error-with-reason", Map.of("reason", e.getMessage())));
            plugin.economyService().logAction(
                    player.getUniqueId().toString(),
                    player.getName(),
                    "PLAYER",
                    "CHEQUE_REDEEM_ERROR",
                    player.getUniqueId().toString(),
                    player.getName(),
                    null,
                    e.getMessage(),
                    false
            );
        }
    }
}
