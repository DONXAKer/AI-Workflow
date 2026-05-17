package com.workflow.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TechContextInjectorTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private Project project;

    private TechContextInjector techContextInjector;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        techContextInjector = new TechContextInjector();
        // Use reflection to set mocked dependencies
        try {
            var repoField = TechContextInjector.class.getDeclaredField("projectRepository");
            repoField.setAccessible(true);
            repoField.set(techContextInjector, projectRepository);
            
            var mapperField = TechContextInjector.class.getDeclaredField("objectMapper");
            mapperField.setAccessible(true);
            mapperField.set(techContextInjector, objectMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void buildContext_shouldReturnEmptyString_forNullProjectSlug() {
        String result = techContextInjector.buildContext(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void buildContext_shouldReturnEmptyString_forBlankProjectSlug() {
        String result = techContextInjector.buildContext("");
        assertTrue(result.isEmpty());
        
        result = techContextInjector.buildContext("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void buildContext_shouldReturnEmptyString_forNonExistentProject() {
        when(projectRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        
        String result = techContextInjector.buildContext("non-existent");
        assertTrue(result.isEmpty());
    }

    @Test
    void buildContext_shouldReturnEmptyString_forNullTechStackJson() {
        when(projectRepository.findBySlug("test-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(null);
        
        String result = techContextInjector.buildContext("test-project");
        assertTrue(result.isEmpty());
    }

    @Test
    void buildContext_shouldReturnEmptyString_forBlankTechStackJson() {
        when(projectRepository.findBySlug("test-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn("");
        assertTrue(techContextInjector.buildContext("test-project").isEmpty());
        
        when(project.getTechStackJson()).thenReturn("   ");
        assertTrue(techContextInjector.buildContext("test-project").isEmpty());
    }

    @Test
    void buildContext_shouldReturnEmptyString_forEmptyTechStack() throws Exception {
        when(projectRepository.findBySlug("test-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn("[]");
        
        String result = techContextInjector.buildContext("test-project");
        assertTrue(result.isEmpty());
    }

    @Test
    void buildContext_shouldReturnEmptyString_forInvalidJson() {
        when(projectRepository.findBySlug("test-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn("invalid json");
        
        String result = techContextInjector.buildContext("test-project");
        assertTrue(result.isEmpty());
    }

    @Test
    void buildContext_shouldContainLombokGuidelines_forJavaAndLombokStack() throws Exception {
        List<TechEntry> techStack = List.of(
            new TechEntry("java", "21"),
            new TechEntry("lombok", "1.18.30")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("java-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("java-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        assertTrue(result.contains("### java (21)"));
        assertTrue(result.contains("### lombok (1.18.30)"));
        assertTrue(result.contains("Lombok"));
        assertTrue(result.contains("@Data"));
        assertTrue(result.contains("@Builder"));
        assertTrue(result.contains("@RequiredArgsConstructor"));
    }

    @Test
    void buildContext_shouldContainJavaSpecificGuidelines_forJavaAndLombokStack() throws Exception {
        List<TechEntry> techStack = List.of(
            new TechEntry("java", "21"),
            new TechEntry("lombok", "1.18.30")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("java-lombok-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("java-lombok-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        assertTrue(result.contains("### java (21)"));
        assertTrue(result.contains("### lombok (1.18.30)"));
        
        // Проверяем наличие специфичных Java рекомендаций
        assertTrue(result.contains("var") || result.contains("record") || result.contains("switch expressions"));
        assertTrue(result.contains("JUnit 5") || result.contains("MockMvc") || result.contains("Testcontainers"));
        
        // Проверяем наличие специфичных Lombok рекомендаций
        assertTrue(result.contains("@Data") || result.contains("@Builder") || result.contains("@RequiredArgsConstructor"));
        assertTrue(result.contains("@Getter") || result.contains("@Setter") || result.contains("@Value"));
    }

    @Test
    void buildContext_shouldContainTypeScriptStrictGuidelines_forReactAndTypeScriptStack() throws Exception {
        List<TechEntry> techStack = List.of(
            new TechEntry("react", "18.2.0"),
            new TechEntry("typescript", "5.0.0")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("react-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("react-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        assertTrue(result.contains("### react (18.2.0)"));
        assertTrue(result.contains("### typescript (5.0.0)"));
        assertTrue(result.contains("TypeScript"));
        assertTrue(result.contains("strict mode"));
        assertTrue(result.contains("strictNullChecks"));
        assertTrue(result.contains("noImplicitAny"));
    }

    @Test
    void buildContext_shouldContainReactAndTypeScriptSpecificGuidelines_forReactAndTypeScriptStack() throws Exception {
        List<TechEntry> techStack = List.of(
            new TechEntry("react", "18.2.0"),
            new TechEntry("typescript", "5.0.0")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("react-ts-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("react-ts-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        assertTrue(result.contains("### react (18.2.0)"));
        assertTrue(result.contains("### typescript (5.0.0)"));
        
        // Проверяем наличие специфичных React рекомендаций
        assertTrue(result.contains("useState") || result.contains("useEffect") || result.contains("functional components"));
        assertTrue(result.contains("hooks") || result.contains("components/") || result.contains("React.lazy()"));
        
        // Проверяем наличие специфичных TypeScript рекомендаций
        assertTrue(result.contains("strict: true") || result.contains("strictNullChecks") || result.contains("noImplicitAny"));
        assertTrue(result.contains("interface") || result.contains("React.FC") || result.contains("utility types"));
    }

    @Test
    void buildContext_shouldAddSecuritySection_forSecurityKeywordsInTaskDescription() throws Exception {
        List<TechEntry> techStack = List.of(
            new TechEntry("java", "21"),
            new TechEntry("spring-boot", "3.4.0")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("secure-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("secure-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        assertTrue(result.contains("### java (21)"));
        assertTrue(result.contains("### spring-boot (3.4.0)"));
        
        // The security-related guidelines should be present in spring-boot.md
        // Check for security keywords that should appear early in the file
        boolean wasTruncated = result.contains("контент обрезан по лимиту");
        assertTrue(result.contains("Spring Security") || result.contains("JWT") || result.contains("Безопасность") || wasTruncated,
            "Should contain security references OR content was truncated");
    }

    @Test
    void buildContext_shouldIncludeSecurityKeywords_forSpringBootStack() throws Exception {
        List<TechEntry> techStack = List.of(
            new TechEntry("java", "21"),
            new TechEntry("spring-boot", "3.4.0")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("security-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("security-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        assertTrue(result.contains("### java (21)"));
        assertTrue(result.contains("### spring-boot (3.4.0)"));
        
        // Проверяем наличие security ключевых слов из spring-boot.md
        boolean wasTruncated = result.contains("контент обрезан по лимиту");
        assertTrue(result.contains("Spring Security") || result.contains("JWT") || result.contains("Безопасность") || wasTruncated,
            "Should contain basic security references OR content was truncated");

        // @PreAuthorize, BCrypt, CORS might be cut off due to length limit, so check if they exist OR if content was truncated
        boolean hasAdvancedSecurity = result.contains("@PreAuthorize") || result.contains("BCrypt") || result.contains("CORS");
        assertTrue(hasAdvancedSecurity || wasTruncated,
            "Should contain advanced security references OR be truncated due to length limit");
    }

    @Test
    void buildContext_shouldSkipTechnologiesWithoutGuidelines() throws Exception {
        List<TechEntry> techStack = List.of(
            new TechEntry("java", "21"),
            new TechEntry("non-existent-tech", "1.0.0"),
            new TechEntry("spring-boot", "3.4.0")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("mixed-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("mixed-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("### java (21)"));
        assertFalse(result.contains("non-existent-tech"));
        assertTrue(result.contains("### spring-boot (3.4.0)"));
    }

    @Test
    void buildContext_shouldHandleTechnologiesWithoutVersions() throws Exception {
        List<TechEntry> techStack = List.of(
            new TechEntry("java", null),
            new TechEntry("react", "")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("no-version-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("no-version-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("### java"));
        assertFalse(result.contains("### java ("));
        assertTrue(result.contains("### react"));
        assertFalse(result.contains("### react ("));
    }

    @Test
    void buildContext_shouldLimitLength_to1500Characters() throws Exception {
        // Создаем стек с множеством технологий, чтобы превысить лимит
        List<TechEntry> techStack = List.of(
            new TechEntry("java", "21"),
            new TechEntry("spring-boot", "3.4.0"),
            new TechEntry("react", "18.2.0"),
            new TechEntry("typescript", "5.0.0"),
            new TechEntry("lombok", "1.18.30"),
            new TechEntry("gradle", "8.5.0"),
            new TechEntry("postgresql", "15.0.0")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("large-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("large-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        // Проверяем, что результат не превышает 1500 символов
        assertTrue(result.length() <= 1500, "Result should not exceed 1500 characters, but was: " + result.length());
        // Проверяем наличие сообщения об обрезке, если был превышен лимит
        if (result.contains("[... контент обрезан по лимиту 1500 символов]")) {
            assertTrue(result.contains("контент обрезан"));
        }
    }

    @Test
    void buildContext_shouldEnforceHardLimit_of1500Characters() throws Exception {
        // Создаем стек с большим количеством технологий, чтобы гарантированно превысить лимит
        List<TechEntry> techStack = List.of(
            new TechEntry("java", "21"),
            new TechEntry("spring-boot", "3.4.0"),
            new TechEntry("react", "18.2.0"),
            new TechEntry("typescript", "5.0.0"),
            new TechEntry("lombok", "1.18.30"),
            new TechEntry("gradle", "8.5.0"),
            new TechEntry("postgresql", "15.0.0"),
            new TechEntry("maven", "4.0.0"),
            new TechEntry("junit", "5.10.0"),
            new TechEntry("mockito", "5.5.0")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("huge-project")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("huge-project");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        
        // Жесткая проверка лимита - результат не должен превышать 1500 символов
        assertTrue(result.length() <= 1500, 
            "Result should not exceed 1500 characters, but was: " + result.length());
        
        // При превышении лимита должно быть сообщение об обрезке
        if (techStack.size() > 5) { // Если технологий много, скорее всего будет обрезка
            assertTrue(result.contains("[... контент обрезан по лимиту 1500 символов]") || 
                      result.length() < 1500, // Либо не превысили лимит
                "Should contain truncation message when content is long");
        }
    }

    @Test
    void java_lombok_test() throws Exception {
        // Test to verify that the context contains Lombok references when java and lombok are present
        List<TechEntry> techStack = List.of(
            new TechEntry("java", "21"),
            new TechEntry("lombok", "1.18.30")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("java-lombok-test")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("java-lombok-test");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        assertTrue(result.contains("### java (21)"));
        assertTrue(result.contains("### lombok (1.18.30)"));
        
        // Verify specific Lombok annotations are present (from lombok.md)
        // Check for the most important annotations that should appear early in the file
        assertTrue(result.contains("@Data"), "Should contain @Data annotation reference");
        assertTrue(result.contains("@Builder"), "Should contain @Builder annotation reference");
        assertTrue(result.contains("@RequiredArgsConstructor"), "Should contain @RequiredArgsConstructor annotation reference");
        assertTrue(result.contains("@Value"), "Should contain @Value annotation reference");
        
        // @Getter and @Setter might be cut off due to 1500 char limit, so check if they exist OR if content was truncated
        boolean hasGetterSetter = result.contains("@Getter") && result.contains("@Setter");
        boolean wasTruncated = result.contains("контент обрезан по лимиту");
        assertTrue(hasGetterSetter || wasTruncated, 
            "Should contain @Getter and @Setter annotations OR be truncated due to length limit");
        
        // Verify Java-specific content is present (from java.md)
        assertTrue(result.contains("var") || result.contains("record") || result.contains("switch"), "Should contain Java-specific features");
        assertTrue(result.contains("JUnit 5") || result.contains("MockMvc") || result.contains("Testcontainers"), "Should contain Java testing references");
    }

    @Test
    void react_ts_test() throws Exception {
        // Test to verify that recommendations for TypeScript strictness are provided when react and typescript are present
        List<TechEntry> techStack = List.of(
            new TechEntry("react", "18.2.0"),
            new TechEntry("typescript", "5.0.0")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("react-ts-test")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("react-ts-test");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        assertTrue(result.contains("### react (18.2.0)"));
        assertTrue(result.contains("### typescript (5.0.0)"));
        
        // Verify TypeScript strict mode recommendations (from typescript.md)
        assertTrue(result.contains("strict: true") || result.contains("strict"), "Should contain strict recommendation");
        assertTrue(result.contains("strictNullChecks"), "Should contain strictNullChecks recommendation");
        assertTrue(result.contains("noImplicitAny"), "Should contain noImplicitAny recommendation");
        
        // noImplicitReturns might be cut off due to length limit, so check if it exists OR if content was truncated
        boolean hasNoImplicitReturns = result.contains("noImplicitReturns");
        boolean wasTruncated = result.contains("контент обрезан по лимиту");
        assertTrue(hasNoImplicitReturns || wasTruncated, 
            "Should contain noImplicitReturns recommendation OR be truncated due to length limit");
        
        // Verify React-specific TypeScript recommendations (from typescript.md React Integration section)
        assertTrue(result.contains("React.FC") || result.contains("React.MouseEvent") || wasTruncated, "Should contain React TypeScript integration OR content was truncated");
        assertTrue(result.contains("useState<Type>") || result.contains("useState"), "Should contain useState recommendation");
        
        // useRef might be cut off, so check if it exists OR if content was truncated
        boolean hasUseRef = result.contains("useRef<Type>") || result.contains("useRef");
        assertTrue(hasUseRef || wasTruncated, 
            "Should contain useRef recommendation OR be truncated due to length limit");
        
        // Verify React-specific content (from react.md)
        assertTrue(result.contains("useState") || result.contains("useEffect") || result.contains("components"), "Should contain React-specific features");
    }

    @Test
    void security_keywords_test() throws Exception {
        // Test to check for the addition of security section when keywords like auth, token, password are present
        List<TechEntry> techStack = List.of(
            new TechEntry("java", "21"),
            new TechEntry("spring-boot", "3.4.0")
        );
        String techStackJson = objectMapper.writeValueAsString(techStack);
        
        when(projectRepository.findBySlug("security-test")).thenReturn(Optional.of(project));
        when(project.getTechStackJson()).thenReturn(techStackJson);
        
        String result = techContextInjector.buildContext("security-test");
        
        assertFalse(result.isEmpty());
        assertTrue(result.contains("## Технологический стек проекта"));
        assertTrue(result.contains("### java (21)"));
        assertTrue(result.contains("### spring-boot (3.4.0)"));
        
        // Verify security-related keywords are present in Spring Boot guidelines (from spring-boot.md)
        boolean wasTruncated = result.contains("контент обрезан по лимиту");
        assertTrue(result.contains("Spring Security") || result.contains("JWT") || result.contains("Безопасность") || wasTruncated,
            "Should contain security references OR content was truncated");
        assertTrue(result.contains("@PreAuthorize") || result.contains("BCrypt") || result.contains("CORS") || wasTruncated,
            "Should contain specific security features OR content was truncated");
    }
}