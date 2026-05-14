package com.workflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers YAML deserialization of the {@code escalation} field on
 * {@link OnFailConfig} and {@link OnFailureConfig}. Three legal forms:
 * absent / "none" / "default" / explicit array.
 */
class EscalationConfigParsingTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void absentEscalation_yieldsDefaultPolicy() throws Exception {
        String src = """
                action: loopback
                target: codegen
                max_iterations: 2
                """;
        OnFailConfig cfg = yaml.readValue(src, OnFailConfig.class);
        assertNotNull(cfg.getEscalation());
        assertEquals(EscalationConfig.Policy.DEFAULT, cfg.getEscalation().policy());
        assertTrue(cfg.getEscalation().steps().isEmpty());
    }

    @Test
    void escalationNoneSentinel_yieldsNonePolicy() throws Exception {
        String src = """
                action: loopback
                target: codegen
                escalation: none
                """;
        OnFailConfig cfg = yaml.readValue(src, OnFailConfig.class);
        assertEquals(EscalationConfig.Policy.NONE, cfg.getEscalation().policy());
    }

    @Test
    void escalationDefaultSentinel_yieldsDefaultPolicy() throws Exception {
        String src = """
                action: loopback
                target: codegen
                escalation: default
                """;
        OnFailConfig cfg = yaml.readValue(src, OnFailConfig.class);
        assertEquals(EscalationConfig.Policy.DEFAULT, cfg.getEscalation().policy());
    }

    @Test
    void escalationFalseBoolean_yieldsNonePolicy() throws Exception {
        // YAML `escalation: false` (and `off`/`no` which YAML 1.1 maps to bool false)
        // are interpreted as opt-out for operator convenience.
        OnFailConfig falseExplicit = yaml.readValue("action: fail\nescalation: false\n", OnFailConfig.class);
        OnFailConfig offAlias = yaml.readValue("action: fail\nescalation: off\n", OnFailConfig.class);
        assertEquals(EscalationConfig.Policy.NONE, falseExplicit.getEscalation().policy());
        assertEquals(EscalationConfig.Policy.NONE, offAlias.getEscalation().policy());
    }

    @Test
    void escalationTrueBoolean_yieldsDefaultPolicy() throws Exception {
        OnFailConfig trueExplicit = yaml.readValue("action: fail\nescalation: true\n", OnFailConfig.class);
        assertEquals(EscalationConfig.Policy.DEFAULT, trueExplicit.getEscalation().policy());
    }

    @Test
    void explicitArrayWithCloudAndHuman_parsesBothSteps() throws Exception {
        String src = """
                action: loopback
                target: codegen
                max_iterations: 2
                escalation:
                  - tier: cloud
                    provider: openrouter
                    model: smart
                    max_iterations: 2
                  - tier: human
                    notify: [email, ui]
                    timeout: 3600
                """;
        OnFailConfig cfg = yaml.readValue(src, OnFailConfig.class);
        assertEquals(EscalationConfig.Policy.EXPLICIT, cfg.getEscalation().policy());
        assertEquals(2, cfg.getEscalation().steps().size());

        EscalationStep first = cfg.getEscalation().steps().get(0);
        assertInstanceOf(EscalationStep.Cloud.class, first);
        EscalationStep.Cloud cloud = (EscalationStep.Cloud) first;
        assertEquals("openrouter", cloud.provider());
        assertEquals("smart", cloud.model());
        assertEquals(2, cloud.maxIterations());

        EscalationStep second = cfg.getEscalation().steps().get(1);
        assertInstanceOf(EscalationStep.Human.class, second);
        EscalationStep.Human human = (EscalationStep.Human) second;
        assertEquals(2, human.notifyChannels().size());
        assertEquals(3600L, human.timeoutSeconds());
    }

    @Test
    void cloudStepWithDefaults_appliesFallbacks() throws Exception {
        String src = """
                escalation:
                  - tier: cloud
                """;
        OnFailConfig cfg = yaml.readValue(src, OnFailConfig.class);
        EscalationStep.Cloud cloud = (EscalationStep.Cloud) cfg.getEscalation().steps().get(0);
        assertEquals("openrouter", cloud.provider());
        assertEquals("smart", cloud.model());
        assertEquals(2, cloud.maxIterations());
    }

    @Test
    void humanStepWithDefaults_appliesUiAnd24h() throws Exception {
        String src = """
                escalation:
                  - tier: human
                """;
        OnFailConfig cfg = yaml.readValue(src, OnFailConfig.class);
        EscalationStep.Human human = (EscalationStep.Human) cfg.getEscalation().steps().get(0);
        assertEquals(java.util.List.of("ui"), human.notifyChannels());
        assertEquals(86_400L, human.timeoutSeconds());
    }

    @Test
    void invalidSentinelString_throws() {
        String src = "escalation: maybe\n";
        assertThrows(Exception.class, () -> yaml.readValue(src, OnFailConfig.class));
    }

    @Test
    void emptyArray_parsesAsExplicitWithZeroSteps() throws Exception {
        String src = "escalation: []\n";
        OnFailConfig cfg = yaml.readValue(src, OnFailConfig.class);
        assertEquals(EscalationConfig.Policy.EXPLICIT, cfg.getEscalation().policy());
        assertEquals(0, cfg.getEscalation().steps().size());
    }

    @Test
    void onFailureConfigAlsoSupportsEscalation() throws Exception {
        String src = """
                action: loopback
                target: codegen
                max_iterations: 2
                failed_statuses: [failure]
                escalation:
                  - tier: cloud
                    model: reasoning
                """;
        OnFailureConfig cfg = yaml.readValue(src, OnFailureConfig.class);
        assertEquals(EscalationConfig.Policy.EXPLICIT, cfg.getEscalation().policy());
        EscalationStep.Cloud cloud = (EscalationStep.Cloud) cfg.getEscalation().steps().get(0);
        assertEquals("reasoning", cloud.model());
    }

    @Test
    void setterNull_revertsToDefaults() {
        OnFailConfig cfg = new OnFailConfig();
        cfg.setEscalation(null);
        assertEquals(EscalationConfig.Policy.DEFAULT, cfg.getEscalation().policy());
    }
}
