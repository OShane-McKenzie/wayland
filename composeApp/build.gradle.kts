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
            // Change these to 'api' so consumers get the right versions
            api(compose.ui)
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.components.resources)
            api(compose.components.uiToolingPreview)
            api(libs.androidx.lifecycle.viewmodelCompose)
            api(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            api(compose.desktop.currentOs)  // Change to 'api'

            implementation(libs.kotlinx.coroutinesSwing)
            implementation("net.java.dev.jna:jna:5.13.0")
            implementation("net.java.dev.jna:jna-platform:5.13.0")
            implementation(kotlin("reflect"))
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
            version = "1.0.3"

            from(components["kotlin"])
        }
    }
}
