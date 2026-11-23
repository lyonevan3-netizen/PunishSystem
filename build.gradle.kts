plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.punishsystem"
version = "1.0.4"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.2 stable API; the plugin only uses Bukkit/Paper public APIs.
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.sqlite", "com.punishsystem.libs.sqlite")
}

tasks.build { dependsOn(tasks.shadowJar) }
