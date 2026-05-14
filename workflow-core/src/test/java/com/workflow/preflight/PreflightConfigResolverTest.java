package com.workflow.preflight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers resolution order:
 * 1) CLAUDE.md ## Preflight section,
 * 2) auto-detect from build manifests,
 * 3) fallback.
 */
class PreflightConfigResolverTest {

    private final PreflightConfigResolver resolver = new PreflightConfigResolver();

    @Test
    void claudeMdPreflightSection_isParsedAndWins(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("CLAUDE.md"), """
                # Project X

                Some intro.

                ## Preflight
                build: gradle build -x integrationTest
                test: gradle test --tests '*UnitTest'
                fqn_format: junit5

                ## Other section
                irrelevant
                """);
        // Also create a build.gradle so we know CLAUDE.md takes priority.
        Files.writeString(tmp.resolve("build.gradle"), "// stub");

        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals(PreflightCommands.SOURCE_CLAUDE_MD, cmds.source());
        assertEquals("gradle build -x integrationTest", cmds.buildCmd());
        assertEquals("gradle test --tests '*UnitTest'", cmds.testCmd());
        assertEquals("junit5", cmds.fqnFormat());
    }

    @Test
    void claudeMdSectionWithoutKeys_fallsThroughToAutoDetect(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("CLAUDE.md"), """
                ## Preflight
                (intentionally empty)
                """);
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals(PreflightCommands.SOURCE_AUTO, cmds.source());
        assertEquals("maven", cmds.detected());
    }

    @Test
    void autoDetect_gradleWithWrapper_usesGradlewBinary(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle.kts"), "// kts build");
        Files.writeString(tmp.resolve("gradlew"), "#!/bin/sh\n");

        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals(PreflightCommands.SOURCE_AUTO, cmds.source());
        assertTrue(cmds.buildCmd().startsWith("./gradlew "), "expected wrapper-prefixed build, got: " + cmds.buildCmd());
        assertTrue(cmds.testCmd().startsWith("./gradlew "));
        assertTrue(cmds.detected().contains("wrapper"));
    }

    @Test
    void autoDetect_gradleWithoutWrapper_usesSystemGradle(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"), "// no wrapper");

        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals(PreflightCommands.SOURCE_AUTO, cmds.source());
        assertTrue(cmds.buildCmd().startsWith("gradle "));
        assertTrue(cmds.detected().contains("system"));
    }

    @Test
    void autoDetect_maven(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals(PreflightCommands.SOURCE_AUTO, cmds.source());
        assertEquals("maven", cmds.detected());
        assertTrue(cmds.testCmd().contains("mvn"));
    }

    @Test
    void autoDetect_npm(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("package.json"), "{\"name\":\"x\"}");
        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals("npm", cmds.detected());
        assertEquals("npm test", cmds.testCmd());
        assertEquals("jest", cmds.fqnFormat());
    }

    @Test
    void autoDetect_pythonPyproject(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pyproject.toml"), "[tool.poetry]");
        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals("pyproject", cmds.detected());
        assertEquals("pytest", cmds.testCmd());
    }

    @Test
    void autoDetect_go(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("go.mod"), "module example.com/x\n");
        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals("go", cmds.detected());
        assertEquals("go test ./...", cmds.testCmd());
    }

    @Test
    void autoDetect_cargo(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Cargo.toml"), "[package]");
        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals("cargo", cmds.detected());
        assertEquals("cargo test", cmds.testCmd());
    }

    @Test
    void noManifests_returnsFallback(@TempDir Path tmp) {
        PreflightCommands cmds = resolver.resolve(tmp);
        assertTrue(cmds.isFallback());
        assertEquals("none", cmds.fqnFormat());
        assertFalse(cmds.hasCommands());
    }

    @Test
    void nullWorkingDir_returnsFallback() {
        PreflightCommands cmds = resolver.resolve(null);
        assertTrue(cmds.isFallback());
    }

    @Test
    void claudeMdHeadingCaseInsensitive(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("CLAUDE.md"), """
                ## PREFLIGHT
                build: my-build
                test: my-test
                """);
        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals(PreflightCommands.SOURCE_CLAUDE_MD, cmds.source());
        assertEquals("my-build", cmds.buildCmd());
    }

    @Test
    void claudeMdTestFqnFormatAlias(@TempDir Path tmp) throws Exception {
        // Accepts both "fqn_format" (preferred) and "test_fqn_format" (legacy).
        Files.writeString(tmp.resolve("CLAUDE.md"), """
                ## Preflight
                build: b
                test: t
                test_fqn_format: pytest
                """);
        PreflightCommands cmds = resolver.resolve(tmp);
        assertEquals("pytest", cmds.fqnFormat());
    }
}
