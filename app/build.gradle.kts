plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

android {
    namespace = "com.goose.summerzf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.goose.summerzf"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

val androidAutoPrivateDir = rootProject.projectDir.resolve("tooling/private/android-auto")
val androidAutoIdentityOutputDir = layout.buildDirectory.dir("generated/res/android-auto-identity/main")
val androidAutoSourceOutputDir = layout.buildDirectory.dir("generated/source/android-auto-private/main")
val includeAndroidAutoIdentity = providers.gradleProperty("includeAndroidAutoIdentity")
    .map { it.toBoolean() }
    .orElse(false)
val includeAndroidAutoReceiver = providers.gradleProperty("includeAndroidAutoReceiver")
    .map { it.toBoolean() }
    .orElse(false)

val cleanAndroidAutoPrivateInputs by tasks.registering(Delete::class) {
    delete(androidAutoIdentityOutputDir, androidAutoSourceOutputDir)
}

val verifyAndroidAutoPrivateInputs by tasks.registering {
    doLast {
        if (includeAndroidAutoIdentity.get()) {
            check(androidAutoPrivateDir.resolve("aa_cert").isFile) {
                "Missing tooling/private/android-auto/aa_cert"
            }
            check(androidAutoPrivateDir.resolve("aa_identity_data").isFile) {
                "Missing tooling/private/android-auto/aa_identity_data"
            }
        }
        if (includeAndroidAutoReceiver.get()) {
            check(androidAutoPrivateDir.resolve("vendor-src/io/motohub/android/aa/AaReceiver.kt").isFile) {
                "Receiver source has not been imported. Run tooling/android-auto/import-motohub-receiver.sh."
            }
            check(androidAutoPrivateDir.resolve("vendor-src/com/goose/summerzf/privateaa/AndroidAutoPrivateAdapter.kt").isFile) {
                "Private Android Auto adapter is missing. Re-run the receiver import script."
            }
        }
    }
}

val prepareAndroidAutoIdentity by tasks.registering(Copy::class) {
    dependsOn(cleanAndroidAutoPrivateInputs, verifyAndroidAutoPrivateInputs)
    from(androidAutoPrivateDir) {
        include("aa_cert", "aa_identity_data")
    }
    into(androidAutoIdentityOutputDir.map { it.dir("raw") })
    onlyIf {
        includeAndroidAutoIdentity.get() &&
            androidAutoPrivateDir.resolve("aa_cert").isFile &&
            androidAutoPrivateDir.resolve("aa_identity_data").isFile
    }
}

val prepareAndroidAutoReceiver by tasks.registering(Copy::class) {
    dependsOn(cleanAndroidAutoPrivateInputs, verifyAndroidAutoPrivateInputs)
    from(androidAutoPrivateDir.resolve("vendor-src"))
    into(androidAutoSourceOutputDir)
    onlyIf {
        includeAndroidAutoReceiver.get() && androidAutoPrivateDir.resolve("vendor-src").isDirectory
    }
}

android.sourceSets.getByName("main").res.srcDir(androidAutoIdentityOutputDir)
android.sourceSets.getByName("main").java.srcDir(androidAutoSourceOutputDir)

tasks.named("preBuild").configure {
    dependsOn(prepareAndroidAutoIdentity, prepareAndroidAutoReceiver)
}

val assemblePrivateAndroidAutoDebug by tasks.registering {
    group = "build"
    description = "Builds a private debug APK with the local Android Auto receiver and identity."
    dependsOn("assembleDebug")
}

dependencies {
    // Custom HUD Lib
    implementation(files("libs/hudlib.aar"))

    // Map renderer (snapshot-based for the 800x400 HUD canvas)
    implementation(libs.maplibre.android)

    // Embedded Android Auto receiver dependencies. The receiver source itself
    // is a private build input and is not compiled into normal public builds.
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)

    // Camera X
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit barcode scanning
    implementation(libs.barcode.scanning)
    
    // Basic
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}