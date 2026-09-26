package com.acme.sre.observability;

public record LogEntry(String timestamp, String level, String logger, String message) {

    public String toPromptText() {
        return "%s %s [%s] %s".formatted(timestamp, level, logger, message);
    }
}
