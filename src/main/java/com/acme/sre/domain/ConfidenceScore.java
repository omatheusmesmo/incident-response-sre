package com.acme.sre.domain;

public record ConfidenceScore(
        double score,
        String reasoning) {

    public double value() {
        return score;
    }

    public boolean isSufficient() {
        return score >= 0.8;
    }

    @Override
    public String toString() {
        return String.format("%.2f (%s)", score, reasoning);
    }
}
