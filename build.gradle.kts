plugins {
    id("java")
    id("application")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.crdb.benchmark"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // CockroachDB JDBC Driver (PostgreSQL compatible) - Latest
    implementation("org.postgresql:postgresql:42.7.4")
    
    // HikariCP Connection Pool - Latest
    implementation("com.zaxxer:HikariCP:6.2.1")
    
    // Logging - Latest
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    
    // Metrics Collection with Micrometer - Latest
    implementation("io.micrometer:micrometer-core:1.14.2")
    
    // JSON Processing - Latest
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    
    // JUnit for Testing - Latest
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    
    // H2 Database for demo and testing
    implementation("com.h2database:h2:2.3.232")
}

application {
    mainClass = "com.crdb.benchmark.BenchmarkRunner"
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
    options.release = 21
}

tasks.withType<JavaExec> {
    jvmArgs = listOf("--enable-preview")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs = listOf("--enable-preview")
}

tasks.shadowJar {
    archiveBaseName.set("crdb-connection-benchmark")
    archiveClassifier.set("")
    archiveVersion.set("1.0.0")
    
    manifest {
        attributes["Main-Class"] = "com.crdb.benchmark.BenchmarkRunner"
    }
    
    // Merge service files
    mergeServiceFiles()
}

// Fix task dependencies
tasks.named("distZip") {
    dependsOn(tasks.shadowJar)
}

tasks.named("distTar") {
    dependsOn(tasks.shadowJar)
}

tasks.named("startScripts") {
    dependsOn(tasks.shadowJar)
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}
