package com.acme.sre.domain.diagnosis;

public record ConfidenceScore(
        double score,
        String reasoning) {

    private static final java.util.regex.Pattern SCORE_PATTERN = java.util.regex.Pattern.compile("(\\d+\\.?\\d*)");

    public static ConfidenceScore parse(String score) {
        if (score == null || score.isBlank()) {
            return new ConfidenceScore(0.5, "No score provided");
        }
        try {
            return new ConfidenceScore(Double.parseDouble(score.trim()), "");
        } catch (NumberFormatException e) {
            var matcher = SCORE_PATTERN.matcher(score);
            if (matcher.find()) {
                return new ConfidenceScore(Double.parseDouble(matcher.group(1)), score.trim());
            }
            return new ConfidenceScore(0.5, score.trim());
        }
    }

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
