plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "com.gakj.weilie"
version = "0.1.0"

val ktorVersion = "2.3.12"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    // Ktor 服务端：核心 + Netty 引擎 + WebSocket + JSON 内容协商
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // kotlinx.serialization 运行时（与 Android 客户端同为 1.7.3，协议可互相序列化）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 日志实现
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // 测试
    testImplementation(kotlin("test"))
    // useJUnitPlatform() 需要引擎在测试运行时类路径上
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

// 固定 JDK 17（与客户端工具链一致）；本机 JAVA_HOME 已指向 JDK17 时直接复用
kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.gakj.weilie.server.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * 手工打可执行 fat jar（Docker 运行阶段 / 直接 java -jar 均可）。
 * 产物名固定为 build/libs/weilie-server-all.jar。
 */
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "打包可执行 fat jar，供 Docker 与直接运行使用"
    archiveBaseName.set("weilie-server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.extension.equals("jar", ignoreCase = true) }
            .map { zipTree(it) }
    })
    // 去掉签名文件，避免合并后的 jar 校验失败
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// 让 build 一并产出 fat jar，保证 gradle build 后即可 java -jar 运行
tasks.named("build") {
    dependsOn("fatJar")
}
