package com.acme.sre.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "alerts")
public class Alert extends PanacheEntityBase {

    @Id
    public String id;
    public String source;
    public String service;
    public String metric;
    public BigDecimal value;
    public BigDecimal threshold;
    public String message;
    public Instant timestamp;

    public Alert() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
    }

    public static Alert of(String source, String service, String metric, BigDecimal value, BigDecimal threshold, String message) {
        Alert alert = new Alert();
        alert.source = source;
        alert.service = service;
        alert.metric = metric;
        alert.value = value;
        alert.threshold = threshold;
        alert.message = message;
        return alert;
    }

    public String toPromptText() {
        return String.format(
                "Alert from %s: Service '%s' - %s at %s (threshold: %s). Message: %s",
                source, service, metric, value, threshold, message);
    }
}
