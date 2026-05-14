package com.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.EscalationConfig;
import com.workflow.config.EscalationStep;
import com.workflow.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the 3-level resolver: block-level explicit / opt-out wins, then
 * {@link Project#getEscalationDefaultsJson()}, then global {@link EscalationProperties}.
 */
class EscalationResolverTest {

    private EscalationProperties props;
    private EscalationResolver resolver;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        props = new EscalationProperties();
        // EscalationProperties initializes with cloud(openrouter, smart, 2) + human(ui, 86400)
        mapper = new ObjectMapper();
        resolver = new EscalationResolver(props, mapper);
    }

    @Test
    void blockExplicit_overridesEverything() {
        var blockSteps = List.<EscalationStep>of(
                new EscalationStep.Cloud("openrouter", "reasoning", 1)
        );
        var blockCfg = EscalationConfig.explicit(blockSteps);

        var project = new Project();
        project.setEscalationDefaultsJson("[{\"tier\":\"human\",\"timeout\":3600}]");

        List<EscalationStep> resolved = resolver.resolve(blockCfg, project);
        assertEquals(1, resolved.size());
        EscalationStep.Cloud cloud = (EscalationStep.Cloud) resolved.get(0);
        assertEquals("reasoning", cloud.model());
    }

    @Test
    void blockNone_returnsEmptyEvenWithProjectAndGlobalDefaults() {
        var blockCfg = EscalationConfig.none();
        var project = new Project();
        project.setEscalationDefaultsJson("[{\"tier\":\"human\"}]");

        List<EscalationStep> resolved = resolver.resolve(blockCfg, project);
        assertTrue(resolved.isEmpty(), "block.escalation=none must opt out completely");
    }

    @Test
    void blockDefault_fallsBackToProjectJson() {
        var blockCfg = EscalationConfig.defaults();
        var project = new Project();
        project.setSlug("test");
        project.setEscalationDefaultsJson("""
                [
                  {"tier":"cloud","provider":"openrouter","model":"reasoning","max_iterations":1},
                  {"tier":"human","notify":["email"],"timeout":7200}
                ]
                """);

        List<EscalationStep> resolved = resolver.resolve(blockCfg, project);
        assertEquals(2, resolved.size());
        var cloud = (EscalationStep.Cloud) resolved.get(0);
        assertEquals("reasoning", cloud.model());
        assertEquals(1, cloud.maxIterations());
        var human = (EscalationStep.Human) resolved.get(1);
        assertEquals(List.of("email"), human.notifyChannels());
        assertEquals(7200L, human.timeoutSeconds());
    }

    @Test
    void blockDefault_andProjectJsonNull_fallsBackToGlobalDefaults() {
        var blockCfg = EscalationConfig.defaults();
        var project = new Project();
        // escalationDefaultsJson left null

        List<EscalationStep> resolved = resolver.resolve(blockCfg, project);
        assertEquals(2, resolved.size(), "global defaults should be cloud + human");
        assertInstanceOf(EscalationStep.Cloud.class, resolved.get(0));
        assertInstanceOf(EscalationStep.Human.class, resolved.get(1));
    }

    @Test
    void blockDefault_andNullProject_fallsBackToGlobalDefaults() {
        var blockCfg = EscalationConfig.defaults();

        List<EscalationStep> resolved = resolver.resolve(blockCfg, null);
        assertEquals(2, resolved.size());
    }

    @Test
    void invalidProjectJson_logsWarnAndFallsBackToGlobalDefaults() {
        var blockCfg = EscalationConfig.defaults();
        var project = new Project();
        project.setSlug("broken");
        project.setEscalationDefaultsJson("{not valid json at all");

        List<EscalationStep> resolved = resolver.resolve(blockCfg, project);
        // Should not throw — falls back to global defaults silently (with a WARN log).
        assertEquals(2, resolved.size());
    }

    @Test
    void emptyProjectJsonArray_returnsEmptyList() {
        var blockCfg = EscalationConfig.defaults();
        var project = new Project();
        project.setSlug("intentionally-empty");
        project.setEscalationDefaultsJson("[]");

        List<EscalationStep> resolved = resolver.resolve(blockCfg, project);
        assertTrue(resolved.isEmpty(),
                "explicit empty array at project level should be honored (deliberate opt-out)");
    }

    @Test
    void nullBlockEscalation_treatedAsDefault() {
        List<EscalationStep> resolved = resolver.resolve(null, null);
        assertEquals(2, resolved.size(), "null block config should behave like 'default'");
    }

    @Test
    void maxBudgetUsd_returnsConfiguredValue() {
        props.setMaxBudgetUsd(15.50);
        assertEquals(15.50, resolver.maxBudgetUsd(), 0.001);
    }

    @Test
    void globalDefaults_areShapeCloudThenHuman() {
        // Sanity: hard-coded defaults in EscalationProperties.
        List<EscalationStep> resolved = resolver.resolve(EscalationConfig.defaults(), null);
        assertInstanceOf(EscalationStep.Cloud.class, resolved.get(0));
        var cloud = (EscalationStep.Cloud) resolved.get(0);
        assertEquals("openrouter", cloud.provider());
        assertEquals("smart", cloud.model());
        assertEquals(2, cloud.maxIterations());

        assertInstanceOf(EscalationStep.Human.class, resolved.get(1));
        var human = (EscalationStep.Human) resolved.get(1);
        assertEquals(List.of("ui"), human.notifyChannels());
        assertEquals(86_400L, human.timeoutSeconds());
    }
}
