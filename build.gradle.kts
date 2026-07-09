plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "uesugi.official.qq"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("uesugi:onebot-lib:1.0.0")
    implementation("io.ktor:ktor-client-core:3.3.2")
    implementation("io.ktor:ktor-client-cio:3.3.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.2")
    implementation("io.ktor:ktor-client-websockets:3.3.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.typesafe:config:1.4.3")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.02")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")


    testImplementation(kotlin("test"))
    testImplementation("uesugi:onebot-sdk:1.0.0")
    testImplementation("io.ktor:ktor-client-mock:3.3.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("uesugi.official.qq.onebot.MainKt")
}

kotlin {
    jvmToolchain(17)
}
