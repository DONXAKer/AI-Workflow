package com.workflow.config;

import com.workflow.blocks.Block;
import com.workflow.blocks.BlockMetadata;
import com.workflow.blocks.FieldSchema;
import com.workflow.blocks.Phase;
import com.workflow.core.BlockRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PipelineConfigValidator}. Each error code has at least one
 * dedicated test that builds a {@link PipelineConfig} via setters (no YAML round-trip)
 * to keep failure modes isolated.
 *
 * <p>The "collect-all" semantics are exercised by the multi-error test: a single config
 * that violates several rules at once must produce a complete error list, not bail out
 * on the first failure.
 */
class PipelineConfigValidatorTest {

    private PipelineConfigValidator validator;

    /**
     * Builds a registry containing a fixed set of well-known block types so tests don't
     * depend on the live Spring context. Names cover the types referenced by every
     * positive test case below.
     */
    @BeforeEach
    void setUp() throws Exception {
        BlockRegistry registry = new BlockRegistry();
        List<Block> stubs = new ArrayList<>();
        for (String name : List.of("analysis", "verify", "code_generation", "shell_exec",
                "task_md_input", "agent_with_tools", "orchestrator")) {
            stubs.add(stubBlock(name));
        }
        ReflectionTestUtils.setField(registry, "allBlocks", stubs);
        // Invoke the package-private @PostConstruct manually (Spring isn't running)
        Method init = BlockRegistry.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(registry);
        this.validator = new PipelineConfigValidator(registry);
    }

    private static Block stubBlock(String name) {
        return new Block() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> run(Map<String, Object> input, BlockConfig config,
                                                     com.workflow.core.PipelineRun run) {
                return Map.of();
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BlockConfig block(String id, String type) {
        BlockConfig b = new BlockConfig();
        b.setId(id);
        b.setBlock(type);
        return b;
    }

    private static PipelineConfig pipeline(BlockConfig... blocks) {
        PipelineConfig cfg = new PipelineConfig();
        cfg.setPipeline(new ArrayList<>(List.of(blocks)));
        return cfg;
    }

    private static List<String> codes(ValidationResult r) {
        return r.errors().stream().map(ValidationError::code).toList();
    }

    // ── Level 1 ───────────────────────────────────────────────────────────────

    @Test
    void missingId_emitsMissingField() {
        BlockConfig b = new BlockConfig();
        b.setBlock("analysis");           // id intentionally absent
        ValidationResult r = validator.validate(pipeline(b));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.MISSING_FIELD));
    }

    @Test
    void missingBlockType_emitsMissingField() {
        BlockConfig b = new BlockConfig();
        b.setId("foo");                    // block type intentionally absent
        ValidationResult r = validator.validate(pipeline(b));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.MISSING_FIELD));
    }

    @Test
    void duplicateBlockId_emitsDuplicateBlockId() {
        ValidationResult r = validator.validate(pipeline(
            block("a", "analysis"),
            block("a", "verify")
        ));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.DUPLICATE_BLOCK_ID));
    }

    @Test
    void unknownBlockType_emitsUnknownBlockType() {
        ValidationResult r = validator.validate(pipeline(
            block("a", "no_such_block_type")
        ));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.UNKNOWN_BLOCK_TYPE));
    }

    // ── Level 2 ───────────────────────────────────────────────────────────────

    @Test
    void dependsOnUnknown_emitsDependsOnUnknown() {
        BlockConfig b = block("a", "analysis");
        b.setDependsOn(new ArrayList<>(List.of("ghost")));
        ValidationResult r = validator.validate(pipeline(b));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.DEPENDS_ON_UNKNOWN));
    }

    @Test
    void cycle_emitsDagCycle() {
        BlockConfig a = block("a", "analysis");
        a.setDependsOn(new ArrayList<>(List.of("b")));
        BlockConfig b = block("b", "verify");
        b.setDependsOn(new ArrayList<>(List.of("a")));
        ValidationResult r = validator.validate(pipeline(a, b));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.DAG_CYCLE));
    }

    @Test
    void entryPointUnknownBlock_emitsEntryPointUnknownBlock() {
        PipelineConfig cfg = pipeline(block("a", "analysis"));
        EntryPointConfig ep = new EntryPointConfig();
        ep.setId("scratch");
        ep.setFromBlock("ghost");
        cfg.setEntryPoints(new ArrayList<>(List.of(ep)));
        ValidationResult r = validator.validate(cfg);
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.ENTRY_POINT_UNKNOWN_BLOCK));
    }

    @Test
    void verifySubjectUnknown_emitsVerifySubjectUnknown() {
        BlockConfig v = block("v", "verify");
        VerifyConfig vc = new VerifyConfig();
        vc.setSubject("ghost");
        v.setVerify(vc);
        ValidationResult r = validator.validate(pipeline(v));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.VERIFY_SUBJECT_UNKNOWN));
    }

    @Test
    void verifyTargetUnknown_emitsVerifyTargetUnknown() {
        BlockConfig v = block("v", "verify");
        VerifyConfig vc = new VerifyConfig();
        OnFailConfig of = new OnFailConfig();
        of.setAction("loopback");
        of.setTarget("ghost");
        vc.setOnFail(of);
        v.setVerify(vc);
        ValidationResult r = validator.validate(pipeline(v));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.VERIFY_TARGET_UNKNOWN));
    }

    @Test
    void onFailureTargetUnknown_emitsOnFailureTargetUnknown() {
        BlockConfig ci = block("ci", "shell_exec");
        OnFailureConfig of = new OnFailureConfig();
        of.setAction("loopback");
        of.setTarget("ghost");
        ci.setOnFailure(of);
        ValidationResult r = validator.validate(pipeline(ci));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.ON_FAILURE_TARGET_UNKNOWN));
    }

    // ── Level 3 ───────────────────────────────────────────────────────────────

    @Test
    void refToUnknownBlock_emitsRefUnknownBlock() {
        // task_md exists; impl references ${ghost.field} in its config
        BlockConfig taskMd = block("task_md", "task_md_input");
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("task_md")));
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "Use ${ghost.title} please");
        impl.setConfig(conf);
        ValidationResult r = validator.validate(pipeline(taskMd, impl));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.REF_UNKNOWN_BLOCK));
    }

    @Test
    void refToDisabledBlock_emitsRefDisabledBlock() {
        BlockConfig disabled = block("plan", "orchestrator");
        disabled.setEnabled(false);
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("plan")));
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "${plan.goal}");
        impl.setConfig(conf);
        ValidationResult r = validator.validate(pipeline(disabled, impl));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.REF_DISABLED_BLOCK));
    }

    @Test
    void forwardRefDollarBrace_emitsForwardRef() {
        // 'a' references 'b', but 'b' is declared after 'a' and 'a' has no depends_on on 'b'
        BlockConfig a = block("a", "agent_with_tools");
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "${b.title}");
        a.setConfig(conf);
        BlockConfig b = block("b", "task_md_input");
        ValidationResult r = validator.validate(pipeline(a, b));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.FORWARD_REF));
    }

    @Test
    void forwardRefDollarDot_inCondition_emitsForwardRef() {
        // 'a' has condition referencing 'b', but b is later in DAG
        BlockConfig a = block("a", "verify");
        a.setCondition("$.b.passed == true");
        BlockConfig b = block("b", "verify");
        ValidationResult r = validator.validate(pipeline(a, b));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.FORWARD_REF));
    }

    @Test
    void inputRef_isSkipped() {
        // ${input.X} is a runtime concern — must NOT trigger validator errors
        BlockConfig a = block("a", "task_md_input");
        Map<String, Object> conf = new HashMap<>();
        conf.put("file_path", "${input.requirement}");
        a.setConfig(conf);
        ValidationResult r = validator.validate(pipeline(a));
        assertTrue(r.valid(), "validator should ignore ${input.X} refs");
    }

    @Test
    void disabledBlock_skipsLevel3_butStillValidatedForLevel1And2() {
        // disabled block with bad ref must not emit REF_*; depends_on still must resolve
        BlockConfig dis = block("dis", "agent_with_tools");
        dis.setEnabled(false);
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "${ghost.title}"); // bad ref, but disabled — should skip
        dis.setConfig(conf);
        // depends_on must still exist
        BlockConfig real = block("real", "task_md_input");
        dis.setDependsOn(new ArrayList<>(List.of("real")));

        ValidationResult r = validator.validate(pipeline(real, dis));
        assertTrue(r.valid(), "disabled block: bad ${...} should be ignored, deps still present");
    }

    @Test
    void disabledBlock_dependsOnUnknown_stillEmitsLevel2Error() {
        BlockConfig dis = block("dis", "agent_with_tools");
        dis.setEnabled(false);
        dis.setDependsOn(new ArrayList<>(List.of("ghost")));
        ValidationResult r = validator.validate(pipeline(dis));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.DEPENDS_ON_UNKNOWN));
    }

    @Test
    void verifyOnFailInjectContext_selfReference_isAllowed() {
        // Loopback inject_context fires AFTER the block has produced output, so the
        // referencing block can read its own output (e.g. its issues field).
        BlockConfig impl = block("impl", "agent_with_tools");
        BlockConfig review = block("review", "orchestrator");
        review.setDependsOn(new ArrayList<>(List.of("impl")));
        VerifyConfig vc = new VerifyConfig();
        OnFailConfig of = new OnFailConfig();
        of.setAction("loopback");
        of.setTarget("impl");
        of.setInjectContext(new HashMap<>(Map.of(
            "retry_instruction", "$.review.retry_instruction",
            "issues", "$.review.issues"
        )));
        vc.setOnFail(of);
        review.setVerify(vc);
        ValidationResult r = validator.validate(pipeline(impl, review));
        assertTrue(r.valid(), () -> "self-ref in verify.on_fail.inject_context should be allowed: " + r.errors());
    }

    @Test
    void verifyOnFailInjectContext_forwardRef_emitsForwardRef() {
        // a has verify.on_fail.inject_context referring to a block declared later
        BlockConfig a = block("a", "verify");
        VerifyConfig vc = new VerifyConfig();
        OnFailConfig of = new OnFailConfig();
        of.setAction("loopback");
        of.setTarget("a");                                       // self-target ok for this case (level-2 wise)
        of.setInjectContext(new HashMap<>(Map.of("issues", "$.b.issues")));
        vc.setOnFail(of);
        a.setVerify(vc);
        BlockConfig b = block("b", "verify");
        ValidationResult r = validator.validate(pipeline(a, b));
        assertFalse(r.valid());
        assertTrue(codes(r).contains(PipelineConfigValidator.FORWARD_REF));
    }

    // ── Collect-all behaviour ─────────────────────────────────────────────────

    @Test
    void multipleErrors_areAllReported() {
        // Three problems in one pipeline:
        //   - duplicate id 'x'
        //   - unknown block type
        //   - unknown depends_on
        BlockConfig x1 = block("x", "analysis");
        BlockConfig x2 = block("x", "no_such_type");      // duplicate id + unknown type
        BlockConfig y = block("y", "verify");
        y.setDependsOn(new ArrayList<>(List.of("ghost"))); // unknown dep
        ValidationResult r = validator.validate(pipeline(x1, x2, y));
        assertFalse(r.valid());
        List<String> cs = codes(r);
        assertTrue(cs.contains(PipelineConfigValidator.DUPLICATE_BLOCK_ID), cs.toString());
        assertTrue(cs.contains(PipelineConfigValidator.UNKNOWN_BLOCK_TYPE), cs.toString());
        assertTrue(cs.contains(PipelineConfigValidator.DEPENDS_ON_UNKNOWN), cs.toString());
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void wellFormedPipeline_isValid() {
        BlockConfig taskMd = block("task_md", "task_md_input");
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("task_md")));
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "Build ${task_md.title}");
        impl.setConfig(conf);
        BlockConfig v = block("v", "verify");
        v.setDependsOn(new ArrayList<>(List.of("impl")));
        VerifyConfig vc = new VerifyConfig();
        vc.setSubject("impl");
        OnFailConfig of = new OnFailConfig();
        of.setAction("loopback");
        of.setTarget("impl");
        of.setInjectContext(new HashMap<>(Map.of("issues", "$.v.issues")));
        // ^ self-ref through injection — that points back at the verify block itself,
        // which is the target. v is enabled, exists, and `v` referencing `v` is a
        // forward/self ref. We avoid that by referencing impl:
        of.setInjectContext(new HashMap<>(Map.of("issues", "$.impl.tool_calls_made")));
        vc.setOnFail(of);
        v.setVerify(vc);

        PipelineConfig cfg = pipeline(taskMd, impl, v);
        EntryPointConfig ep = new EntryPointConfig();
        ep.setId("implement");
        ep.setFromBlock("task_md");
        cfg.setEntryPoints(new ArrayList<>(List.of(ep)));

        ValidationResult r = validator.validate(cfg);
        assertTrue(r.valid(), () -> "expected valid, got: " + r.errors());
    }

    // ── Level 4 — phase ordering ──────────────────────────────────────────────

    @Test
    void phaseDecreasesAlongDependsOn_emitsPhaseMonotonicity() {
        // verify (VERIFY=3) → analysis (ANALYZE=1): violates monotonicity.
        BlockConfig analysis = block("analysis", "analysis");
        BlockConfig v = block("v", "verify");
        v.setDependsOn(new ArrayList<>(List.of("analysis")));
        // Reverse: declare a block whose successor is earlier in phase order.
        // analysis depends_on verify — analysis(ANALYZE)<verify(VERIFY).
        analysis.setDependsOn(new ArrayList<>(List.of("v")));
        // To avoid DAG cycle, drop v's depends_on:
        v.setDependsOn(new ArrayList<>());
        // Now analysis -> v means v completes first, then analysis runs;
        // that's analysis(ANALYZE=1) AFTER v(VERIFY=3), which violates.
        ValidationResult r = validator.validate(pipeline(v, analysis));
        assertTrue(codes(r).contains(PipelineConfigValidator.PHASE_MONOTONICITY),
            () -> "expected PHASE_MONOTONICITY, got: " + r.errors());
    }

    @Test
    void phaseRespected_noPhaseErrors() {
        // task_md(INTAKE) → analysis(ANALYZE) → impl(IMPLEMENT) → v(VERIFY): all monotonic.
        BlockConfig taskMd = block("task_md", "task_md_input");
        BlockConfig analysis = block("analysis", "analysis");
        analysis.setDependsOn(new ArrayList<>(List.of("task_md")));
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("analysis")));
        BlockConfig v = block("v", "verify");
        v.setDependsOn(new ArrayList<>(List.of("impl")));
        ValidationResult r = validator.validate(pipeline(taskMd, analysis, impl, v));
        assertFalse(codes(r).contains(PipelineConfigValidator.PHASE_MONOTONICITY),
            () -> "expected no PHASE_MONOTONICITY, got: " + r.errors());
    }

    @Test
    void loopbackToLaterPhase_emitsPhaseLoopbackForward() {
        // verify (VERIFY) loops back to a target in a strictly LATER phase — illegal.
        // Need an IMPLEMENT block whose loopback target is RELEASE (we don't have
        // 'release_notes' in the stub registry, so use shell_exec with phase override).
        BlockConfig impl = block("impl", "agent_with_tools");          // IMPLEMENT
        BlockConfig later = block("later", "shell_exec");              // ANY by default
        later.setPhase("release");                                       // override → RELEASE
        BlockConfig v = block("v", "verify");                           // VERIFY
        v.setDependsOn(new ArrayList<>(List.of("impl")));
        VerifyConfig vc = new VerifyConfig();
        vc.setSubject("impl");
        OnFailConfig of = new OnFailConfig();
        of.setAction("loopback");
        of.setTarget("later");                                          // VERIFY → RELEASE: forward
        vc.setOnFail(of);
        v.setVerify(vc);
        ValidationResult r = validator.validate(pipeline(impl, later, v));
        assertTrue(codes(r).contains(PipelineConfigValidator.PHASE_LOOPBACK_FORWARD),
            () -> "expected PHASE_LOOPBACK_FORWARD, got: " + r.errors());
    }

    @Test
    void polymorphicWithoutOverride_emitsWarn() {
        // shell_exec defaults to ANY — without explicit phase override should WARN.
        BlockConfig sh = block("sh", "shell_exec");
        ValidationResult r = validator.validate(pipeline(sh));
        assertTrue(codes(r).contains(PipelineConfigValidator.PHASE_OVERRIDE_MISSING),
            () -> "expected PHASE_OVERRIDE_MISSING, got: " + r.errors());
        // WARN does not block: the result must still be valid().
        assertTrue(r.valid(), () -> "WARN-only result should be valid, got: " + r.errors());
    }

    @Test
    void polymorphicWithOverride_noWarn() {
        BlockConfig sh = block("sh", "shell_exec");
        sh.setPhase("verify");
        ValidationResult r = validator.validate(pipeline(sh));
        assertFalse(codes(r).contains(PipelineConfigValidator.PHASE_OVERRIDE_MISSING),
            () -> "no PHASE_OVERRIDE_MISSING expected when phase is set, got: " + r.errors());
    }

    @Test
    void phaseCheckDisabled_skipsLevel4() {
        // Construct a pipeline that would normally fail PHASE_MONOTONICITY
        // and assert that phase_check=false suppresses the error entirely.
        BlockConfig v = block("v", "verify");
        BlockConfig analysis = block("analysis", "analysis");
        analysis.setDependsOn(new ArrayList<>(List.of("v")));
        PipelineConfig cfg = pipeline(v, analysis);
        cfg.setPhaseCheck(false);
        ValidationResult r = validator.validate(cfg);
        assertFalse(codes(r).contains(PipelineConfigValidator.PHASE_MONOTONICITY),
            () -> "phase_check=false should skip Level 4, got: " + r.errors());
        assertFalse(codes(r).contains(PipelineConfigValidator.PHASE_OVERRIDE_MISSING));
    }

    @Test
    void anyBlockTransparentInDependsOn_noErrors() {
        // analysis(ANALYZE) → orchestrator(ANY override → analyze) → impl(IMPLEMENT):
        // ANY block, even when overridden, is transparent only when ANY itself.
        // Here we test the truly-transparent case: leave orchestrator as ANY.
        BlockConfig analysis = block("analysis", "analysis");
        BlockConfig orch = block("orch", "orchestrator");           // stays ANY
        orch.setDependsOn(new ArrayList<>(List.of("analysis")));
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("orch")));        // IMPLEMENT depends on ANY → transparent
        ValidationResult r = validator.validate(pipeline(analysis, orch, impl));
        assertFalse(codes(r).contains(PipelineConfigValidator.PHASE_MONOTONICITY),
            () -> "ANY should be transparent in monotonicity, got: " + r.errors());
    }

    @Test
    void unknownPhaseString_emitsInvalidPhase() {
        BlockConfig sh = block("sh", "shell_exec");
        sh.setPhase("bogus");
        ValidationResult r = validator.validate(pipeline(sh));
        assertTrue(codes(r).contains(PipelineConfigValidator.INVALID_PHASE),
            () -> "expected INVALID_PHASE, got: " + r.errors());
    }

    // ── REF_UNKNOWN_FIELD (PR-1) ──────────────────────────────────────────────

    /** Builds a validator backed by a registry that includes a stub with declared outputs. */
    private PipelineConfigValidator validatorWithOutputsStub() throws Exception {
        BlockRegistry registry = new BlockRegistry();
        List<Block> stubs = new ArrayList<>();
        for (String name : List.of("verify", "shell_exec")) {
            stubs.add(stubBlock(name));
        }
        // Stub 'analysis' returning a real output schema so REF_UNKNOWN_FIELD can fire.
        stubs.add(new Block() {
            @Override public String getName() { return "analysis"; }
            @Override public String getDescription() { return "analysis"; }
            @Override public Map<String, Object> run(Map<String, Object> input, BlockConfig config,
                                                     com.workflow.core.PipelineRun run) {
                return Map.of();
            }
            @Override public BlockMetadata getMetadata() {
                return new BlockMetadata("Analysis", "agent", Phase.ANALYZE, List.of(),
                    false, Map.of(),
                    List.of(
                        FieldSchema.output("summary", "Summary", "string", "summary"),
                        FieldSchema.output("estimated_complexity", "Complexity", "enum", "complexity")
                    ),
                    100);
            }
        });
        // Stub 'agent_with_tools' — declares NO outputs, exercises backwards-compat skip.
        stubs.add(new Block() {
            @Override public String getName() { return "agent_with_tools"; }
            @Override public String getDescription() { return "agent_with_tools"; }
            @Override public Map<String, Object> run(Map<String, Object> input, BlockConfig config,
                                                     com.workflow.core.PipelineRun run) {
                return Map.of();
            }
        });
        // task_md_input stub used as a reference target — empty outputs.
        stubs.add(stubBlock("task_md_input"));
        ReflectionTestUtils.setField(registry, "allBlocks", stubs);
        Method init = BlockRegistry.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(registry);
        return new PipelineConfigValidator(registry);
    }

    @Test
    void refToKnownOutputField_noWarning() throws Exception {
        PipelineConfigValidator v = validatorWithOutputsStub();
        BlockConfig analysis = block("analysis", "analysis");
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("analysis")));
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "Build ${analysis.summary}");
        impl.setConfig(conf);
        ValidationResult r = v.validate(pipeline(analysis, impl));
        assertFalse(codes(r).contains(PipelineConfigValidator.REF_UNKNOWN_FIELD),
            () -> "no warning expected for known field, got: " + r.errors());
    }

    @Test
    void refToUnknownOutputField_emitsWarn() throws Exception {
        PipelineConfigValidator v = validatorWithOutputsStub();
        BlockConfig analysis = block("analysis", "analysis");
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("analysis")));
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "Build ${analysis.nonsense_field}");
        impl.setConfig(conf);
        ValidationResult r = v.validate(pipeline(analysis, impl));
        assertTrue(codes(r).contains(PipelineConfigValidator.REF_UNKNOWN_FIELD),
            () -> "expected REF_UNKNOWN_FIELD, got: " + r.errors());
        // WARN-only — must NOT make result invalid.
        assertTrue(r.valid(),
            () -> "REF_UNKNOWN_FIELD is WARN, result must stay valid: " + r.errors());
    }

    @Test
    void refToUnknownField_inCondition_emitsWarn() throws Exception {
        PipelineConfigValidator v = validatorWithOutputsStub();
        BlockConfig analysis = block("analysis", "analysis");
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("analysis")));
        impl.setCondition("$.analysis.bogus_field == 'x'");
        ValidationResult r = v.validate(pipeline(analysis, impl));
        assertTrue(codes(r).contains(PipelineConfigValidator.REF_UNKNOWN_FIELD),
            () -> "expected REF_UNKNOWN_FIELD on condition, got: " + r.errors());
    }

    @Test
    void refToBlockWithoutDeclaredOutputs_skipsWarn() throws Exception {
        PipelineConfigValidator v = validatorWithOutputsStub();
        // agent_with_tools stub has no outputs declared — the field check must
        // silently skip (backwards-compat for unmigrated blocks).
        BlockConfig impl = block("impl", "agent_with_tools");
        BlockConfig downstream = block("downstream", "agent_with_tools");
        downstream.setDependsOn(new ArrayList<>(List.of("impl")));
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "Read ${impl.literally_anything}");
        downstream.setConfig(conf);
        ValidationResult r = v.validate(pipeline(impl, downstream));
        assertFalse(codes(r).contains(PipelineConfigValidator.REF_UNKNOWN_FIELD),
            () -> "block w/o declared outputs must skip field check, got: " + r.errors());
    }

    @Test
    void bareBlockRefWithoutTail_skipsWarn() throws Exception {
        // ${analysis} (no .field) — there's no field to check, must not warn.
        PipelineConfigValidator v = validatorWithOutputsStub();
        BlockConfig analysis = block("analysis", "analysis");
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("analysis")));
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "${analysis}");
        impl.setConfig(conf);
        ValidationResult r = v.validate(pipeline(analysis, impl));
        assertFalse(codes(r).contains(PipelineConfigValidator.REF_UNKNOWN_FIELD),
            () -> "bare block ref must not trigger field check, got: " + r.errors());
    }

    @Test
    void selfRefBogusField_inOnFailInjectContext_emitsWarn() throws Exception {
        // Self-ref in verify.on_fail.inject_context is allowed (post-execution),
        // BUT a typo in the field must still surface as REF_UNKNOWN_FIELD.
        PipelineConfigValidator v = validatorWithOutputsStub();
        BlockConfig impl = block("impl", "agent_with_tools");
        BlockConfig analysis = block("analysis", "analysis");          // self-target
        VerifyConfig vc = new VerifyConfig();
        OnFailConfig of = new OnFailConfig();
        of.setAction("loopback");
        of.setTarget("impl");
        of.setInjectContext(new HashMap<>(Map.of("issues", "$.analysis.no_such_field")));
        vc.setOnFail(of);
        analysis.setVerify(vc);
        analysis.setDependsOn(new ArrayList<>(List.of("impl")));
        ValidationResult r = v.validate(pipeline(impl, analysis));
        assertTrue(codes(r).contains(PipelineConfigValidator.REF_UNKNOWN_FIELD),
            () -> "self-ref bogus field should warn, got: " + r.errors());
    }

    @Test
    void nestedFieldPath_validatesOnlyFirstSegment() throws Exception {
        // $.analysis.summary.something — first segment 'summary' IS declared,
        // 'something' is not validated (declared outputs don't carry nested schemas).
        PipelineConfigValidator v = validatorWithOutputsStub();
        BlockConfig analysis = block("analysis", "analysis");
        BlockConfig impl = block("impl", "agent_with_tools");
        impl.setDependsOn(new ArrayList<>(List.of("analysis")));
        Map<String, Object> conf = new HashMap<>();
        conf.put("user_message", "Read ${analysis.summary.length} chars");
        impl.setConfig(conf);
        ValidationResult r = v.validate(pipeline(analysis, impl));
        assertFalse(codes(r).contains(PipelineConfigValidator.REF_UNKNOWN_FIELD),
            () -> "nested-segment path on known field must not warn, got: " + r.errors());
    }
}
