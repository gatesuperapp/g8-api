val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project
val koinKtor_version: String by project
val hikaricp_version: String by project

plugins {
    kotlin("jvm") version "2.3.21"
    id("io.ktor.plugin") version "3.5.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.withType<Test> {
    useJUnit()
    // Tests run against a Testcontainers-managed Postgres and never reach a real SMTP.
    // Requires a Docker daemon reachable at /var/run/docker.sock — OrbStack and
    // Docker Desktop wire that path automatically. On runtimes that expose the socket
    // elsewhere (e.g. Colima), export DOCKER_HOST before running ./gradlew test.
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
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    // Testcontainers spins up a real Postgres 16 in Docker for the integration tests
    // so the test schema/dialect matches production. Uses docker-java 3.4.2 under the
    // hood, which needs `api.version=1.44` in src/test/resources/docker-java.properties
    // to satisfy Docker Engine 27+'s minimum API version.
    testImplementation("org.testcontainers:postgresql:1.21.3")
    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.61.0")
    // Production database — read DATABASE_URL/USER/PASSWORD env vars (see application.conf).
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
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


