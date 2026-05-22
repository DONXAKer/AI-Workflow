package com.workflow.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Plan-tier catalog — bound from {@code workflow.tier.catalog.*}. Each {@link Account}'s
 * {@code tier} string keys into it. Every field has a safe "unset" default so an
 * unconfigured deployment (closed beta) behaves exactly as before:
 *
 * <pre>
 * workflow:
 *   tier:
 *     catalog:
 *       free: { markup: 2.0, max-concurrent-runs: 1, monthly-credit-usd: 0 }
 *       pro:  { markup: 1.5, max-concurrent-runs: 5, monthly-credit-usd: 10 }
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "workflow.tier")
public class TierProperties {

    private Map<String, TierDef> catalog = new HashMap<>();

    public Map<String, TierDef> getCatalog() { return catalog; }
    public void setCatalog(Map<String, TierDef> catalog) {
        this.catalog = catalog != null ? catalog : new HashMap<>();
    }

    /** Definition for a tier name, or shared safe defaults when the tier is not configured. */
    public TierDef forName(String tierName) {
        TierDef d = tierName != null ? catalog.get(tierName) : null;
        return d != null ? d : TierDef.DEFAULTS;
    }

    /** One tier's limits. All fields default to "unset" so behaviour is unchanged until configured. */
    public static class TierDef {
        static final TierDef DEFAULTS = new TierDef();

        /** Wallet markup; {@code <= 0} means "use the global {@code workflow.billing.markup}". */
        private double markup = 0.0;
        /** Max concurrent (RUNNING/PAUSED) runs; {@code <= 0} means unlimited. */
        private int maxConcurrentRuns = 0;
        /** Monthly credit granted to active accounts on this tier; {@code 0} means none. */
        private double monthlyCreditUsd = 0.0;

        public double getMarkup() { return markup; }
        public void setMarkup(double markup) { this.markup = markup; }

        public int getMaxConcurrentRuns() { return maxConcurrentRuns; }
        public void setMaxConcurrentRuns(int maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }

        public double getMonthlyCreditUsd() { return monthlyCreditUsd; }
        public void setMonthlyCreditUsd(double monthlyCreditUsd) { this.monthlyCreditUsd = monthlyCreditUsd; }
    }
}
