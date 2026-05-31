package com.acme.sre.domain;

import java.util.Map;

public record RemediationAction(
        ActionType type,
        String description,
        boolean destructive,
        String targetService,
        Map<String, Object> parameters) {

    public static RemediationAction of(ActionType type, String description, String targetService) {
        return new RemediationAction(type, description, type.isDestructive(), targetService, Map.of());
    }

    public static RemediationAction of(ActionType type, String description, String targetService, Map<String, Object> parameters) {
        return new RemediationAction(type, description, type.isDestructive(), targetService, parameters);
    }

    public String toPromptText() {
        return String.format("%s: %s (target: %s, destructive: %s)", type, description, targetService, destructive);
    }
}
