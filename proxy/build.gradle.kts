plugins {
    id("com.gradleup.shadow")
}

val nettyVersion     = "4.1.115.Final"
val gsonVersion      = "2.10.1"
val snakeYamlVersion = "2.2"
val velocityVersion  = "3.3.0-SNAPSHOT"

dependencies {
    // Bundled into the shaded jar
    implementation(project(":common"))

    // Provided at runtime by Velocity
    compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
    compileOnly("io.netty:netty-all:$nettyVersion")
    compileOnly("com.google.code.gson:gson:$gsonVersion")
    compileOnly("org.yaml:snakeyaml:$snakeYamlVersion")
}

tasks.shadowJar {
    archiveBaseName.set("zpeer-proxy")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}

tasks.named("build") {
    dependsOn("shadowJar")
}

tasks.named<Jar>("jar") {
    // disable the thin jar; only shaded jar is published
    enabled = false
}
