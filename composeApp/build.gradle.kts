import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    kotlin("plugin.serialization") version "1.9.21"
    `maven-publish`
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // Compose Core
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)

            // Compose Resources & Tooling
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // Lifecycle & ViewModel
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

            // Native & Reflection
            implementation("net.java.dev.jna:jna:5.13.0")
            implementation("net.java.dev.jna:jna-platform:5.13.0")
            implementation(kotlin("reflect"))

            // Serialization
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
        }
    }
}

//compose.desktop {
//    application {
//        mainClass = "pkg.virdin.wayland.MainKt"
//
//        nativeDistributions {
//            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
//            packageName = "pkg.virdin.wayland"
//            packageVersion = "1.0.0"
//        }
//    }
//}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "pkg.virdin"
            artifactId = "wayland"
            version = "1.0.4"

            from(components["kotlin"])
        }
    }
}
