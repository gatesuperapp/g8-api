import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems.jar

val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project
val koinKtor_version: String by project
val hikaricp_version: String by project

plugins {
    kotlin("jvm") version "1.9.22"
    id("io.ktor.plugin") version "2.3.7"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.withType<Test> {
    useJUnit()
    // Tests run against an in-memory H2 (no file on disk) and never reach a real SMTP.
    environment("JWT_SECRET", "test-secret-only-for-junit-runs-not-real")
    environment("EMAIL_NOOP", "true")
}


group = "com.a4a.g8api"
version = "0.0.1"

application {
    mainClass.set("com.a4a.g8api.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-server-auth-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-auth-jwt-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-rate-limit-jvm")
    implementation("io.ktor:ktor-server-forwarded-header-jvm")
    implementation("io.ktor:ktor-server-default-headers-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    implementation("org.jetbrains.exposed:exposed-core:0.47.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.47.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.47.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.47.0")
    // H2 kept for the test suite (in-memory, MODE=PostgreSQL).
    implementation("com.h2database:h2:2.2.224")
    // Production database — read DATABASE_URL/USER/PASSWORD env vars (see application.conf).
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
    // Dependency Injection
    //Enables external provisioning of component dependencies, fostering modularity and flexibility in development.
    implementation("io.insert-koin:koin-ktor:$koinKtor_version")
    // Connection pooling
    //Facilitates connection pooling, optimizing performance and resource usage by reusing connections instead of creating new ones for each operation.
    implementation("com.zaxxer:HikariCP:$hikaricp_version")
    // Stripe
    implementation("com.stripe:stripe-java:26.1.0")
    // Mail (SMTP via Postfix on localhost)
    implementation("com.sun.mail:jakarta.mail:2.0.1")
}


ktor {
    fatJar {
        archiveFileName.set("g8-api.jar")
    }
}


