// seoltube 엔진 올리기 전 시험(JVM). 엔진 APK 빌드(../settings.gradle.kts)와 따로인 빌드
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "seoltube-engine-smoke"
