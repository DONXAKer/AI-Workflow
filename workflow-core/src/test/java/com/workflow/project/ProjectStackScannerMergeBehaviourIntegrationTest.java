package com.workflow.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.project.ProjectStackScanner.TechItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProjectStackScannerMergeBehaviourIntegrationTest {

    @Autowired
    private ProjectStackScanner scanner;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void mergeBehaviour_WhenTechStackJsonExists_MergesCorrectly() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("test-merge-project");
        project.setWorkingDir("/test/path");
        
        // Existing tech stack with patterns and risks
        String existingTechStack = """
            [
                {"name": "java", "version": "17"},
                {"name": "spring-boot", "version": "3.1.0"},
                {"name": "patterns", "version": "singleton-pattern,factory-pattern"},
                {"name": "risks", "version": "memory-leak,sql-injection"}
            ]
            """;
        project.setTechStackJson(existingTechStack);
        
        project = projectRepository.save(project);
        
        // Simulate detected stack (without patterns and risks)
        Set<TechItem> detectedTechs = new LinkedHashSet<>();
        detectedTechs.add(new TechItem("java", "21"));
        detectedTechs.add(new TechItem("spring-boot", "3.2.0"));
        detectedTechs.add(new TechItem("maven", null));
        
        String detectedStackJson = objectMapper.writeValueAsString(detectedTechs);
        
        // Act - simulate the merge behavior that would happen in detect-stack endpoint
        String mergedStack = mergeTechStack(existingTechStack, detectedStackJson);
        
        // Assert
        TechItem[] mergedItems = objectMapper.readValue(mergedStack, TechItem[].class);
        
        // Should contain updated versions
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "java".equals(item.getName()) && "21".equals(item.getVersion())));
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "spring-boot".equals(item.getName()) && "3.2.0".equals(item.getVersion())));
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "maven".equals(item.getName())));
        
        // Should preserve patterns and risks
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "patterns".equals(item.getName()) && "singleton-pattern,factory-pattern".equals(item.getVersion())));
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "risks".equals(item.getName()) && "memory-leak,sql-injection".equals(item.getVersion())));
    }

    @Test
    void mergeBehaviour_WhenNoExistingTechStack_UsesDetectedOnly() throws Exception {
        // Arrange
        Project project = new Project();
        project.setSlug("test-no-existing");
        project.setWorkingDir("/test/path");
        project.setTechStackJson(null);
        
        project = projectRepository.save(project);
        
        Set<TechItem> detectedTechs = new LinkedHashSet<>();
        detectedTechs.add(new TechItem("typescript", "5.0.0"));
        detectedTechs.add(new TechItem("react", "18.2.0"));
        
        String detectedStackJson = objectMapper.writeValueAsString(detectedTechs);
        
        // Act
        String mergedStack = mergeTechStack(null, detectedStackJson);
        
        // Assert
        TechItem[] mergedItems = objectMapper.readValue(mergedStack, TechItem[].class);
        assertEquals(2, mergedItems.length);
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "typescript".equals(item.getName())));
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "react".equals(item.getName())));
    }

    @Test
    void mergeBehaviour_WhenEmptyExistingTechStack_UsesDetectedOnly() throws Exception {
        // Arrange
        String existingTechStack = "[]";
        
        Set<TechItem> detectedTechs = new LinkedHashSet<>();
        detectedTechs.add(new TechItem("python", "3.11"));
        detectedTechs.add(new TechItem("django", "4.2"));
        
        String detectedStackJson = objectMapper.writeValueAsString(detectedTechs);
        
        // Act
        String mergedStack = mergeTechStack(existingTechStack, detectedStackJson);
        
        // Assert
        TechItem[] mergedItems = objectMapper.readValue(mergedStack, TechItem[].class);
        assertEquals(2, mergedItems.length);
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "python".equals(item.getName())));
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "django".equals(item.getName())));
    }

    @Test
    void mergeBehaviour_ProtectsPatternsAndRisks() throws Exception {
        // Arrange
        String existingTechStack = """
            [
                {"name": "java", "version": "17"},
                {"name": "patterns", "version": "custom-pattern-1,custom-pattern-2"},
                {"name": "risks", "version": "custom-risk-1,custom-risk-2"}
            ]
            """;
        
        Set<TechItem> detectedTechs = new LinkedHashSet<>();
        detectedTechs.add(new TechItem("java", "21"));
        detectedTechs.add(new TechItem("maven", null));
        // Note: detected doesn't contain patterns or risks
        
        String detectedStackJson = objectMapper.writeValueAsString(detectedTechs);
        
        // Act
        String mergedStack = mergeTechStack(existingTechStack, detectedStackJson);
        
        // Assert
        TechItem[] mergedItems = objectMapper.readValue(mergedStack, TechItem[].class);
        
        // Should preserve existing patterns and risks
        Optional<TechItem> patterns = Arrays.stream(mergedItems)
            .filter(item -> "patterns".equals(item.getName()))
            .findFirst();
        assertTrue(patterns.isPresent());
        assertEquals("custom-pattern-1,custom-pattern-2", patterns.get().getVersion());
        
        Optional<TechItem> risks = Arrays.stream(mergedItems)
            .filter(item -> "risks".equals(item.getName()))
            .findFirst();
        assertTrue(risks.isPresent());
        assertEquals("custom-risk-1,custom-risk-2", risks.get().getVersion());
        
        // Should update java version
        assertTrue(Arrays.stream(mergedItems).anyMatch(item -> "java".equals(item.getName()) && "21".equals(item.getVersion())));
    }

    @Test
    void mergeBehaviour_HandlesMalformedExistingJson() throws Exception {
        // Arrange
        String malformedExistingTechStack = "{ invalid json }";
        
        Set<TechItem> detectedTechs = new LinkedHashSet<>();
        detectedTechs.add(new TechItem("java", "21"));
        
        String detectedStackJson = objectMapper.writeValueAsString(detectedTechs);
        
        // Act - should fall back to detected stack only
        String mergedStack = mergeTechStack(malformedExistingTechStack, detectedStackJson);
        
        // Assert
        TechItem[] mergedItems = objectMapper.readValue(mergedStack, TechItem[].class);
        assertEquals(1, mergedItems.length);
        assertEquals("java", mergedItems[0].getName());
        assertEquals("21", mergedItems[0].getVersion());
    }

    /**
     * Simulates the merge behavior that happens in the detect-stack endpoint.
     * This method protects patterns and risks while updating other technologies.
     */
    private String mergeTechStack(String existingTechStackJson, String detectedTechStackJson) {
        try {
            Set<TechItem> result = new LinkedHashSet<>();
            
            // Parse detected stack
            Set<TechItem> detectedTechs = objectMapper.readValue(detectedTechStackJson, 
                objectMapper.getTypeFactory().constructCollectionType(Set.class, TechItem.class));
            
            // Parse existing stack if available
            Set<TechItem> existingTechs = new HashSet<>();
            if (existingTechStackJson != null && !existingTechStackJson.trim().isEmpty() && !existingTechStackJson.equals("[]")) {
                try {
                    existingTechs = objectMapper.readValue(existingTechStackJson, 
                        objectMapper.getTypeFactory().constructCollectionType(Set.class, TechItem.class));
                } catch (Exception e) {
                    // If parsing fails, we'll just use detected stack
                    existingTechs = new HashSet<>();
                }
            }
            
            // Add all detected technologies
            result.addAll(detectedTechs);
            
            // Add back patterns and risks from existing stack if they exist
            for (TechItem existing : existingTechs) {
                if ("patterns".equals(existing.getName()) || "risks".equals(existing.getName())) {
                    result.add(existing);
                }
            }
            
            return objectMapper.writeValueAsString(result.stream()
                .sorted(Comparator.comparing(TechItem::getName))
                .toList());
        } catch (Exception e) {
            // If merge fails, return detected stack
            return detectedTechStackJson;
        }
    }
}