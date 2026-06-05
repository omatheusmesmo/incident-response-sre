package com.acme.sre.domain.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "incidents")
public class Incident extends PanacheEntityBase {

    @Id
    public String id;

    @Column(name = "alert_source")
    public String alertSource;

    @Column(name = "alert_service")
    public String alertService;

    @Column(name = "alert_metric")
    public String alertMetric;

    @Column(name = "alert_message", columnDefinition = "TEXT")
    public String alertMessage;

    @Enumerated(EnumType.STRING)
    public Severity severity;

    @Column(columnDefinition = "TEXT")
    public String diagnosis;

    @Column(name = "root_cause", columnDefinition = "TEXT")
    public String rootCause;

    @Column(name = "remediation_type")
    @Enumerated(EnumType.STRING)
    public ActionType remediationType;

    @Column(name = "remediation_description", columnDefinition = "TEXT")
    public String remediationDescription;

    @Column(name = "remediation_destructive")
    public boolean remediationDestructive;

    @Column(name = "remediation_target")
    public String remediationTargetService;

    @Enumerated(EnumType.STRING)
    public IncidentStatus status;

    @Column(name = "confidence_score")
    public double confidenceScore;

    @Column(name = "post_mortem_summary", columnDefinition = "TEXT")
    public String postMortemSummary;

    @Column(name = "workflow_instance_id")
    public String workflowInstanceId;

    public Incident() {
        this.id = UUID.randomUUID().toString();
    }

    public static Incident fromAlert(Alert alert) {
        Incident incident = new Incident();
        incident.alertSource = alert.source;
        incident.alertService = alert.service;
        incident.alertMetric = alert.metric;
        incident.alertMessage = alert.message;
        incident.status = IncidentStatus.TRIAGED;
        return incident;
    }

    public String toPromptText() {
        return String.format(
                "Incident %s: Service '%s' - %s (from %s). Alert: %s. Severity: %s. Status: %s",
                id, alertService, alertMetric, alertSource, alertMessage, severity, status);
    }
}
