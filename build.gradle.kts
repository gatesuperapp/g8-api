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
    implementation("io.ktor:ktor-server-swagger-jvm")
    implementation("io.ktor:ktor-server-openapi")
    implementation("io.ktor:ktor-server-auth-jwt-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    implementation("org.jetbrains.exposed:exposed-core:0.47.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.47.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.47.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.47.0")
    implementation("com.h2database:h2:2.2.224")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
    // Dependency Injection
    //Enables external provisioning of component dependencies, fostering modularity and flexibility in development.
    implementation("io.insert-koin:koin-ktor:$koinKtor_version")
    // Connection pooling
    //Facilitates connection pooling, optimizing performance and resource usage by reusing connections instead of creating new ones for each operation.
    implementation("com.zaxxer:HikariCP:$hikaricp_version")
    //bcrypt implementation for hashing passwords
    //https://github.com/jeremyh/jBCrypt
    implementation("org.mindrot:jbcrypt:0.4")
}


ktor {
    fatJar {
        archiveFileName.set("g8-api.jar")
    }
}


