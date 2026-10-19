import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("kotlin-conventions")
    id("formatter-conventions")
    id("test-conventions")
    alias(libs.plugins.shadow)
    application
}

application {
    mainClass.set("org.rsmod.tools.launcher.LauncherKt")
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    implementation("com.formdev:flatlaf:3.7.1")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("launcher.jar")
    mergeServiceFiles()
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
