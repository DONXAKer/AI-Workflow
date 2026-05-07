package com.workflow.api;

import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigValidator;
import com.workflow.config.ValidationError;
import com.workflow.config.ValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@code POST /api/pipelines/validate-body} (PR-3, 2026-05-07).
 * Exercises {@link RunController#validatePipelineBody} directly without a Spring context.
 */
@SuppressWarnings("unchecked")
class PipelineValidateBodyTest {

    private RunController controller(PipelineConfigValidator validator) {
        RunController c = new RunController();
        ReflectionTestUtils.setField(c, "pipelineConfigValidator", validator);
        return c;
    }

    @Test
    void nullBody_returns400WithErrorKey() {
        PipelineConfigValidator validator = mock(PipelineConfigValidator.class);
        RunController c = controller(validator);

        ResponseEntity<?> resp = c.validatePipelineBody(null);

        assertEquals(400, resp.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("error"));
        verifyNoInteractions(validator);
    }

    @Test
    void validConfig_returns200WithValidTrue() {
        PipelineConfigValidator validator = mock(PipelineConfigValidator.class);
        PipelineConfig config = new PipelineConfig();
        when(validator.validate(config)).thenReturn(ValidationResult.ok());

        ResponseEntity<?> resp = controller(validator).validatePipelineBody(config);

        assertEquals(200, resp.getStatusCode().value());
        ValidationResult result = (ValidationResult) resp.getBody();
        assertNotNull(result);
        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void configWithErrors_returns200WithValidFalse() {
        PipelineConfigValidator validator = mock(PipelineConfigValidator.class);
        PipelineConfig config = new PipelineConfig();
        ValidationResult errResult = ValidationResult.of(List.of(
            ValidationError.error("UNKNOWN_BLOCK", "Block type 'bogus' not registered",
                "pipeline[0].block", "block-1")
        ));
        when(validator.validate(config)).thenReturn(errResult);

        ResponseEntity<?> resp = controller(validator).validatePipelineBody(config);

        assertEquals(200, resp.getStatusCode().value());
        ValidationResult result = (ValidationResult) resp.getBody();
        assertNotNull(result);
        assertFalse(result.valid());
        assertEquals(1, result.errors().size());
        assertEquals("UNKNOWN_BLOCK", result.errors().get(0).code());
    }
}
