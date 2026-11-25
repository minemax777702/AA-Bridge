plugins {
    id("com.android.application")
}

android {
    namespace = "dev.caraalink.emulator"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.minemax777702.huemulator"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":protocol"))
    testImplementation("junit:junit:4.13.2")
}
