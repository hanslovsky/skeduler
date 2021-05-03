import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val compileKotlin: KotlinCompile by tasks
val compileTestKotlin: KotlinCompile by tasks

compileKotlin.kotlinOptions.jvmTarget = "1.8"
compileTestKotlin.kotlinOptions.jvmTarget = compileKotlin.kotlinOptions.jvmTarget

plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.4.20"

    // Apply the java-library plugin for API and implementation separation.
    `java-library`

    application
}

application {
    mainClass.set("me.hanslovsky.skeduler.SchedulerKt")
}

repositories {
    // Use JCenter for resolving dependencies.
    jcenter()
    mavenLocal()
}

dependencies {
    api("black.ninia:jep:3.9.1")
    api("net.imglib2:imglib2:5.12.0")
    api("org.ntakt:ntakt:0.1.0-SNAPSHOT")
}
