import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.bluewallet"
version = "0.0.1"

kotlin {
    jvm()
    androidLibrary {
        namespace = "io.bluewallet.bip158"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosArm64()
    iosSimulatorArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlincrypto.sha2)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "bip158", version.toString())

    pom {
        name = "bip158"
        description = "Kotlin Multiplatform BIP-158 compact block filters (GCS)."
        inceptionYear = "2026"
        url = "https://github.com/GladosBlueWallet/bip158.kmp/"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "overtorment"
                name = "Overtorment"
                url = "https://github.com/Overtorment/"
            }
        }
        scm {
            url = "https://github.com/GladosBlueWallet/bip158.kmp/"
            connection = "scm:git:git://github.com/GladosBlueWallet/bip158.kmp.git"
            developerConnection = "scm:git:ssh://git@github.com/GladosBlueWallet/bip158.kmp.git"
        }
    }
}
