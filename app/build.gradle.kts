plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.xros.securescreenrecord"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xros.securescreenrecord"
        minSdk = 33
        targetSdk = 35
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
}

// Android 13 framework JAR (classes-13.jar), used compile-only to access
// system-level APIs (DisplayManager secure virtual display, REMOTE_SUBMIX, etc.)
// that require UID 1000 + platform signature at runtime. Never packaged into the APK.
// Override the path via -Pandroid13FrameworkJar=/path/to/classes-13.jar if needed.
val android13FrameworkJar: String? = (project.findProperty("android13FrameworkJar") as String?)
val android13FrameworkJarFile = if (android13FrameworkJar != null) {
    file(android13FrameworkJar)
} else {
    rootProject.file("resource/classes-13.jar")
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    if (android13FrameworkJarFile.exists()) {
        compileOnly(files(android13FrameworkJarFile))
    } else {
        logger.warn("android13 framework jar not found at ${'$'}{android13FrameworkJarFile}; hidden API compileOnly access disabled")
    }
}