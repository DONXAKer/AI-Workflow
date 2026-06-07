package com.workflow.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
class ProjectStackScannerIntegrationTest {

    private ProjectStackScanner scanner;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scanner = new ProjectStackScanner(objectMapper);
    }

    @Test
    void scanProject_MergeBehaviour_WhenTechStackJsonNotEmpty() throws IOException {
        // Создаем проект с существующим techStack
        Path projectDir = tempDir.resolve("test-project");
        Files.createDirectories(projectDir);
        
        // Создаем файлы проекта
        Files.writeString(projectDir.resolve("pom.xml"), """
            <?xml version="1.0"?>
            <project>
                <properties>
                    <java.version>17</java.version>
                </properties>
            </project>
            """);
        
        // Сканируем первый раз
        String firstScan = scanner.scanProject(projectDir.toString());
        List<ProjectStackScanner.TechItem> firstItems = objectMapper.readValue(firstScan, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, ProjectStackScanner.TechItem.class));
        
        // Добавляем новые файлы
        Path frontendDir = projectDir.resolve("frontend");
        Files.createDirectories(frontendDir);
        Files.writeString(frontendDir.resolve("package.json"), """
            {
                "dependencies": {
                    "react": "^18.0.0",
                    "typescript": "^5.0.0"
                }
            }
            """);
        
        // Сканируем второй раз
        String secondScan = scanner.scanProject(projectDir.toString());
        List<ProjectStackScanner.TechItem> secondItems = objectMapper.readValue(secondScan, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, ProjectStackScanner.TechItem.class));
        
        // Второй скан должен содержать больше технологий
        assertTrue(secondItems.size() > firstItems.size());
        
        // Проверяем наличие всех технологий
        assertTrue(secondItems.stream().anyMatch(item -> "java".equals(item.getName())));
        assertTrue(secondItems.stream().anyMatch(item -> "maven".equals(item.getName())));
        assertTrue(secondItems.stream().anyMatch(item -> "react".equals(item.getName())));
        assertTrue(secondItems.stream().anyMatch(item -> "typescript".equals(item.getName())));
    }

    @Test
    void scanProject_VerifyContextFromPomXml() throws IOException {
        // Создаем Maven проект с Spring Boot
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.1.5</version>
                </parent>
                
                <properties>
                    <java.version>17</java.version>
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                </properties>
                
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;
        Files.writeString(tempDir.resolve("pom.xml"), pomContent);
        
        String result = scanner.scanProject(tempDir.toString());
        List<ProjectStackScanner.TechItem> items = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, ProjectStackScanner.TechItem.class));
        
        // Проверяем наличие всех ожидаемых технологий
        assertTrue(items.stream().anyMatch(item -> "java".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "maven".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "spring-boot".equals(item.getName())));
        
        // Проверяем версию Java
        ProjectStackScanner.TechItem javaItem = items.stream()
            .filter(item -> "java".equals(item.getName()))
            .findFirst()
            .orElse(null);
        assertNotNull(javaItem);
        assertEquals("17", javaItem.getVersion());
    }

    @Test
    void scanProject_VerifyContextFromPackageJson() throws IOException {
        // Создаем Node.js проект с TypeScript
        String packageContent = """
            {
                "name": "test-frontend",
                "version": "1.0.0",
                "engines": {
                    "node": ">=18.0.0"
                },
                "dependencies": {
                    "react": "^18.2.0",
                    "react-dom": "^18.2.0"
                },
                "devDependencies": {
                    "typescript": "^5.2.0",
                    "@types/react": "^18.2.0",
                    "vite": "^4.5.0",
                    "eslint": "^8.50.0",
                    "prettier": "^3.0.0"
                }
            }
            """;
        Files.writeString(tempDir.resolve("package.json"), packageContent);
        
        String result = scanner.scanProject(tempDir.toString());
        List<ProjectStackScanner.TechItem> items = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, ProjectStackScanner.TechItem.class));
        
        // Проверяем наличие всех ожидаемых технологий
        assertTrue(items.stream().anyMatch(item -> "typescript".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "nodejs".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "react".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "vite".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "eslint".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "prettier".equals(item.getName())));
    }

    @Test
    void scanProject_MergingContextsFromBackendAndFrontend() throws IOException {
        // Создаем структуру с backend и frontend
        Path backendDir = tempDir.resolve("backend");
        Path frontendDir = tempDir.resolve("frontend");
        Files.createDirectories(backendDir);
        Files.createDirectories(frontendDir);
        
        // Backend: Spring Boot
        Files.writeString(backendDir.resolve("pom.xml"), """
            <?xml version="1.0"?>
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.1.0</version>
                </parent>
                <properties>
                    <java.version>17</java.version>
                </properties>
            </project>
            """);
        
        // Frontend: React + TypeScript
        Files.writeString(frontendDir.resolve("package.json"), """
            {
                "dependencies": {
                    "react": "^18.2.0",
                    "typescript": "^5.0.0"
                },
                "devDependencies": {
                    "vite": "^4.5.0"
                }
            }
            """);
        
        String result = scanner.scanProject(tempDir.toString());
        List<ProjectStackScanner.TechItem> items = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, ProjectStackScanner.TechItem.class));
        
        // Проверяем детект монорепозитория
        assertTrue(items.stream().anyMatch(item -> "monorepo".equals(item.getName())));
        
        // Проверяем backend технологии
        assertTrue(items.stream().anyMatch(item -> "java".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "spring-boot".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "maven".equals(item.getName())));
        
        // Проверяем frontend технологии
        assertTrue(items.stream().anyMatch(item -> "typescript".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "react".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "vite".equals(item.getName())));
    }

    @Test
    void scanProject_ProjectWithoutRecognizedFiles_ReturnsEmptyLanguages() throws IOException {
        // Создаем директорию с нераспознанными файлами
        Files.writeString(tempDir.resolve("unknown.xyz"), "some content");
        Files.writeString(tempDir.resolve("readme.txt"), "project readme");
        
        String result = scanner.scanProject(tempDir.toString());
        
        assertEquals("[]", result);
        
        // Парсим результат чтобы убедиться что это пустой массив
        try {
            List<ProjectStackScanner.TechItem> items = objectMapper.readValue(result, 
                objectMapper.getTypeFactory().constructCollectionType(List.class, ProjectStackScanner.TechItem.class));
            assertTrue(items.isEmpty());
        } catch (Exception e) {
            fail("Result should be valid JSON array: " + e.getMessage());
        }
    }

    @Test
    void scanProject_VerifyScannerDoesNotAlterPatternsAndRisks() throws IOException {
        // Создаем проект с базовыми технологиями
        Files.writeString(tempDir.resolve("pom.xml"), """
            <?xml version="1.0"?>
            <project>
                <properties>
                    <java.version>17</java.version>
                </properties>
            </project>
            """);
        
        // Сканируем несколько раз чтобы убедиться что результат консистентный
        String firstScan = scanner.scanProject(tempDir.toString());
        String secondScan = scanner.scanProject(tempDir.toString());
        String thirdScan = scanner.scanProject(tempDir.toString());
        
        // Все сканы должны возвращать одинаковый результат
        assertEquals(firstScan, secondScan);
        assertEquals(secondScan, thirdScan);
        
        // Результат должен быть валидным JSON
        assertDoesNotThrow(() -> {
            objectMapper.readValue(firstScan, 
                objectMapper.getTypeFactory().constructCollectionType(List.class, ProjectStackScanner.TechItem.class));
        });
    }

    @Test
    void scanProject_DeepDirectoryStructure_HandlesDepthCorrectly() throws IOException {
        // Создаем глубокую структуру директорий
        Path deepDir = tempDir;
        for (int i = 0; i < 15; i++) {
            deepDir = deepDir.resolve("level" + i);
            Files.createDirectories(deepDir);
        }
        
        // Создаем файл на глубине > 10 (должен быть проигнорирован)
        Files.writeString(deepDir.resolve("Main.java"), "public class Main { }");
        
        // Создаем файл на допустимой глубине
        Path shallowDir = tempDir;
        for (int i = 0; i < 5; i++) {
            shallowDir = shallowDir.resolve("level" + i);
            Files.createDirectories(shallowDir);
        }
        Files.writeString(shallowDir.resolve("App.java"), "public class App { }");
        
        String result = scanner.scanProject(tempDir.toString());
        List<ProjectStackScanner.TechItem> items = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, ProjectStackScanner.TechItem.class));
        
        // Java должен быть обнаружен (с неглубокого уровня)
        assertTrue(items.stream().anyMatch(item -> "java".equals(item.getName())));
    }

    @Test
    void scanProject_MixedTechnologies_DetectsAllCorrectly() throws IOException {
        // Создаем проект со смешанными технологиями
        Files.writeString(tempDir.resolve("pom.xml"), """
            <?xml version="1.0"?>
            <project>
                <properties>
                    <java.version>17</java.version>
                </properties>
            </project>
            """);
        
        Files.writeString(tempDir.resolve("package.json"), """
            {
                "dependencies": {
                    "express": "^4.18.0"
                }
            }
            """);
        
        Files.writeString(tempDir.resolve("requirements.txt"), "flask==2.0.0");
        Files.writeString(tempDir.resolve("Dockerfile"), "FROM node:18");
        
        String result = scanner.scanProject(tempDir.toString());
        List<ProjectStackScanner.TechItem> items = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, ProjectStackScanner.TechItem.class));
        
        // Проверяем наличие всех технологий
        assertTrue(items.stream().anyMatch(item -> "java".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "maven".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "nodejs".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "python".equals(item.getName())));
        assertTrue(items.stream().anyMatch(item -> "docker".equals(item.getName())));
    }
}