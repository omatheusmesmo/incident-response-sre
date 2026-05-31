package com.acme.sre.domain;

public enum Severity {
    P1_CRITICAL,
    P2_HIGH,
    P3_MEDIUM,
    P4_LOW;

    public boolean requiresImmediateAction() {
        return this == P1_CRITICAL || this == P2_HIGH;
    }

    public String toPromptLabel() {
        return switch (this) {
            case P1_CRITICAL -> "P1 - Critical (uptime at risk)";
            case P2_HIGH -> "P2 - High (degraded service)";
            case P3_MEDIUM -> "P3 - Medium (minor impact)";
            case P4_LOW -> "P4 - Low (informational)";
        };
    }
}
