package com.workflow.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops obsolete H2 CHECK constraints that prevent queries with newly-added enum values.
 * Runs once at startup before any pipeline code executes.
 */
@Component
public class SchemaRepair {

    private static final Logger log = LoggerFactory.getLogger(SchemaRepair.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void repairEnumConstraints() {
        try {
            var rows = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                "WHERE TABLE_NAME = 'INTEGRATION_CONFIG' AND CONSTRAINT_TYPE = 'CHECK'");
            for (var row : rows) {
                String name = (String) row.get("CONSTRAINT_NAME");
                jdbcTemplate.execute("ALTER TABLE integration_config DROP CONSTRAINT IF EXISTS \"" + name + "\"");
                log.info("SchemaRepair: dropped stale CHECK constraint '{}' on integration_config", name);
            }
        } catch (Exception e) {
            log.debug("SchemaRepair: skipped ({})", e.getMessage());
        }
    }
}
