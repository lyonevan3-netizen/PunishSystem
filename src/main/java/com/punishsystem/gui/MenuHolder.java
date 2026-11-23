package com.punishsystem.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MenuHolder implements InventoryHolder {
    public enum Type { PUNISH_ROOT, OFFENCES, HISTORY_ROOT, HISTORY_LIST, DETAILS }

    private final Type type;
    private final UUID target;
    private final String targetName;
    private final String category;
    private final String action;
    private final int page;
    private final Map<Integer, Long> punishmentSlots = new HashMap<>();
    private final Map<Integer, String> offenceSlots = new HashMap<>();
    private Inventory inventory;

    public MenuHolder(Type type, UUID target, String targetName, String category, String action, int page) {
        this.type = type;
        this.target = target;
        this.targetName = targetName;
        this.category = category;
        this.action = action;
        this.page = page;
    }

    public Type type() { return type; }
    public UUID target() { return target; }
    public String targetName() { return targetName; }
    public String category() { return category; }
    public String action() { return action; }
    public int page() { return page; }
    public void mapPunishment(int slot, long id) { punishmentSlots.put(slot, id); }
    public Long punishmentAt(int slot) { return punishmentSlots.get(slot); }
    public void mapOffence(int slot, String offence) { offenceSlots.put(slot, offence); }
    public String offenceAt(int slot) { return offenceSlots.get(slot); }
    public void inventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}
