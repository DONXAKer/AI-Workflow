package com.workflow.llm.provider;

import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for FIX-LLM-002: the tool-use retry predicate must recognise a netty
 * {@link ReadTimeoutException} — including when it is nested in the cause chain and
 * the outer exception's {@code getMessage()} is {@code null}. Run {@code befa764e}
 * crashed the loop at iteration 13 ("completeWithTools iteration 13 failed: null")
 * because the old string-only predicate missed exactly this shape.
 */
class OpenAICompatibleProviderClientTest {

    @Test
    void readTimeout_isRetriable_directly() {
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(ReadTimeoutException.INSTANCE));
    }

    @Test
    void readTimeout_isRetriable_whenNestedAndOuterMessageIsNull() {
        // Mirrors the real failure: the netty ReadTimeoutException is the cause and
        // the outer exception has no message — the old getMessage()-only check missed it.
        Throwable nested = new RuntimeException((String) null, ReadTimeoutException.INSTANCE);
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(nested));
    }

    @Test
    void socketTimeout_isRetriable() {
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(new SocketTimeoutException()));
    }

    @Test
    void connectionDropSignatures_stillRetriable() {
        // Pre-existing string-based cases must keep working.
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(
            new RuntimeException("reactor.netty.http.client.PrematureCloseException: Connection prematurely closed")));
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(
            new RuntimeException("Connection reset by peer")));
    }

    @Test
    void nonTransientError_isNotRetriable() {
        assertFalse(OpenAICompatibleProviderClient.isRetriableToolUseError(
            new IllegalArgumentException("malformed JSON in response body")));
    }

    @Test
    void nullMessageWithoutTimeoutCause_isNotRetriable() {
        assertFalse(OpenAICompatibleProviderClient.isRetriableToolUseError(
            new RuntimeException((String) null)));
    }

    @Test
    void http429_isRetriable_andFlaggedAsRateLimit() {
        // Run 6b2b7e90 died here: AllTokens returned 429 mid tool-use loop.
        WebClientResponseException ex = WebClientResponseException.create(
            429, "Too Many Requests", null, null, null);
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(ex), "429 must be retriable");
        assertTrue(OpenAICompatibleProviderClient.isRateLimitError(ex), "429 must flag as rate-limit (long backoff)");
    }

    @Test
    void http503_isRetriable_butNotRateLimit() {
        WebClientResponseException ex = WebClientResponseException.create(
            503, "Service Unavailable", null, null, null);
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(ex), "503 is a transient upstream error");
        assertFalse(OpenAICompatibleProviderClient.isRateLimitError(ex), "503 is not a rate-limit");
    }

    @Test
    void http400_isNotRetriable() {
        WebClientResponseException ex = WebClientResponseException.create(
            400, "Bad Request", null, null, null);
        assertFalse(OpenAICompatibleProviderClient.isRetriableToolUseError(ex), "client errors must not retry");
    }

    @Test
    void prematureClose_isRetriable_caseInsensitive() {
        // Run a8e9dd1f died here: the message has a capital "Connection" and no literal
        // "PrematureCloseException" — the old case-sensitive predicate missed it.
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(
            new RuntimeException("Connection prematurely closed BEFORE response")));
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(
            new RuntimeException("Connection prematurely closed DURING response")));
    }

    @Test
    void webClientRequestException_isAlwaysRetriable() {
        // Any transport-level failure (request never completed) must retry.
        WebClientRequestException ex = new WebClientRequestException(
            new IOException("connection error"), HttpMethod.POST,
            URI.create("https://api.alltokens.ru/api/v1/chat/completions"), new HttpHeaders());
        assertTrue(OpenAICompatibleProviderClient.isRetriableToolUseError(ex));
        assertFalse(OpenAICompatibleProviderClient.isRateLimitError(ex), "transport error is not a rate-limit");
    }
}
