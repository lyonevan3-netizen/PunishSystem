package com.punishsystem.model;

public record PunishmentStep(String action, long durationMillis) {
    public boolean permanent() { return durationMillis < 0; }
}
