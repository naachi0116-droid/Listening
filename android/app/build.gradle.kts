plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.naachi.eigodrive"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.naachi.eigodrive"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("BUILD_NO") ?: "1").toInt()
        versionName = "0." + (System.getenv("BUILD_NO") ?: "1")
    }
    // 個人用アプリの署名鍵（更新のたびに入れ直さなくて済むよう、毎回同じ鍵で署名する）
    signingConfigs {
        create("rel") {
            storeFile = file("../eigo.jks")
            storePassword = "eigodrive123"
            keyAlias = "eigo"
            keyPassword = "eigodrive123"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rel")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
