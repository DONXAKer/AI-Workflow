package com.workflow.billing;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Stripe-backed {@link PaymentProvider}: hosted Checkout for wallet top-ups, signed
 * webhooks for completion. Credentials come from {@code workflow.billing.stripe.*} — both
 * blank in dev, in which case checkout/webhook fail loudly with a clear message.
 *
 * <p>The API key is passed per-request via {@link RequestOptions} (no global mutation, so
 * it is thread-safe). The account id rides along as {@code client_reference_id} so the
 * webhook can credit the right wallet.
 */
@Component
public class StripePaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentProvider.class);
    private static final String EVENT_CHECKOUT_COMPLETED = "checkout.session.completed";

    private final BillingProperties props;

    public StripePaymentProvider(BillingProperties props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "stripe";
    }

    @Override
    public CheckoutSession createCheckout(Long accountId, BigDecimal amountUsd,
                                          String successUrl, String cancelUrl) {
        String key = props.getStripe().getSecretKey();
        if (key.isBlank()) {
            throw new PaymentException("Stripe is not configured (workflow.billing.stripe.secret-key)");
        }
        long amountCents = amountUsd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (amountCents <= 0) {
            throw new PaymentException("Top-up amount must be positive");
        }

        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(successUrl)
            .setCancelUrl(cancelUrl)
            .setClientReferenceId(String.valueOf(accountId))
            .putMetadata("accountId", String.valueOf(accountId))
            .addLineItem(SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                    .setCurrency("usd")
                    .setUnitAmount(amountCents)
                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                        .setName("AI-Workflow wallet top-up")
                        .build())
                    .build())
                .build())
            .build();

        try {
            RequestOptions options = RequestOptions.builder().setApiKey(key).build();
            Session session = Session.create(params, options);
            return new CheckoutSession(session.getUrl(), session.getId());
        } catch (StripeException e) {
            throw new PaymentException("Stripe checkout creation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentEvent parseWebhook(String payload, String signature) {
        String secret = props.getStripe().getWebhookSecret();
        if (secret.isBlank()) {
            throw new PaymentException("Stripe webhook secret is not configured");
        }
        if (signature == null || signature.isBlank()) {
            throw new PaymentException("Missing Stripe-Signature header");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, secret);
        } catch (SignatureVerificationException e) {
            throw new PaymentException("Invalid Stripe webhook signature", e);
        }

        if (!EVENT_CHECKOUT_COMPLETED.equals(event.getType())) {
            return null;   // not a completed checkout — acknowledged and ignored
        }

        Optional<StripeObject> obj = event.getDataObjectDeserializer().getObject();
        if (obj.isEmpty() || !(obj.get() instanceof Session session)) {
            throw new PaymentException("Stripe webhook payload missing the checkout session "
                + "(verify the Stripe API version matches the SDK)");
        }
        if (!"paid".equals(session.getPaymentStatus())) {
            log.info("Stripe checkout completed with payment_status={} — ignoring",
                session.getPaymentStatus());
            return null;
        }

        Long accountId = parseAccountId(session);
        if (accountId == null) {
            throw new PaymentException("Stripe session carries no resolvable accountId");
        }
        Long amountTotal = session.getAmountTotal();
        BigDecimal amountUsd = amountTotal != null
            ? BigDecimal.valueOf(amountTotal).movePointLeft(2)
            : BigDecimal.ZERO;
        return new PaymentEvent(session.getId(), accountId, amountUsd);
    }

    private static Long parseAccountId(Session session) {
        String ref = session.getClientReferenceId();
        if (ref == null && session.getMetadata() != null) {
            ref = session.getMetadata().get("accountId");
        }
        try {
            return ref != null ? Long.valueOf(ref) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
