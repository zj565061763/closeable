plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

val libGroupId = "com.sd.lib.android"
val libArtifactId = "closeable"
val libVersionName = "1.2.0"

android {
    namespace = "com.sd.lib.closeable"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = 21
    }

    kotlinOptions {
        freeCompilerArgs += "-module-name=$libGroupId.$libArtifactId"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(8)
}

dependencies {
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = libGroupId
            artifactId = libArtifactId
            version = libVersionName

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}