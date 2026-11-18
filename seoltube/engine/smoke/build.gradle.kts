plugins {
    application
}

// 엔진 소스(../engine/src/main/java)를 같은 NewPipe 판으로 JVM 에서 돌려 본다. 안드로이드에는 org.json 이 들어 있어 여기서만 넣는다.
// 자동 빌드가 gradle -p seoltube/engine/smoke run -PnewpipeVersion=v0.27.1 처럼 부른다.
val newpipeVersion: String = (findProperty("newpipeVersion") as String?) ?: "v0.27.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        java.srcDirs("../engine/src/main/java", "src")
    }
}

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newpipeVersion")
    implementation("org.json:json:20240303")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

application {
    mainClass.set("com.seolpc.seoltube.engine.Smoke")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
