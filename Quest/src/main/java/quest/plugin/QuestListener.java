package quest.plugin;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;

@SuppressWarnings("deprecation")
public final class QuestListener implements Listener {
    private final QuestManager questManager;

    public QuestListener(QuestManager questManager) {
        this.questManager = questManager;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        questManager.onMobKill(killer, e.getEntityType());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Material mat = e.getBlock().getType();
        questManager.onBlockMine(e.getPlayer(), mat);
        questManager.addGuildProgress(e.getPlayer(), mat, 1);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        questManager.onItemCollect(
                e.getPlayer(),
                e.getItem().getItemStack().getType(),
                e.getItem().getItemStack().getAmount()
        );
    }
}

