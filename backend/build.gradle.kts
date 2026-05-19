plugins {
    id("com.gradleup.shadow")
}

val nettyVersion  = "4.1.115.Final"
val gsonVersion   = "2.10.1"
val paperVersion  = "1.21.1-R0.1-SNAPSHOT"

dependencies {
    // Bundled into the shaded jar
    implementation(project(":common"))

    // Provided at runtime by Paper
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("io.netty:netty-all:$nettyVersion")
    compileOnly("com.google.code.gson:gson:$gsonVersion")
}

tasks.shadowJar {
    archiveBaseName.set("zpeer-backend")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}

tasks.named("build") {
    dependsOn("shadowJar")
}

tasks.named<Jar>("jar") {
    enabled = false
}
