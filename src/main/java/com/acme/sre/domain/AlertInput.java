package com.acme.sre.domain;

import java.math.BigDecimal;

public record AlertInput(
        String source,
        String service,
        String metric,
        BigDecimal value,
        BigDecimal threshold,
        String message) {

    public Alert toAlert() {
        return Alert.of(source, service, metric, value, threshold, message);
    }

    public String toPromptText() {
        return String.format(
                "Alert from %s: Service '%s' - %s at %s (threshold: %s). Message: %s",
                source, service, metric, value, threshold, message);
    }
}
