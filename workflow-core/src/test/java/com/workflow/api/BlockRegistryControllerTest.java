package com.workflow.api;

import com.workflow.blocks.Block;
import com.workflow.blocks.BlockMetadata;
import com.workflow.blocks.FieldSchema;
import com.workflow.config.BlockConfig;
import com.workflow.core.BlockRegistry;
import com.workflow.core.PipelineRun;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@link BlockRegistryController#getRegistry()} contract:
 * payload shape is stable, every entry has type+description+metadata, and
 * blocks declaring metadata pass it through unchanged.
 */
class BlockRegistryControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void registryReturnsTypeAndMetadataPerBlock() throws Exception {
        Block plain = stubBlock("plain", null);
        Block withMeta = stubBlock("with_meta", new BlockMetadata(
            "Custom Form Block",
            "agent",
            List.of(FieldSchema.string("foo", "Foo", "the foo field")),
            true,
            Map.of("hint", "x")
        ));

        BlockRegistry registry = new BlockRegistry();
        ReflectionTestUtils.setField(registry, "allBlocks", List.of(plain, withMeta));
        Method init = BlockRegistry.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(registry);

        BlockRegistryController controller = new BlockRegistryController();
        ReflectionTestUtils.setField(controller, "blockRegistry", registry);

        ResponseEntity<List<Map<String, Object>>> resp = controller.getRegistry();
        assertEquals(200, resp.getStatusCode().value());
        List<Map<String, Object>> body = resp.getBody();
        assertNotNull(body);
        assertEquals(2, body.size());

        // Sorted by type name
        Map<String, Object> first = body.get(0);
        assertEquals("plain", first.get("type"));
        BlockMetadata firstMeta = (BlockMetadata) first.get("metadata");
        assertEquals("plain", firstMeta.label(), "default metadata label = block name");
        assertEquals("general", firstMeta.category());
        assertTrue(firstMeta.configFields().isEmpty());
        assertTrue(!firstMeta.hasCustomForm());

        Map<String, Object> second = body.get(1);
        assertEquals("with_meta", second.get("type"));
        BlockMetadata secondMeta = (BlockMetadata) second.get("metadata");
        assertEquals("Custom Form Block", secondMeta.label());
        assertEquals("agent", secondMeta.category());
        assertEquals(1, secondMeta.configFields().size());
        assertTrue(secondMeta.hasCustomForm());
    }

    private static Block stubBlock(String name, BlockMetadata md) {
        return new Block() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name + " desc"; }
            @Override public Map<String, Object> run(Map<String, Object> in, BlockConfig c, PipelineRun r) {
                return Map.of();
            }
            @Override public BlockMetadata getMetadata() {
                return md != null ? md : Block.super.getMetadata();
            }
        };
    }
}
