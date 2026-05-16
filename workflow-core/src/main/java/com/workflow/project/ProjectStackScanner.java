package com.workflow.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Сканирует файловую систему проекта для автодетекта технологий.
 * Анализирует файлы и директории для определения используемого стека технологий.
 */
@Component
public class ProjectStackScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectStackScanner.class);
    
    private final ObjectMapper objectMapper;

    // Паттерны для детекта технологий по именам файлов
    private static final Map<String, List<String>> FILE_PATTERNS = new HashMap<>();
    
    static {
        FILE_PATTERNS.put("java", List.of("*.java", "pom.xml", "build.gradle", "build.gradle.kts"));
        FILE_PATTERNS.put("javascript", List.of("*.js", "*.jsx", "package.json", "yarn.lock", "pnpm-lock.yaml"));
        FILE_PATTERNS.put("typescript", List.of("*.ts", "*.tsx", "tsconfig.json", "package.json"));
        FILE_PATTERNS.put("python", List.of("*.py", "requirements.txt", "setup.py", "pyproject.toml", "Pipfile"));
        FILE_PATTERNS.put("go", List.of("*.go", "go.mod", "go.sum"));
        FILE_PATTERNS.put("rust", List.of("*.rs", "Cargo.toml", "Cargo.lock"));
        FILE_PATTERNS.put("php", List.of("*.php", "composer.json", "composer.lock"));
        FILE_PATTERNS.put("ruby", List.of("*.rb", "Gemfile", "Gemfile.lock"));
        FILE_PATTERNS.put("csharp", List.of("*.cs", "*.csx", "*.csproj", "packages.config", "global.json"));
        FILE_PATTERNS.put("cpp", List.of("*.cpp", "*.cxx", "*.cc", "*.h", "*.hpp", "CMakeLists.txt", "Makefile"));
        FILE_PATTERNS.put("docker", List.of("Dockerfile", "docker-compose.yml", "docker-compose.yaml", ".dockerignore"));
        FILE_PATTERNS.put("kubernetes", List.of("*.yaml", "*.yml", "k8s/", "deploy/", "helm/"));
        FILE_PATTERNS.put("terraform", List.of("*.tf", "*.tfvars", "terraform.tfstate", ".terraform/"));
        FILE_PATTERNS.put("ansible", List.of("*.yml", "*.yaml", "playbook/", "roles/", "ansible.cfg"));
        FILE_PATTERNS.put("react", List.of("package.json", "*.jsx", "src/App.jsx", "public/index.html"));
        FILE_PATTERNS.put("vue", List.of("*.vue", "vue.config.js", "nuxt.config.js", "vite.config.ts"));
        FILE_PATTERNS.put("angular", List.of("angular.json", "package.json", "*.ts", "src/app/"));
        FILE_PATTERNS.put("spring-boot", List.of("pom.xml", "build.gradle", "src/main/java/", "application.properties", "application.yml"));
        FILE_PATTERNS.put("nodejs", List.of("package.json", "node_modules/", "*.js", "*.ts"));
        FILE_PATTERNS.put("maven", List.of("pom.xml", ".m2/", "mvnw", "mvnw.cmd"));
        FILE_PATTERNS.put("gradle", List.of("build.gradle", "build.gradle.kts", "gradlew", "gradlew.bat", "settings.gradle"));
        FILE_PATTERNS.put("npm", List.of("package.json", "package-lock.json", ".npmrc"));
        FILE_PATTERNS.put("yarn", List.of("yarn.lock", ".yarnrc", ".yarn/"));
        FILE_PATTERNS.put("pnpm", List.of("pnpm-lock.yaml", ".pnpmrc"));
        FILE_PATTERNS.put("webpack", List.of("webpack.config.js", "webpack.config.ts", "webpack.*.js"));
        FILE_PATTERNS.put("vite", List.of("vite.config.js", "vite.config.ts", "vite.config.*.js"));
        FILE_PATTERNS.put("babel", List.of(".babelrc", "babel.config.js", "babel.config.*.js"));
        FILE_PATTERNS.put("eslint", List.of(".eslintrc.js", ".eslintrc.json", "eslint.config.js"));
        FILE_PATTERNS.put("prettier", List.of(".prettierrc", ".prettierrc.json", "prettier.config.js"));
        FILE_PATTERNS.put("jest", List.of("jest.config.js", "jest.config.ts", "jest.config.*.js"));
        FILE_PATTERNS.put("cypress", List.of("cypress.config.js", "cypress.config.ts", "cypress/"));
        FILE_PATTERNS.put("playwright", List.of("playwright.config.js", "playwright.config.ts", "playwright.config.*.js"));
        FILE_PATTERNS.put("tailwind", List.of("tailwind.config.js", "tailwind.config.ts", "tailwind.config.*.js"));
        FILE_PATTERNS.put("bootstrap", List.of("bootstrap.min.css", "bootstrap.css", "scss/bootstrap.scss"));
        FILE_PATTERNS.put("mysql", List.of("mysql", "*.sql", "schema.sql", "migrations/"));
        FILE_PATTERNS.put("postgresql", List.of("postgresql", "*.sql", "schema.sql", "migrations/"));
        FILE_PATTERNS.put("mongodb", List.of("mongodb", "mongoose", "*.js", "models/"));
        FILE_PATTERNS.put("redis", List.of("redis", "redis.conf", "*.redis"));
        FILE_PATTERNS.put("sqlite", List.of("*.sqlite", "*.sqlite3", "*.db", "database.sqlite"));
        FILE_PATTERNS.put("hibernate", List.of("hibernate.cfg.xml", "persistence.xml", "*.hbm.xml"));
        FILE_PATTERNS.put("jpa", List.of("persistence.xml", "EntityManager", "@Entity"));
        FILE_PATTERNS.put("mybatis", List.of("mybatis-config.xml", "*.mapper.xml", "SqlSession"));
        FILE_PATTERNS.put("liquibase", List.of("liquibase/", "changelog/", "db/changelog/"));
        FILE_PATTERNS.put("flyway", List.of("flyway.conf", "db/migration/", "V__*.sql"));
        FILE_PATTERNS.put("junit", List.of("junit", "@Test", "Assertions", "*.Test.java"));
        FILE_PATTERNS.put("testng", List.of("testng.xml", "@Test", "TestNG", "*.java"));
        FILE_PATTERNS.put("mockito", List.of("mockito", "@Mock", "@Spy", "Mockito"));
        FILE_PATTERNS.put("spring-test", List.of("@SpringBootTest", "@TestConfiguration", "TestRestTemplate"));
        FILE_PATTERNS.put("selenium", List.of("selenium", "WebDriver", "By", "WebElement"));
        FILE_PATTERNS.put("logback", List.of("logback.xml", "logback-spring.xml", "logback-test.xml"));
        FILE_PATTERNS.put("log4j", List.of("log4j2.xml", "log4j.properties", "log4j2.properties"));
        FILE_PATTERNS.put("slf4j", List.of("slf4j", "LoggerFactory", "@Slf4j"));
        FILE_PATTERNS.put("jackson", List.of("jackson", "ObjectMapper", "@JsonProperty"));
        FILE_PATTERNS.put("gson", List.of("gson", "Gson", "JsonElement"));
        FILE_PATTERNS.put("fastjson", List.of("fastjson", "JSON", "JSONObject"));
    }

    // Регулярные выражения для детекта версий из файлов
    private static final Map<String, List<Pattern>> VERSION_PATTERNS = new HashMap<>();
    
    static {
        VERSION_PATTERNS.put("java", List.of(
            Pattern.compile("<java\\.version>([0-9.]+)</java\\.version>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<maven\\.compiler\\.source>([0-9.]+)</maven\\.compiler\\.source>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<maven\\.compiler\\.target>([0-9.]+)</maven\\.compiler\\.target>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("sourceCompatibility\\s*=\\s*[\"']?([0-9.]+)[\"']?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("targetCompatibility\\s*=\\s*[\"']?([0-9.]+)[\"']?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("java\\.version\\s*=\\s*[\"']?([0-9.]+)[\"']?", Pattern.CASE_INSENSITIVE)
        ));
        VERSION_PATTERNS.put("nodejs", List.of(
            Pattern.compile("\"node\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"engines\":\\s*\\{[^}]*\"node\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("node\\s*([0-9.]+)", Pattern.CASE_INSENSITIVE)
        ));
        VERSION_PATTERNS.put("typescript", List.of(
            Pattern.compile("\"typescript\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"@types/node\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"ts-node\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        ));
        VERSION_PATTERNS.put("spring-boot", List.of(
            Pattern.compile("<spring-boot\\.version>([^<]+)</spring-boot\\.version>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("springBootVersion\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("org\\.springframework\\.boot:spring-boot-[^:]+:([0-9.]+)", Pattern.CASE_INSENSITIVE)
        ));
        VERSION_PATTERNS.put("react", List.of(
            Pattern.compile("\"react\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"@types/react\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        ));
        VERSION_PATTERNS.put("vue", List.of(
            Pattern.compile("\"vue\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"@vue/core\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        ));
        VERSION_PATTERNS.put("angular", List.of(
            Pattern.compile("\"@angular/core\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"@angular/cli\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        ));
    }

    public ProjectStackScanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Сканирует директорию проекта и возвращает обнаруженные технологии.
     * 
     * @param workingDir рабочая директория проекта
     * @return список обнаруженных технологий в формате JSON
     */
    public String scanProject(String workingDir) {
        if (workingDir == null || workingDir.trim().isEmpty()) {
            log.warn("Working directory is null or empty");
            return "[]";
        }

        Path rootPath = Paths.get(workingDir);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.warn("Working directory does not exist or is not a directory: {}", workingDir);
            return "[]";
        }

        log.info("Starting project stack scan for directory: {}", workingDir);
        
        Set<TechItem> detectedTechs = new LinkedHashSet<>();
        
        try {
            // Сканируем файловую систему
            scanFileSystem(rootPath, detectedTechs);
            
            // Анализируем содержимое ключевых файлов для версий
            analyzeFileContents(rootPath, detectedTechs);
            
            // Проверяем на монорепозитории
            detectMonorepoStructure(rootPath, detectedTechs);
            
        } catch (IOException e) {
            log.error("Error scanning project directory: " + workingDir, e);
            return "[]";
        }

        // Обработка случая, когда нет распознанных файлов
        if (detectedTechs.isEmpty()) {
            log.info("No recognized technologies found in directory: {}", workingDir);
            return "[]";
        }

        // Конвертируем в JSON
        try {
            return objectMapper.writeValueAsString(detectedTechs.stream()
                .sorted(Comparator.comparing(TechItem::getName))
                .toList());
        } catch (Exception e) {
            log.error("Error converting tech stack to JSON", e);
            return "[]";
        }
    }

    private void scanFileSystem(Path rootPath, Set<TechItem> detectedTechs) throws IOException {
        try (Stream<Path> stream = Files.walk(rootPath, 10)) {
            stream.filter(Files::isRegularFile)
                 .forEach(file -> {
                     String relativePath = rootPath.relativize(file).toString();
                     detectFromFilePattern(relativePath, detectedTechs);
                     if ("package.json".equalsIgnoreCase(file.getFileName().toString())) {
                         try {
                             analyzePackageJson(Files.readString(file), detectedTechs);
                         } catch (IOException e) {
                             log.debug("Could not read package.json at {}", file, e);
                         }
                     }
                 });
        }
    }

    private void detectFromFilePattern(String relativePath, Set<TechItem> detectedTechs) {
        String normalizedPath = relativePath.replace('\\', '/').toLowerCase();
        
        for (Map.Entry<String, List<String>> entry : FILE_PATTERNS.entrySet()) {
            String techName = entry.getKey();
            List<String> patterns = entry.getValue();
            
            for (String pattern : patterns) {
                if (matchesPattern(normalizedPath, pattern)) {
                    detectedTechs.add(new TechItem(techName, null));
                    break; // Нашли совпадение, переходим к следующей технологии
                }
            }
        }
    }

    private boolean matchesPattern(String path, String pattern) {
        String lowerPattern = pattern.toLowerCase();
        if (lowerPattern.startsWith("*")) {
            String extension = lowerPattern.substring(1);
            return path.endsWith(extension);
        } else if (lowerPattern.endsWith("/")) {
            return path.startsWith(lowerPattern);
        } else {
            return path.equals(lowerPattern) || path.endsWith("/" + lowerPattern);
        }
    }

    private void analyzeFileContents(Path rootPath, Set<TechItem> detectedTechs) {
        // Анализируем ключевые файлы для определения версий
        Map<String, String> keyFiles = Map.of(
            "package.json", "javascript|typescript|nodejs|react|vue|angular",
            "pom.xml", "java|spring-boot|maven",
            "build.gradle", "java|spring-boot|gradle",
            "build.gradle.kts", "java|spring-boot|gradle",
            "requirements.txt", "python",
            "go.mod", "go",
            "Cargo.toml", "rust",
            "composer.json", "php",
            "Gemfile", "ruby"
        );

        for (Map.Entry<String, String> entry : keyFiles.entrySet()) {
            String fileName = entry.getKey();
            String techTypes = entry.getValue();
            
            Path filePath = rootPath.resolve(fileName);
            if (Files.exists(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    analyzeFileContent(fileName, content, techTypes, detectedTechs);
                    
                    // Специальный анализ для package.json
                    if ("package.json".equals(fileName)) {
                        analyzePackageJson(content, detectedTechs);
                    }
                } catch (IOException e) {
                    log.debug("Could not read file: {}", fileName, e);
                }
            }
        }
    }

    private void analyzeFileContent(String fileName, String content, String techTypes, Set<TechItem> detectedTechs) {
        String[] types = techTypes.split("\\|");

        for (String techType : types) {
            List<Pattern> patterns = VERSION_PATTERNS.get(techType);
            if (patterns != null) {
                for (Pattern pattern : patterns) {
                    Matcher matcher = pattern.matcher(content);
                    if (matcher.find()) {
                        String version = matcher.groupCount() > 0 ? matcher.group(1) : null;
                        if (version != null) {
                            // Update existing item's version if it was added without version
                            detectedTechs.removeIf(item -> techType.equals(item.getName()) && item.getVersion() == null);
                            detectedTechs.add(new TechItem(techType, version));
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * Проверяет наличие монорепозиториев (frontend/backend структуры)
     */
    private void detectMonorepoStructure(Path rootPath, Set<TechItem> detectedTechs) {
        try {
            // Проверяем наличие frontend/backend директорий
            Path frontendDir = rootPath.resolve("frontend");
            Path backendDir = rootPath.resolve("backend");
            Path clientDir = rootPath.resolve("client");
            Path serverDir = rootPath.resolve("server");
            Path webDir = rootPath.resolve("web");
            Path apiDir = rootPath.resolve("api");
            
            boolean hasFrontend = Files.exists(frontendDir) || Files.exists(clientDir) || Files.exists(webDir);
            boolean hasBackend = Files.exists(backendDir) || Files.exists(serverDir) || Files.exists(apiDir);
            
            if (hasFrontend && hasBackend) {
                detectedTechs.add(new TechItem("monorepo", null));
                log.info("Detected monorepo structure");
                
                // Сканируем поддиректории для детекта технологий
                if (Files.exists(frontendDir)) {
                    scanSubdirectory(frontendDir, detectedTechs);
                }
                if (Files.exists(clientDir)) {
                    scanSubdirectory(clientDir, detectedTechs);
                }
                if (Files.exists(webDir)) {
                    scanSubdirectory(webDir, detectedTechs);
                }
                if (Files.exists(backendDir)) {
                    scanSubdirectory(backendDir, detectedTechs);
                }
                if (Files.exists(serverDir)) {
                    scanSubdirectory(serverDir, detectedTechs);
                }
                if (Files.exists(apiDir)) {
                    scanSubdirectory(apiDir, detectedTechs);
                }
            }
        } catch (Exception e) {
            log.debug("Error detecting monorepo structure", e);
        }
    }

    /**
     * Сканирует поддиректорию для детекта технологий
     */
    private void scanSubdirectory(Path subDir, Set<TechItem> detectedTechs) {
        try {
            if (!Files.isDirectory(subDir)) return;
            
            try (Stream<Path> stream = Files.walk(subDir, 5)) {
                stream.filter(Files::isRegularFile)
                     .forEach(file -> {
                         String relativePath = subDir.relativize(file).toString();
                         detectFromFilePattern(relativePath, detectedTechs);
                     });
            }
        } catch (IOException e) {
            log.debug("Error scanning subdirectory: {}", subDir, e);
        }
    }

    /**
     * Анализирует package.json для детекта TypeScript и других frontend технологий
     */
    private void analyzePackageJson(String content, Set<TechItem> detectedTechs) {
        try {
            // Проверяем наличие TypeScript в dependencies/devDependencies
            if (content.contains("\"typescript\"") || content.contains("\"@types/")) {
                detectedTechs.add(new TechItem("typescript", null));
            }
            
            // Проверяем наличие React
            if (content.contains("\"react\"")) {
                detectedTechs.add(new TechItem("react", null));
            }
            
            // Проверяем наличие Vue
            if (content.contains("\"vue\"")) {
                detectedTechs.add(new TechItem("vue", null));
            }
            
            // Проверяем наличие Angular
            if (content.contains("\"@angular/")) {
                detectedTechs.add(new TechItem("angular", null));
            }
            
            // Проверяем наличие Next.js
            if (content.contains("\"next\"")) {
                detectedTechs.add(new TechItem("nextjs", null));
            }
            
            // Проверяем наличие Vite
            if (content.contains("\"vite\"")) {
                detectedTechs.add(new TechItem("vite", null));
            }
            
            // Проверяем наличие Webpack
            if (content.contains("\"webpack\"")) {
                detectedTechs.add(new TechItem("webpack", null));
            }
            
            // Проверяем наличие ESLint
            if (content.contains("\"eslint\"")) {
                detectedTechs.add(new TechItem("eslint", null));
            }
            
            // Проверяем наличие Prettier
            if (content.contains("\"prettier\"")) {
                detectedTechs.add(new TechItem("prettier", null));
            }
            
            // Проверяем наличие Jest
            if (content.contains("\"jest\"")) {
                detectedTechs.add(new TechItem("jest", null));
            }
            
            // Проверяем наличие Cypress
            if (content.contains("\"cypress\"")) {
                detectedTechs.add(new TechItem("cypress", null));
            }
            
            // Проверяем наличие Playwright
            if (content.contains("\"playwright\"")) {
                detectedTechs.add(new TechItem("playwright", null));
            }
            
            // Проверяем наличие Tailwind CSS
            if (content.contains("\"tailwindcss\"")) {
                detectedTechs.add(new TechItem("tailwind", null));
            }
            
            // Проверяем наличие Bootstrap
            if (content.contains("\"bootstrap\"")) {
                detectedTechs.add(new TechItem("bootstrap", null));
            }
            
        } catch (Exception e) {
            log.debug("Error analyzing package.json", e);
        }
    }

    /**
     * Внутренний класс для представления технологии.
     */
    public static class TechItem {
        private String name;
        private String version;

        public TechItem() {}

        public TechItem(String name, String version) {
            this.name = name;
            this.version = version;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TechItem techItem = (TechItem) o;
            return Objects.equals(name, techItem.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        @Override
        public String toString() {
            return "TechItem{name='" + name + "', version='" + version + "'}";
        }
    }
}