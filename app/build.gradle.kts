import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sulaymoniy.soalarm"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sulaymoniy.soalarm"
        minSdk = 26
        targetSdk = 37
        // Raise versionCode by 1 every time you upload a new build to Google Play.
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Kept off so MediaPipe's native code works without extra keep rules.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // MediaPipe reads the model straight from the APK, so keep it uncompressed.
        noCompress += "task"
    }
}

// ---------------------------------------------------------------------------
// Hand-tracking model. MediaPipe needs "hand_landmarker.task" in the assets
// folder. It is downloaded once from Google's official model storage on the
// first build, so you never have to fetch it by hand.
// ---------------------------------------------------------------------------
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
val handModelFile = layout.projectDirectory.file("src/main/assets/hand_landmarker.task")

val downloadHandModel = tasks.register("downloadHandModel") {
    description = "Downloads the MediaPipe hand landmarker model into src/main/assets."
    val modelUrl = handModelUrl
    val target = handModelFile
    outputs.file(target)
    onlyIf { !target.asFile.exists() }
    doLast {
        val out = target.asFile
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        val input = URI(modelUrl).toURL().openStream()
        try {
            Files.copy(input, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            input.close()
        }
        check(tmp.length() > 1_000_000) { "Hand model download looks incomplete. Try building again." }
        if (!tmp.renameTo(out)) error("Could not save $out")
    }
}
tasks.named("preBuild") { dependsOn(downloadHandModel) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
}
