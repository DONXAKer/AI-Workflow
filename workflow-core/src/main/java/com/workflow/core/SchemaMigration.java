package com.workflow.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixes H2 ENUM column constraints that don't update automatically under ddl-auto:update.
 * When new values are added to Java enums (LlmProvider, IntegrationType), H2 keeps the old
 * CHECK constraint and rejects inserts. We relax those columns to plain VARCHAR once on startup.
 */
@Component
public class SchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private final JdbcTemplate jdbc;

    public SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void relaxEnumColumns() {
        relaxColumn("PROJECT", "DEFAULT_PROVIDER", "VARCHAR(50)");
        relaxColumn("INTEGRATION_CONFIG", "TYPE", "VARCHAR(50)");
        relaxColumn("LLM_CALL", "PROVIDER", "VARCHAR(50)");
    }

    private void relaxColumn(String table, String column, String newType) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " " + newType);
            log.debug("Schema migration: {}.{} relaxed to {}", table, column, newType);
        } catch (Exception e) {
            // Column may already be VARCHAR or table may not exist yet — both are fine
            log.trace("Schema migration skipped for {}.{}: {}", table, column, e.getMessage());
        }
    }
}
