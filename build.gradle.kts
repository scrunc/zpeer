plugins {
    id("com.gradleup.shadow") version "8.3.5" apply false
}

allprojects {
    group   = "dev.servereer"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
