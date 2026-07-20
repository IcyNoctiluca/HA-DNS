plugins {
    java
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.homelab.dnsha"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass.set("io.homelab.dnsha.App")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.glassfish.jersey.containers:jersey-container-grizzly2-http:3.1.5")
    implementation("org.glassfish.jersey.inject:jersey-hk2:3.1.5")
    implementation("org.glassfish.jersey.media:jersey-media-json-jackson:3.1.5")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("dnsjava:dnsjava:3.6.0")
    implementation("io.prometheus:simpleclient:0.16.0")
    implementation("io.prometheus:simpleclient_hotspot:0.16.0")
    implementation("io.prometheus:simpleclient_servlet:0.16.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
}

tasks.test {
    useJUnitPlatform()
    // Integration tests (Testcontainers-backed) are separated by naming
    // convention (*IT.java) and excluded from the default `test` task
    // since they require a Docker daemon. Run explicitly via `integrationTest`.
    exclude("**/*IT.class")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs Testcontainers-backed integration tests (requires Docker)."
    group = "verification"
    useJUnitPlatform()
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.check {
    // integrationTest is intentionally NOT wired into `check` — it requires a
    // Docker daemon that may not be present on every dev machine / CI runner
    // stage. `./gradlew check` (the required pre-push gate per AGENTS.md)
    // therefore only runs the fast unit suite. CI's `test` job runs both
    // explicitly; see .forgejo/workflows/build.yml.
}

tasks.shadowJar {
    archiveBaseName.set("dns-ha-agent")
    archiveClassifier.set("")
    archiveVersion.set("")
}

tasks.jar {
    // Disable the plain (non-shadow) jar's default "-1.0.0" classifier-less
    // output colliding with shadowJar's output path; shadowJar is the one
    // artifact we ship.
    enabled = true
}
