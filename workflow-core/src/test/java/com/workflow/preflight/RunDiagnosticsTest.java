package com.workflow.preflight;

import com.workflow.blocks.Block;
import com.workflow.config.BlockConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.config.Severity;
import com.workflow.config.ValidationResult;
import com.workflow.core.BlockRegistry;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.IntegrationResolver;
import com.workflow.llm.LlmProvider;
import com.workflow.model.IntegrationType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RunDiagnostics} — requirement gathering (reachability scoping, disabled
 * skip, condition downgrade, dedup) and verdict construction. Hand-written fakes, no Mockito.
 */
class RunDiagnosticsTest {

    private static final String FAKE_BINARY = "definitely-not-a-real-binary-xyz-9000";

    static class FakeResolver extends IntegrationResolver {
        boolean providerConfigured = true;
        FakeResolver() { super(null); }
        @Override public boolean isProviderConfigured(LlmProvider p) { return providerConfigured; }
        @Override public String providerBaseUrl(LlmProvider p) { return "http://x/v1"; }
        @Override public boolean hasToken(String name, IntegrationType type) { return true; }
    }

    static class FakeRegistry extends BlockRegistry {
        final Map<String, Block> map = new HashMap<>();
        @Override public Block get(String type) { return map.get(type); }
    }

    private final FakeResolver resolver = new FakeResolver();
    private final FakeRegistry registry = new FakeRegistry();

    private RunDiagnostics diagnostics(ReachabilityProbe probe) {
        return new RunDiagnostics(registry, new RequirementChecker(resolver, probe), null);
    }

    private RunDiagnostics diagnostics() {
        return diagnostics((url, t) -> new ReachabilityProbe.Result(true, "ok"));
    }

    private Block fakeBlock(String type, List<Requirement> reqs) {
        return new Block() {
            @Override public String getName() { return type; }
            @Override public String getDescription() { return type; }
            @Override public Map<String, Object> run(Map<String, Object> i, BlockConfig c, PipelineRun r) { return Map.of(); }
            @Override public List<Requirement> preflightRequirements(BlockConfig c, PreflightContext ctx) { return reqs; }
        };
    }

    private BlockConfig block(String id, String type, String condition) {
        BlockConfig bc = new BlockConfig();
        bc.setId(id);
        bc.setBlock(type);
        bc.setCondition(condition);
        return bc;
    }

    private PipelineConfig pipeline(BlockConfig... blocks) {
        PipelineConfig cfg = new PipelineConfig();
        cfg.setPipeline(List.of(blocks));
        return cfg;
    }

    private PreflightContext noProviderCtx() {
        return new PreflightContext(null, null, "default", null);
    }

    @Test
    void hardFail_fromUnconditionalBlock_blocksRun() {
        registry.map.put("b1", fakeBlock("b1", List.of(new Requirement.Binary(FAKE_BINARY))));

        ValidationResult result = diagnostics()
                .diagnose(pipeline(block("step1", "b1", null)), null, noProviderCtx());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.code().equals("PREFLIGHT_BINARY_MISSING") && e.severity() == Severity.ERROR);
    }

    @Test
    void hardFail_fromConditionalBlock_downgradedToWarn() {
        registry.map.put("b1", fakeBlock("b1", List.of(new Requirement.Binary(FAKE_BINARY))));

        ValidationResult result = diagnostics()
                .diagnose(pipeline(block("step1", "b1", "$.x.y == true")), null, noProviderCtx());

        assertThat(result.valid()).isTrue(); // WARN does not block
        assertThat(result.errors())
                .anyMatch(e -> e.code().equals("PREFLIGHT_BINARY_MISSING") && e.severity() == Severity.WARN);
    }

    @Test
    void dedup_hardWinsOverConditional() {
        registry.map.put("b1", fakeBlock("b1", List.of(new Requirement.Binary(FAKE_BINARY))));
        registry.map.put("b2", fakeBlock("b2", List.of(new Requirement.Binary(FAKE_BINARY))));

        ValidationResult result = diagnostics().diagnose(
                pipeline(block("cond", "b1", "$.x == 1"), block("hard", "b2", null)),
                null, noProviderCtx());

        assertThat(result.valid()).isFalse();
        long binaryFindings = result.errors().stream()
                .filter(e -> e.code().equals("PREFLIGHT_BINARY_MISSING"))
                .count();
        assertThat(binaryFindings).isEqualTo(1); // deduped to a single check
        assertThat(result.errors()).allMatch(e -> e.severity() == Severity.ERROR);
    }

    @Test
    void reachabilityFailure_isWarnNotBlock() {
        resolver.providerConfigured = true;
        registry.map.put("b1", fakeBlock("b1", List.of(new Requirement.Provider(LlmProvider.OPENROUTER))));

        ValidationResult result = diagnostics((url, t) -> new ReachabilityProbe.Result(false, "timeout"))
                .diagnose(pipeline(block("step1", "b1", null)), null, noProviderCtx());

        assertThat(result.valid()).isTrue();
        assertThat(result.errors())
                .anyMatch(e -> e.code().equals("PREFLIGHT_PROVIDER_UNREACHABLE") && e.severity() == Severity.WARN);
    }

    @Test
    void disabledBlock_requirementsIgnored() {
        BlockConfig disabled = block("step1", "b1", null);
        disabled.setEnabled(false);
        registry.map.put("b1", fakeBlock("b1", List.of(new Requirement.Binary(FAKE_BINARY))));

        ValidationResult result = diagnostics().diagnose(pipeline(disabled), null, noProviderCtx());

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void baseProviderRequirement_addedFromContext() {
        resolver.providerConfigured = false;
        PreflightContext ctx = new PreflightContext(null, LlmProvider.OPENROUTER, "default", null);

        ValidationResult result = diagnostics().diagnose(pipeline(), null, ctx);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.code().equals("PREFLIGHT_PROVIDER_MISSING") && e.severity() == Severity.ERROR);
    }

    @Test
    void emptyRequirements_isOk() {
        ValidationResult result = diagnostics().diagnose(pipeline(), null, noProviderCtx());
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }
}
