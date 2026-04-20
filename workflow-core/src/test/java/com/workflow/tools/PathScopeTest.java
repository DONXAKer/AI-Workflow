package com.workflow.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathScopeTest {

    @Test
    void resolvesRelativePathUnderWorkingDir(@TempDir Path wd) throws IOException {
        Files.createDirectories(wd.resolve("src/main"));
        ToolContext ctx = ToolContext.of(wd);

        Path resolved = PathScope.resolve(ctx, "src/main/Foo.java");
        assertTrue(resolved.startsWith(wd.toRealPath()));
        assertTrue(resolved.toString().endsWith("Foo.java"));
    }

    @Test
    void rejectsParentEscape(@TempDir Path wd) {
        ToolContext ctx = ToolContext.of(wd);
        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
            () -> PathScope.resolve(ctx, "../escaped.txt"));
        assertTrue(ex.getMessage().contains("escapes working directory"));
    }

    @Test
    void rejectsAbsolutePathOutsideRoot(@TempDir Path wd, @TempDir Path outside) {
        ToolContext ctx = ToolContext.of(wd);
        assertThrows(ToolInvocationException.class,
            () -> PathScope.resolve(ctx, outside.resolve("x.txt").toString()));
    }

    @Test
    void rejectsNullAndBlank(@TempDir Path wd) {
        ToolContext ctx = ToolContext.of(wd);
        assertThrows(ToolInvocationException.class, () -> PathScope.resolve(ctx, null));
        assertThrows(ToolInvocationException.class, () -> PathScope.resolve(ctx, ""));
        assertThrows(ToolInvocationException.class, () -> PathScope.resolve(ctx, "   "));
    }

    @Test
    void acceptsAbsolutePathUnderRoot(@TempDir Path wd) throws IOException {
        Files.createDirectories(wd.resolve("pkg"));
        ToolContext ctx = ToolContext.of(wd);
        Path abs = wd.resolve("pkg/file.txt").toAbsolutePath();

        Path resolved = PathScope.resolve(ctx, abs.toString());
        assertEquals(wd.toRealPath().resolve("pkg/file.txt"), resolved);
    }

    @Test
    void acceptsNonexistentTargetUnderRoot(@TempDir Path wd) {
        ToolContext ctx = ToolContext.of(wd);
        Path resolved = PathScope.resolve(ctx, "new/nested/file.txt");
        assertTrue(resolved.toString().endsWith("file.txt"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsSymlinkEscape(@TempDir Path wd, @TempDir Path secret) throws IOException {
        Files.writeString(secret.resolve("secret.txt"), "sensitive");
        Path link = wd.resolve("peek");
        Files.createSymbolicLink(link, secret);

        ToolContext ctx = ToolContext.of(wd);
        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
            () -> PathScope.resolve(ctx, "peek/secret.txt"));
        assertTrue(ex.getMessage().contains("symlink")
            || ex.getMessage().contains("escapes working directory"));
    }
}
