plugins {
    id("com.android.application")
}

android {
    namespace = "cn.kylin.watchface"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.kylin.watchface"
        minSdk = 27
        targetSdk = 27
        versionCode = 7
        versionName = "1.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
