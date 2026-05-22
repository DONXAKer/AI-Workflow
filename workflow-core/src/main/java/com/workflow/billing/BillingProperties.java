package com.workflow.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Billing configuration — bound from {@code workflow.billing.*} in application.yaml.
 *
 * <pre>
 * workflow:
 *   billing:
 *     markup: 1.7                 # multiplier applied to raw LLM costUsd when debiting
 *     min-run-reserve-usd: 0.10   # a run won't start / continue below this balance
 *     provider: stripe            # active PaymentProvider id
 *     stripe:
 *       secret-key: sk_live_...   # blank disables checkout
 *       webhook-secret: whsec_... # blank disables webhook verification
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "workflow.billing")
public class BillingProperties {

    /** Multiplier applied to the raw LLM {@code costUsd} when debiting the wallet. */
    private double markup = 1.7;

    /** Minimum wallet balance (USD) required to start or continue a run. */
    private double minRunReserveUsd = 0.10;

    /** Active payment provider id (matches {@link PaymentProvider#id()}). */
    private String provider = "stripe";

    private final Stripe stripe = new Stripe();

    public double getMarkup() { return markup > 0 ? markup : 1.0; }
    public void setMarkup(double markup) { this.markup = markup; }

    public double getMinRunReserveUsd() { return Math.max(0.0, minRunReserveUsd); }
    public void setMinRunReserveUsd(double minRunReserveUsd) { this.minRunReserveUsd = minRunReserveUsd; }

    public String getProvider() { return provider != null ? provider : "stripe"; }
    public void setProvider(String provider) { this.provider = provider; }

    public Stripe getStripe() { return stripe; }

    /** Stripe credentials. Both blank in dev — checkout/webhook then fail loudly. */
    public static class Stripe {
        private String secretKey = "";
        private String webhookSecret = "";

        public String getSecretKey() { return secretKey != null ? secretKey : ""; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

        public String getWebhookSecret() { return webhookSecret != null ? webhookSecret : ""; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    }
}
