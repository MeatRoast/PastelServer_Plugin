package com.memu.servermenu;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

public final class MenuListener implements Listener {
    private final MenuManager menuManager;

    public MenuListener(MenuManager menuManager) {
        this.menuManager = menuManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player)) {
            return;
        }
        menuManager.handleClick((org.bukkit.entity.Player) event.getWhoClicked(), event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        menuManager.clearViewer(event.getPlayer().getUniqueId());
    }
}
