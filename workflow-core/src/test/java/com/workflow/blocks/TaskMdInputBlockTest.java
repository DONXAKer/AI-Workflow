package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskMdInputBlockTest {

    private TaskMdInputBlock block;

    @BeforeEach
    void setUp() {
        block = new TaskMdInputBlock();
    }

    private BlockConfig cfgWith(String filePath) {
        BlockConfig bc = new BlockConfig();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("file_path", filePath);
        bc.setConfig(cfg);
        return bc;
    }

    @Test
    void parsesFilenameSectionsAndHeuristics(@TempDir Path wd) throws Exception {
        Path task = wd.resolve("FIX-BOOT-009_fix-boot-crash.md");
        Files.writeString(task, """
            # Fix the boot crash

            ## Как сейчас
            При старте клиент падает с NPE в USTRUCT FErrorMessage.

            ## Как надо
            Добавить USTRUCT и сервер шлёт валидный payload.

            ## Вне scope
            UI/UMG менять не нужно.

            ## Критерии приёмки
            - Тест t1 зелёный
            - Validator clean

            Files: src/server/Foo.cpp
            """);

        Map<String, Object> out = block.run(Map.of(), cfgWith(task.toString()), new PipelineRun());

        assertEquals("FIX-BOOT-009", out.get("feat_id"));
        assertEquals("fix-boot-crash", out.get("slug"));
        assertEquals("Fix the boot crash", out.get("title"));
        assertTrue(((String) out.get("as_is")).contains("NPE в USTRUCT"));
        assertTrue(((String) out.get("to_be")).contains("валидный payload"));
        assertTrue(((String) out.get("out_of_scope")).contains("UMG"));
        assertTrue(((String) out.get("acceptance")).contains("Validator clean"));

        assertTrue((Boolean) out.get("needs_contract_change"),
            "USTRUCT/payload should trigger contract change flag");
        assertTrue((Boolean) out.get("needs_server"));
        assertFalse((Boolean) out.get("is_greenfield"));
    }

    @Test
    void missingOptionalSectionsAreEmpty(@TempDir Path wd) throws Exception {
        Path task = wd.resolve("TASK-1_minimal.md");
        Files.writeString(task, """
            # Minimal

            ## Как сейчас
            Старое поведение.
            """);

        Map<String, Object> out = block.run(Map.of(), cfgWith(task.toString()), new PipelineRun());

        assertEquals("Старое поведение.", out.get("as_is"));
        assertEquals("", out.get("to_be"));
        assertEquals("", out.get("out_of_scope"));
        assertEquals("", out.get("acceptance"));
    }

    @Test
    void filenameWithoutPatternSetsEmptyFeatId(@TempDir Path wd) throws Exception {
        Path task = wd.resolve("random-name.md");
        Files.writeString(task, "# Hi\n\nnothing to see\n");

        Map<String, Object> out = block.run(Map.of(), cfgWith(task.toString()), new PipelineRun());
        assertEquals("", out.get("feat_id"));
        assertEquals("random-name", out.get("slug"));
    }

    @Test
    void greenfieldDetected(@TempDir Path wd) throws Exception {
        Path task = wd.resolve("GF-1_new-cli-command.md");
        Files.writeString(task, """
            # New CLI command

            ## Как сейчас
            Нет такой команды.

            ## Как надо
            Добавить команду workflow list, которая печатает все pipelines.
            """);

        Map<String, Object> out = block.run(Map.of(), cfgWith(task.toString()), new PipelineRun());
        assertTrue((Boolean) out.get("is_greenfield"));
        assertFalse((Boolean) out.get("needs_server"));
        assertFalse((Boolean) out.get("needs_contract_change"));
    }

    @Test
    void docsTaskSuppressesImplFlags(@TempDir Path wd) throws Exception {
        Path task = wd.resolve("FEAT-DOCS-001-migrate-active-design-docs.md");
        Files.writeString(task, """
            # Миграция документации

            ## Как сейчас
            В client/Documentation/ остались файлы:
            - DiceRoll_Blueprint_Design.md
            - WBP_UnitTooltip_Blueprint_Design.md
            - GameSettings_Blueprint_Design.md

            ## Как надо
            Каждый файл классифицировать и либо привязать к task, либо удалить.
            """);

        Map<String, Object> out = block.run(Map.of(), cfgWith(task.toString()), new PipelineRun());

        assertEquals("FEAT-DOCS-001", out.get("feat_id"));
        assertTrue((Boolean) out.get("is_docs"),
            "FEAT-DOCS- prefix должен ставить is_docs=true");
        assertFalse((Boolean) out.get("needs_bp"),
            "is_docs=true должен подавлять needs_bp несмотря на '*_Blueprint_Design.md' в теле");
        assertFalse((Boolean) out.get("needs_client"),
            "is_docs=true должен подавлять needs_client несмотря на упоминания client/");
        assertFalse((Boolean) out.get("needs_server"));
        assertFalse((Boolean) out.get("needs_contract_change"));
    }

    @Test
    void wordBoundaryAvoidsSubstringFalsePositive(@TempDir Path wd) throws Exception {
        // Не-DOCS feat_id, но в теле перечислены *_Blueprint_Design.md файлы как ссылки —
        // word-boundary regex не должен матчить "blueprint" внутри _Blueprint_Design.
        Path task = wd.resolve("FEAT-REFA-001-rename-files.md");
        Files.writeString(task, """
            # Переименовать файлы

            ## Как сейчас
            Существуют файлы:
            - GameSettings_Blueprint_Design.md
            - DiceRoll_Blueprint_Design.md

            ## Как надо
            Переименовать в snake_case.
            """);

        Map<String, Object> out = block.run(Map.of(), cfgWith(task.toString()), new PipelineRun());
        assertFalse((Boolean) out.get("needs_bp"),
            "'blueprint' внутри *_Blueprint_Design.md не должен триггерить needs_bp");
        assertFalse((Boolean) out.get("is_docs"),
            "feat_id=FEAT-REFA — не DOCS-префикс, is_docs=false");
    }

    @Test
    void wordBoundaryStillMatchesStandaloneKeyword(@TempDir Path wd) throws Exception {
        Path task = wd.resolve("FEAT-HUD-002-tooltip-widget.md");
        Files.writeString(task, """
            # Tooltip widget

            ## Как надо
            Реализовать tooltip как Blueprint widget в UMG editor.
            """);

        Map<String, Object> out = block.run(Map.of(), cfgWith(task.toString()), new PipelineRun());
        assertTrue((Boolean) out.get("needs_bp"),
            "standalone 'Blueprint' / 'widget' / 'umg' слова должны триггерить needs_bp");
    }

    @Test
    void missingFileFails(@TempDir Path wd) {
        assertThrows(IllegalArgumentException.class,
            () -> block.run(Map.of(), cfgWith(wd.resolve("nope.md").toString()), new PipelineRun()));
    }

    @Test
    void missingFilePathFails(@TempDir Path wd) {
        BlockConfig bc = new BlockConfig();
        bc.setConfig(new HashMap<>());
        assertThrows(IllegalArgumentException.class,
            () -> block.run(Map.of(), bc, new PipelineRun()));
    }
}
