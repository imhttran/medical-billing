plugins {
    kotlin("jvm") version "2.2.21"
    // allopen + noarg presets: Spring needs @Configuration/@Component/@Service
    // classes to be non-final, and reflection-driven ones to be instantiable.
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.htt"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

// Spring Boot's BOM pins the Kotlin artifacts it was tested against; point that
// at the compiler plugin's version so stdlib, reflect and the compiler agree.
extra["kotlin.version"] = "2.2.21"

// Java 21 LTS. Toolchain, not the launching JDK, so the JDK that runs Gradle
// (25 on this machine) doesn't have to be the one that compiles. This sets the
// Java and Kotlin toolchains and jvmTarget together.
kotlin {
    jvmToolchain(21)

    compilerOptions {
        // Non-null Kotlin types stay non-null for callers compiled from Java,
        // so Spring and Jackson see what the code means.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-java-parameters")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // JdbcClient + HikariCP: raw SQL, no ORM.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Lets Jackson construct Kotlin classes (defaults, nullability) instead of
    // failing on them.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // HS256 JWTs, carrying the {email, exp, iat} claims the API contract pins.
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // scrypt for the Node-compatible `hex(salt):hex(key)` hash format. The JDK
    // has no scrypt, and every user hash in the database is in that format.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Deterministic jar name for the Dockerfile (build/libs/app.jar).
tasks.bootJar {
    archiveFileName.set("app.jar")
    // Kotlin's `main` lands on a synthesized <File>Kt class, so name it.
    mainClass.set("com.htt.template.TemplateApplicationKt")
}

tasks.jar {
    enabled = false
}
