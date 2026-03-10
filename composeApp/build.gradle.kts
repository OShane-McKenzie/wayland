import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
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
//            packageVersion = "2.0.2"
//        }
//    }
//}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "pkg.virdin"
            artifactId = "wayland"
            version = "2.0.2"

            from(components["kotlin"])
        }
    }
}
