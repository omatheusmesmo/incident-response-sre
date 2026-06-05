package com.acme.sre.domain.model;

public enum IncidentStatus {
    TRIAGED,
    DIAGNOSING,
    PENDING_APPROVAL,
    REMEDIATING,
    RESOLVED,
    ESCALATED
}
