plugins {
    java
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.workflow"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

extra["springShellVersion"] = "3.4.0"
extra["lombok.version"] = "1.18.36"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.shell:spring-shell-starter:${property("springShellVersion")}")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.yaml:snakeyaml")
    // JGit — clones each run's repo into an isolated per-run sandbox (WorkspaceProvisioner).
    // In-process credentials (no token leaking to `ps`), no dependency on a `git` binary.
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    // Liquibase — versioned, reviewable schema migrations (db/changelog/). Owns the SaaS
    // Phase 1 delta; runs before Hibernate on startup. Version managed by the Spring Boot BOM.
    implementation("org.liquibase:liquibase-core")
    // Stripe — self-serve wallet top-ups (Phase 2): hosted Checkout + signed webhooks.
    implementation("com.stripe:stripe-java:25.7.0")
    // Transactional email — account verification (Phase 2). JavaMailSender; SMTP optional.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // Lombok removed; using plain POJOs/records instead
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation("com.github.ben-manes.caffeine:caffeine")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
