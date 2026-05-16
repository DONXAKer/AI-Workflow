package com.workflow.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectStackScannerTest {

    private ProjectStackScanner scanner;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        scanner = new ProjectStackScanner(objectMapper);
    }

    @Test
    void scanProject_EmptyDirectory_ReturnsEmptyArray(@TempDir Path tempDir) throws IOException {
        String result = scanner.scanProject(tempDir.toString());
        
        ProjectStackScanner.TechItem[] techItems = objectMapper.readValue(result, ProjectStackScanner.TechItem[].class);
        
        assertEquals(0, techItems.length);
    }

    @Test
    void scanProject_JavaProject_DetectsJavaAndMaven(@TempDir Path tempDir) throws IOException {
        // Create pom.xml
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <properties>
                    <java.version>17</java.version>
                    <maven.compiler.source>17</maven.compiler.source>
                    <maven.compiler.target>17</maven.compiler.target>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter</artifactId>
                        <version>3.1.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;
        Files.writeString(tempDir.resolve("pom.xml"), pomContent);
        
        // Create Java source file
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/App.java"), "public class App {}");
        
        String result = scanner.scanProject(tempDir.toString());
        
        ProjectStackScanner.TechItem[] techItems = objectMapper.readValue(result, ProjectStackScanner.TechItem[].class);
        
        assertTrue(java.util.Arrays.stream(techItems).anyMatch(item -> "java".equals(item.getName())));
        assertTrue(java.util.Arrays.stream(techItems).anyMatch(item -> "maven".equals(item.getName())));
        assertTrue(java.util.Arrays.stream(techItems).anyMatch(item -> "spring-boot".equals(item.getName())));
        
        // Check version detection
        ProjectStackScanner.TechItem javaItem = java.util.Arrays.stream(techItems)
            .filter(item -> "java".equals(item.getName()))
            .findFirst()
            .orElse(null);
        assertNotNull(javaItem);
        assertEquals("17", javaItem.getVersion());
    }

    @Test
    void scanProject_TypeScriptProject_DetectsTypeScriptAndReact(@TempDir Path tempDir) throws IOException {
        // Create package.json
        String packageContent = """
            {
              "name": "test-project",
              "version": "1.0.0",
              "dependencies": {
                "react": "^18.2.0",
                "typescript": "^5.0.0"
              },
              "devDependencies": {
                "@types/react": "^18.2.0",
                "@types/node": "^20.0.0",
                "vite": "^4.0.0",
                "eslint": "^8.0.0",
                "prettier": "^3.0.0"
              }
            }
            """;
        Files.writeString(tempDir.resolve("package.json"), packageContent);
        
        // Create tsconfig.json
        Files.writeString(tempDir.resolve("tsconfig.json"), """
            {
              "compilerOptions": {
                "target": "es2020",
                "module": "esnext"
              }
            }
            """);
        
        // Create TypeScript files
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/index.tsx"), "import React from 'react';");
        
        String result = scanner.scanProject(tempDir.toString());
        
        Set<ProjectStackScanner.TechItem> techItems = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(Set.class, ProjectStackScanner.TechItem.class));
        
        assertTrue(techItems.stream().anyMatch(item -> "typescript".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "react".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "vite".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "eslint".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "prettier".equals(item.getName())));
    }

    @Test
    void scanProject_Monorepo_DetectsMonorepoStructure(@TempDir Path tempDir) throws IOException {
        // Create frontend directory with React
        Path frontendDir = tempDir.resolve("frontend");
        Files.createDirectories(frontendDir);
        
        String frontendPackage = """
            {
              "name": "frontend",
              "dependencies": {
                "react": "^18.2.0",
                "typescript": "^5.0.0"
              }
            }
            """;
        Files.writeString(frontendDir.resolve("package.json"), frontendPackage);
        
        // Create backend directory with Spring Boot
        Path backendDir = tempDir.resolve("backend");
        Files.createDirectories(backendDir.resolve("src/main/java"));
        
        String backendPom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter</artifactId>
                        <version>3.1.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;
        Files.writeString(backendDir.resolve("pom.xml"), backendPom);
        
        String result = scanner.scanProject(tempDir.toString());
        
        Set<ProjectStackScanner.TechItem> techItems = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(Set.class, ProjectStackScanner.TechItem.class));
        
        assertTrue(techItems.stream().anyMatch(item -> "monorepo".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "react".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "typescript".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "spring-boot".equals(item.getName())));
    }

    @Test
    void scanProject_GradleProject_DetectsGradle(@TempDir Path tempDir) throws IOException {
        // Create build.gradle
        String gradleContent = """
            plugins {
                id 'org.springframework.boot' version '3.1.0'
                id 'java'
            }
            
            sourceCompatibility = '17'
            
            repositories {
                mavenCentral()
            }
            
            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter'
            }
            """;
        Files.writeString(tempDir.resolve("build.gradle"), gradleContent);
        
        // Create settings.gradle
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'test-project'");
        
        String result = scanner.scanProject(tempDir.toString());
        
        Set<ProjectStackScanner.TechItem> techItems = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(Set.class, ProjectStackScanner.TechItem.class));
        
        assertTrue(techItems.stream().anyMatch(item -> "java".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "gradle".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "spring-boot".equals(item.getName())));
    }

    @Test
    void scanProject_PythonProject_DetectsPython(@TempDir Path tempDir) throws IOException {
        // Create requirements.txt
        String requirementsContent = """
            flask==2.3.0
            requests==2.28.0
            pytest==7.4.0
            """;
        Files.writeString(tempDir.resolve("requirements.txt"), requirementsContent);
        
        // Create Python files
        Files.writeString(tempDir.resolve("app.py"), "import flask");
        Files.writeString(tempDir.resolve("test_app.py"), "import pytest");
        
        String result = scanner.scanProject(tempDir.toString());
        
        Set<ProjectStackScanner.TechItem> techItems = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(Set.class, ProjectStackScanner.TechItem.class));
        
        assertTrue(techItems.stream().anyMatch(item -> "python".equals(item.getName())));
    }

    @Test
    void scanProject_NonExistentDirectory_ReturnsEmptyArray() {
        String result = scanner.scanProject("/non/existent/directory");
        assertEquals("[]", result);
    }

    @Test
    void scanProject_NullDirectory_ReturnsEmptyArray() {
        String result = scanner.scanProject(null);
        assertEquals("[]", result);
    }

    @Test
    void scanProject_EmptyDirectory_ReturnsEmptyArrayString(@TempDir Path tempDir) {
        String result = scanner.scanProject(tempDir.toString());
        assertEquals("[]", result);
    }

    @Test
    void scanProject_DockerProject_DetectsDocker(@TempDir Path tempDir) throws IOException {
        // Create Dockerfile
        String dockerfileContent = """
            FROM openjdk:17-jdk-slim
            WORKDIR /app
            COPY . .
            RUN ./gradlew build
            CMD ["java", "-jar", "build/libs/app.jar"]
            """;
        Files.writeString(tempDir.resolve("Dockerfile"), dockerfileContent);
        
        // Create docker-compose.yml
        String composeContent = """
            version: '3.8'
            services:
              app:
                build: .
                ports:
                  - "8080:8080"
              db:
                image: postgres:15
            """;
        Files.writeString(tempDir.resolve("docker-compose.yml"), composeContent);
        
        String result = scanner.scanProject(tempDir.toString());
        
        Set<ProjectStackScanner.TechItem> techItems = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(Set.class, ProjectStackScanner.TechItem.class));
        
        assertTrue(techItems.stream().anyMatch(item -> "docker".equals(item.getName())));
    }

    @Test
    void scanProject_DatabaseProject_DetectsPostgreSQL(@TempDir Path tempDir) throws IOException {
        // Create SQL migration files
        Path migrationsDir = tempDir.resolve("db/migration");
        Files.createDirectories(migrationsDir);
        
        Files.writeString(migrationsDir.resolve("V1__Create_schema.sql"), """
            CREATE TABLE users (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            );
            """);
        
        String result = scanner.scanProject(tempDir.toString());
        
        Set<ProjectStackScanner.TechItem> techItems = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(Set.class, ProjectStackScanner.TechItem.class));
        
        assertTrue(techItems.stream().anyMatch(item -> "postgresql".equals(item.getName())));
    }

    @Test
    void scanProject_PomXmlContext_DetectsContextFromPom(@TempDir Path tempDir) throws IOException {
        // Create pom.xml with rich context
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <properties>
                    <java.version>21</java.version>
                    <maven.compiler.source>21</maven.compiler.source>
                    <maven.compiler.target>21</maven.compiler.target>
                    <spring-boot.version>3.2.0</spring-boot.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                        <version>3.2.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-data-jpa</artifactId>
                        <version>3.2.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.postgresql</groupId>
                        <artifactId>postgresql</artifactId>
                        <version>42.7.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.10.0</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                            <version>3.2.0</version>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;
        Files.writeString(tempDir.resolve("pom.xml"), pomContent);
        
        String result = scanner.scanProject(tempDir.toString());
        
        Set<ProjectStackScanner.TechItem> techItems = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(Set.class, ProjectStackScanner.TechItem.class));
        
        // Check basic technologies
        assertTrue(techItems.stream().anyMatch(item -> "java".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "maven".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "spring-boot".equals(item.getName())));
        
        // Check version detection from context
        ProjectStackScanner.TechItem javaItem = techItems.stream()
            .filter(item -> "java".equals(item.getName()))
            .findFirst()
            .orElse(null);
        assertNotNull(javaItem);
        assertEquals("21", javaItem.getVersion());
        
        ProjectStackScanner.TechItem springBootItem = techItems.stream()
            .filter(item -> "spring-boot".equals(item.getName()))
            .findFirst()
            .orElse(null);
        assertNotNull(springBootItem);
        assertEquals("3.2.0", springBootItem.getVersion());
    }

    @Test
    void scanProject_PomXmlWithoutVersion_DetectsJavaWithoutVersion(@TempDir Path tempDir) throws IOException {
        // Create pom.xml without explicit version
        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;
        Files.writeString(tempDir.resolve("pom.xml"), pomContent);
        
        String result = scanner.scanProject(tempDir.toString());
        
        Set<ProjectStackScanner.TechItem> techItems = objectMapper.readValue(result, 
            objectMapper.getTypeFactory().constructCollectionType(Set.class, ProjectStackScanner.TechItem.class));
        
        assertTrue(techItems.stream().anyMatch(item -> "java".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "maven".equals(item.getName())));
        assertTrue(techItems.stream().anyMatch(item -> "spring-boot".equals(item.getName())));
        
        // Java should be detected but without version
        ProjectStackScanner.TechItem javaItem = techItems.stream()
            .filter(item -> "java".equals(item.getName()))
            .findFirst()
            .orElse(null);
        assertNotNull(javaItem);
        assertNull(javaItem.getVersion());
    }
}