package com.workflow.preflight;

import com.workflow.integrations.IntegrationResolver;
import com.workflow.llm.LlmProvider;
import com.workflow.model.IntegrationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequirementChecker}. Uses a hand-written {@link FakeResolver} and a lambda
 * {@link ReachabilityProbe} (no Mockito) so it is independent of the network and the JDK/Mockito
 * bytecode-agent compatibility.
 */
class RequirementCheckerTest {

    private static final String REAL_BINARY = "sh";  // present on every POSIX/CI PATH
    private static final String FAKE_BINARY = "definitely-not-a-real-binary-xyz-9000";

    /** Configurable stand-in for {@link IntegrationResolver}. */
    static class FakeResolver extends IntegrationResolver {
        boolean providerConfigured = true;
        String baseUrl = "http://provider.test/v1";
        String cliBin = REAL_BINARY;
        boolean tokenPresent = true;

        FakeResolver() { super(null); }
        @Override public boolean isProviderConfigured(LlmProvider p) { return providerConfigured; }
        @Override public String providerBaseUrl(LlmProvider p) { return baseUrl; }
        @Override public String cliBinary() { return cliBin; }
        @Override public boolean hasToken(String name, IntegrationType type) { return tokenPresent; }
    }

    private final FakeResolver resolver = new FakeResolver();

    private RequirementChecker checker(ReachabilityProbe probe) {
        return new RequirementChecker(resolver, probe);
    }

    private RequirementChecker checker() {
        return checker((url, t) -> new ReachabilityProbe.Result(true, "ok"));
    }

    private PreflightContext ctx(String workingDir, LlmProvider provider) {
        return new PreflightContext(workingDir, provider, "default", null);
    }

    // ---- WorkingDir ----

    @Test
    void workingDir_existingWritableDir_passes(@TempDir Path dir) {
        assertThat(checker().check(new Requirement.WorkingDir(dir.toString()), ctx(null, null)).kind())
                .isEqualTo(CheckResult.Kind.PASS);
    }

    @Test
    void workingDir_missing_hardFails() {
        CheckResult r = checker().check(
                new Requirement.WorkingDir("/no/such/path/anywhere/12345"), ctx(null, null));
        assertThat(r.kind()).isEqualTo(CheckResult.Kind.HARD_FAIL);
        assertThat(r.detail()).contains("does not exist");
    }

    @Test
    void workingDir_isAFile_hardFails(@TempDir Path dir) throws IOException {
        Path file = Files.createFile(dir.resolve("a.txt"));
        CheckResult r = checker().check(new Requirement.WorkingDir(file.toString()), ctx(null, null));
        assertThat(r.kind()).isEqualTo(CheckResult.Kind.HARD_FAIL);
        assertThat(r.detail()).contains("not a directory");
    }

    @Test
    void workingDir_nullPathAndNoRunDir_hardFails() {
        CheckResult r = checker().check(new Requirement.WorkingDir((String) null), ctx(null, null));
        assertThat(r.kind()).isEqualTo(CheckResult.Kind.HARD_FAIL);
        assertThat(r.detail()).contains("no working directory");
    }

    @Test
    void workingDir_nullPathUsesRunDir(@TempDir Path dir) {
        assertThat(checker().check(new Requirement.WorkingDir((String) null), ctx(dir.toString(), null)).kind())
                .isEqualTo(CheckResult.Kind.PASS);
    }

    // ---- Binary ----

    @Test
    void binary_onPath_passes() {
        assertThat(checker().check(new Requirement.Binary(REAL_BINARY), ctx(null, null)).kind())
                .isEqualTo(CheckResult.Kind.PASS);
    }

    @Test
    void binary_missing_hardFails() {
        CheckResult r = checker().check(new Requirement.Binary(FAKE_BINARY), ctx(null, null));
        assertThat(r.kind()).isEqualTo(CheckResult.Kind.HARD_FAIL);
        assertThat(r.detail()).contains("not found on PATH");
    }

    @Test
    void binary_blankName_passesAsSkip() {
        assertThat(checker().check(new Requirement.Binary(""), ctx(null, null)).kind())
                .isEqualTo(CheckResult.Kind.PASS);
    }

    // ---- Provider ----

    @Test
    void provider_notConfigured_hardFails() {
        resolver.providerConfigured = false;
        CheckResult r = checker().check(new Requirement.Provider(LlmProvider.OPENROUTER), ctx(null, null));
        assertThat(r.kind()).isEqualTo(CheckResult.Kind.HARD_FAIL);
        assertThat(r.detail()).contains("no API key");
    }

    @Test
    void provider_configuredAndReachable_passes() {
        resolver.providerConfigured = true;
        CheckResult r = checker((url, t) -> new ReachabilityProbe.Result(true, "HTTP 200"))
                .check(new Requirement.Provider(LlmProvider.OPENROUTER), ctx(null, null));
        assertThat(r.kind()).isEqualTo(CheckResult.Kind.PASS);
    }

    @Test
    void provider_configuredButUnreachable_softFails() {
        resolver.providerConfigured = true;
        CheckResult r = checker((url, t) -> new ReachabilityProbe.Result(false, "timeout"))
                .check(new Requirement.Provider(LlmProvider.OPENROUTER), ctx(null, null));
        assertThat(r.kind()).isEqualTo(CheckResult.Kind.SOFT_FAIL);
    }

    @Test
    void provider_cli_checksBinaryNotHttp() {
        resolver.cliBin = REAL_BINARY;
        assertThat(checker().check(new Requirement.Provider(LlmProvider.CLAUDE_CODE_CLI), ctx(null, null)).kind())
                .isEqualTo(CheckResult.Kind.PASS);

        resolver.cliBin = FAKE_BINARY;
        assertThat(checker().check(new Requirement.Provider(LlmProvider.CLAUDE_CODE_CLI), ctx(null, null)).kind())
                .isEqualTo(CheckResult.Kind.HARD_FAIL);
    }

    // ---- Integration ----

    @Test
    void integration_withToken_passes() {
        resolver.tokenPresent = true;
        assertThat(checker().check(new Requirement.Integration(IntegrationType.GITHUB), ctx(null, null)).kind())
                .isEqualTo(CheckResult.Kind.PASS);
    }

    @Test
    void integration_missingToken_hardFails() {
        resolver.tokenPresent = false;
        assertThat(checker().check(new Requirement.Integration(IntegrationType.GITHUB), ctx(null, null)).kind())
                .isEqualTo(CheckResult.Kind.HARD_FAIL);
    }

    // ---- GitRepo ----

    @Test
    void gitRepo_present_passes(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve(".git"));
        assertThat(checker().check(new Requirement.GitRepo(), ctx(dir.toString(), null)).kind())
                .isEqualTo(CheckResult.Kind.PASS);
    }

    @Test
    void gitRepo_absent_hardFails(@TempDir Path dir) {
        CheckResult r = checker().check(new Requirement.GitRepo(), ctx(dir.toString(), null));
        assertThat(r.kind()).isEqualTo(CheckResult.Kind.HARD_FAIL);
        assertThat(r.detail()).contains("not a git repository");
    }

    // ---- ShellCommandBinary parsing ----

    @Test
    void shellBinary_plainCommand() {
        assertThat(ShellCommandBinary.firstBinary("gradle build")).isEqualTo("gradle");
    }

    @Test
    void shellBinary_unwrapsShDashC() {
        assertThat(ShellCommandBinary.firstBinary("sh -c \"npm ci && npm test\"")).isEqualTo("npm");
    }

    @Test
    void shellBinary_skipsUnresolvedInterpolation() {
        assertThat(ShellCommandBinary.firstBinary("${input.build_cmd} --flag")).isNull();
    }

    @Test
    void shellBinary_skipsLeadingEnvAssignment() {
        assertThat(ShellCommandBinary.firstBinary("FOO=bar")).isNull();
    }
}
