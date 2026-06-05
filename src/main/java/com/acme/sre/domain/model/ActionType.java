package com.acme.sre.domain.model;

public enum ActionType {
    RESTART_POD(true),
    SCALE_DOWN(true),
    SCALE_UP(false),
    FEATURE_FLAG(false),
    NOTIFY(false),
    CREATE_TICKET(false),
    INVESTIGATE_ONLY(false);

    private final boolean destructive;

    ActionType(boolean destructive) {
        this.destructive = destructive;
    }

    public boolean isDestructive() {
        return destructive;
    }
}
