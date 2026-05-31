package com.acme.sre.domain;

public enum IncidentStatus {
    TRIAGED,
    DIAGNOSING,
    PENDING_APPROVAL,
    REMEDIATING,
    RESOLVED,
    ESCALATED
}
