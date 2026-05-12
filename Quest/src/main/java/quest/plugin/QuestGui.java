package quest.plugin;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class QuestGui implements InventoryHolder {
    public static final String TITLE = "§6§l퀘스트 보드";
    private final Inventory inventory;
    private final QuestManager manager;
    private final Player viewer;

    public QuestGui(QuestManager manager, Player viewer) {
        this.manager = manager;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 54, TITLE);
        render();
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    private void render() {
        fillBackground();

        inventory.setItem(10, simple(Material.CLOCK, "§e§l일일퀘스트"));
        inventory.setItem(28, simple(Material.BOOK, "§b§l일반퀘스트"));

        int dailySlot = 11;
        for (String dailyId : manager.getActiveDailyQuests()) {
            QuestDefinition q = manager.getQuest(dailyId);
            if (q == null) continue;
            inventory.setItem(dailySlot++, buildQuestItemSingleLine(q, Material.PAPER, "§e"));
            if (dailySlot > 16) break;
        }

        int normalSlot = 29;
        for (QuestDefinition q : manager.getQuests()) {
            if (q.type() != QuestType.NORMAL) continue;
            inventory.setItem(normalSlot++, buildQuestItemSingleLine(q, Material.PAPER, "§b"));
            if (normalSlot > 34) break;
        }

        inventory.setItem(49, simple(Material.GOLD_INGOT, "§6보상: 코인"));
    }

    private void fillBackground() {
        ItemStack pane = simple(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, pane);
        }
    }

    private ItemStack buildQuestItemSingleLine(QuestDefinition q, Material icon, String color) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        int totalNow = 0;
        int totalNeed = 0;
        for (int i = 0; i < q.objectives().size(); i++) {
            QuestObjective o = q.objectives().get(i);
            int progress = manager.getObjectiveProgressForDisplay(viewer, q, i);
            totalNow += Math.min(progress, o.amount());
            totalNeed += o.amount();
        }
        meta.setDisplayName(color + q.title() + " §7[" + totalNow + "/" + totalNeed + "]");
        List<String> lore = new ArrayList<>();
        lore.add("§7보상: §e+" + q.rewardCoin() + " coin");
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack simple(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
