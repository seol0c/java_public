plugins {
    id("com.android.application")
}

// seoltube 엔진: NewPipe Extractor 를 안드로이드용 dex 로 묶은 APK.
// 앱이 DexClassLoader 로 불러 쓰고, 이 APK 는 설치하지 않는다.
// NewPipe 버전은 자동 빌드가 -PnewpipeVersion=v0.27.1 처럼 넘긴다.
val newpipeVersion: String = (findProperty("newpipeVersion") as String?) ?: "v0.27.1"

android {
    namespace = "com.seolpc.seoltube.engine"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seolpc.seoltube.engine"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = newpipeVersion
        buildConfigField("String", "NEWPIPE", "\"$newpipeVersion\"")
    }
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        // NewPipe Extractor 는 안드로이드 13 아래에서 java.nio 등을 쓰려면 desugaring 이 필요하다(NewPipe README)
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/**", "**/*.proto")
    }
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newpipeVersion")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
