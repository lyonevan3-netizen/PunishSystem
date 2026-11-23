package com.punishsystem.model;

import java.util.UUID;

public record Punishment(
        long id, UUID playerUuid, String playerName, String category, String offence,
        String action, long durationMillis, long issuedAt, long expiresAt,
        UUID staffUuid, String staffName, String status, String removalReason,
        String removedBy, long removedAt
) {
    public boolean permanent() { return durationMillis < 0; }
    public boolean warning() { return action.equalsIgnoreCase("WARN"); }
    public boolean active() {
        return status.equalsIgnoreCase("ACTIVE")
                && (warning() || permanent() || expiresAt > System.currentTimeMillis());
    }
}
