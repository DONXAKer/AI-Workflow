package com.workflow.billing;

/**
 * Thrown when a payment operation fails — checkout creation, or an invalid / malformed
 * webhook. Controllers map this to a 4xx so the failure is explicit rather than a 500.
 */
public class PaymentException extends RuntimeException {

    public PaymentException(String message) {
        super(message);
    }

    public PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
