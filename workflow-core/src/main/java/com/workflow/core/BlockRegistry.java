package com.workflow.core;

import com.workflow.blocks.Block;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Source of truth for the set of registered block types. Wraps {@link Block#getName()}
 * lookup, so consumers (PipelineRunner, PipelineConfigValidator) don't each maintain their
 * own copy of the registry.
 *
 * <p>Why a separate Spring bean: PipelineConfigValidator runs at config-load time, before
 * a run is created — it needs the registry without depending on PipelineRunner's lifecycle
 * or its many other collaborators.
 */
@Service
public class BlockRegistry {

    private static final Logger log = LoggerFactory.getLogger(BlockRegistry.class);

    @Autowired
    private List<Block> allBlocks;

    private Map<String, Block> registry;

    @PostConstruct
    void init() {
        Map<String, Block> map = new HashMap<>();
        for (Block block : allBlocks) {
            map.put(block.getName(), block);
        }
        this.registry = Collections.unmodifiableMap(map);
        log.info("BlockRegistry initialized with {} block types: {}", registry.size(), registry.keySet());
    }

    /** @return the {@link Block} bean for the given type name, or {@code null} if unknown. */
    public Block get(String blockType) {
        return registry.get(blockType);
    }

    /** @return {@code true} iff the given block type is registered. */
    public boolean contains(String blockType) {
        return registry.containsKey(blockType);
    }

    /** @return the set of registered block type names. */
    public Set<String> blockTypes() {
        return registry.keySet();
    }
}
