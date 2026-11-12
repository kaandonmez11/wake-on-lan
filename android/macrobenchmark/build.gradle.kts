plugins {
    alias(libs.plugins.android.test)
}

/**
 * S13: performans ölçümü ve baseline profile üretimi (gerçek cihazda). Uygulamanın `benchmark` türünü
 * (release + R8, debug anahtarıyla imzalı, profillenebilir) hedefler.
 *
 * Ölçüm:   ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kdgames.wake_on_lan.macrobenchmark.Benchmarks
 * Profil:  ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kdgames.wake_on_lan.macrobenchmark.BaselineProfileGenerator
 *          çıkan `*-baseline-prof.txt` → app/src/main/baseline-prof.txt
 */
android {
    namespace = "com.kdgames.wake_on_lan.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 35
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    // Yalnız benchmark türü çalıştırılır.
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
