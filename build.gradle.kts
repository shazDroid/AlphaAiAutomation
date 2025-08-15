import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.0.0"
}

group = "com.shazdroid.aiautomation"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // Note, if you develop a library, you should use compose.desktop.common.
    // compose.desktop.currentOs should be used in launcher-sourceSet
    // (in a separate module for demo project and in testMain).
    // With compose.desktop.common you will also lose @Preview functionality
    implementation(compose.desktop.currentOs)

    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.ismai117:kottie:1.9.6")

    // Appium
    implementation("io.appium:java-client:9.2.3")
    implementation("org.seleniumhq.selenium:selenium-java:4.23.0")


    // XML parsing
    implementation("org.jsoup:jsoup:1.18.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AIAutomation"
            packageVersion = "1.0.0"

            macOS {
                iconFile.set(project.file("src/main/resources/drawable/app_icon.png"))
            }

            windows {
                iconFile.set(project.file("src/main/resources/drawable/app_icon.png"))
            }

            linux {
                iconFile.set(project.file("src/main/resources/drawable/app_icon.png"))
            }
        }
    }
}
