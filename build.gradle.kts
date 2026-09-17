import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // The plugin version is declared by org.jetbrains.intellij.platform.settings
    // in settings.gradle.kts; declaring it again here makes Gradle reject the
    // plugin as already present on the classpath with an unknown version.
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // NOTE (Phase 0 verification item, see PLAN.md): confirm this accessor
        // name via IDE code completion once opened in real PyCharm - the
        // IntelliJ Platform Gradle Plugin has been consolidating IU/PY product
        // accessors and `pycharmProfessional(...)` may have changed shape.
        pycharmProfessional(providers.gradleProperty("platformVersion"))

        bundledPlugin("Git4Idea")

        // Reuse the IDE's own pinned pty4j via the bundled terminal plugin
        // instead of shipping a duplicate native library (see PLAN.md). Only
        // fall back to an explicit `org.jetbrains.pty4j:pty4j` dependency if
        // pty4j classes turn out not to be exported to third-party plugins.
        bundledPlugin("org.jetbrains.plugins.terminal")

        // PatchReader lives in the IDE's VCS implementation module and is
        // used to turn Hunk's unified patch into a native diff request.
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.collaborationTools")

    }

    implementation(libs.kotlinx.serialization.json)
}

java {
    // IntelliJ Platform 2026.2.2 requires exactly Java 25 bytecode.
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    // Only JDK 26 is installed locally (no toolchain download resolver is
    // configured), so run the compiler on 26 while still emitting Java 25
    // bytecode via jvmTarget below - the JDK just needs to be >= the target.
    jvmToolchain(26)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}
