plugins {
    java
}

group = "io.github.dohwan"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    implementation("com.mysql:mysql-connector-j:9.3.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(26)
}

tasks.jar {
    archiveFileName.set("dbsql.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
