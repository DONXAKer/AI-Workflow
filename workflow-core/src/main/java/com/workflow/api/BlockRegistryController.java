package com.workflow.api;

import com.workflow.blocks.Block;
import com.workflow.blocks.BlockMetadata;
import com.workflow.core.BlockRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the catalog of registered block types and their UI-editor metadata to the
 * Pipeline Editor frontend. Read-only — block registration happens at Spring startup.
 */
@RestController
@RequestMapping("/api")
public class BlockRegistryController {

    @Autowired
    private BlockRegistry blockRegistry;

    /**
     * GET /api/blocks/registry
     *
     * <p>Returns an array of {@code {type, description, metadata}} entries — one per
     * registered block. The frontend's {@code useBlockRegistry} hook caches this.
     */
    @GetMapping("/blocks/registry")
    public ResponseEntity<List<Map<String, Object>>> getRegistry() {
        List<Map<String, Object>> result = blockRegistry.getAllBlocks().stream()
            .map(b -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", b.getName());
                entry.put("description", b.getDescription());
                BlockMetadata md = b.getMetadata();
                entry.put("metadata", md != null ? md : BlockMetadata.defaultFor(b.getName()));
                return entry;
            })
            .toList();
        return ResponseEntity.ok(result);
    }
}
