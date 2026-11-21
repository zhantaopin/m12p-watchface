buildscript {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.5.0")
    }
}
