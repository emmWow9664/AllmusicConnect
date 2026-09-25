import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources

// 按目标世代选择 loom 版本与插件 id：
// - 26.x（官方命名环境）：loom 1.17-SNAPSHOT，长 id net.fabricmc.fabric-loom（原生支持非混淆环境，无需 mappings）
// - 1.21.x（混淆环境）：loom 1.17.20，短 id fabric-loom + 官方映射 + modImplementation（经 AllMusic 官方项目与本工程验证）
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
    dependencies {
        val mc = gradle.startParameter.projectProperties.getOrDefault("mc", "26_2")
            .replace("-", "_").lowercase()
        val loomVersion = if (mc.startsWith("26_")) "1.17-SNAPSHOT" else "1.17.20"
        classpath("net.fabricmc:fabric-loom:$loomVersion")
    }
}

val targetForPlugin = (project.findProperty("mc") as String? ?: "26_2")
    .replace("-", "_").lowercase()
apply(plugin = if (targetForPlugin.startsWith("26_")) "net.fabricmc.fabric-loom" else "fabric-loom")

data class McVer(val mc: String, val api: String, val java: Int, val gen: String)

// 支持的 Minecraft 版本（fabric-api 版本号取自 Fabric maven 对应小版本的官方发布）
val mcMap = linkedMapOf(
    "1_21" to McVer("1.21", "0.102.0+1.21", 21, "legacy"),
    "1_21_1" to McVer("1.21.1", "0.116.15+1.21.1", 21, "legacy"),
    "1_21_2" to McVer("1.21.2", "0.105.4+1.21.2", 21, "legacy"),
    "1_21_3" to McVer("1.21.3", "0.114.1+1.21.3", 21, "legacy"),
    "1_21_4" to McVer("1.21.4", "0.119.4+1.21.4", 21, "legacy"),
    "1_21_5" to McVer("1.21.5", "0.128.2+1.21.5", 21, "legacy"),
    "1_21_6" to McVer("1.21.6", "0.128.2+1.21.6", 21, "compat_1_21_6"),
    "1_21_7" to McVer("1.21.7", "0.128.2+1.21.7", 21, "compat_1_21_6"),
    "1_21_8" to McVer("1.21.8", "0.136.1+1.21.8", 21, "compat_1_21_6"),
    "1_21_9" to McVer("1.21.9", "0.134.1+1.21.9", 21, "compat_1_21_11"),
    "1_21_10" to McVer("1.21.10", "0.138.4+1.21.10", 21, "compat_1_21_11"),
    "1_21_11" to McVer("1.21.11", "0.141.4+1.21.11", 21, "compat_1_21_11"),
    "26_1" to McVer("26.1", "0.145.1+26.1", 25, "modern"),
    "26_1_2" to McVer("26.1.2", "0.152.1+26.1.2", 25, "modern"),
    "26_2" to McVer("26.2", "0.152.1+26.2", 25, "modern"),
)

// 目标版本：gradlew build -Pmc=1_21_11（默认 26_2）
val target = (project.findProperty("mc") as String? ?: "26_2")
    .replace("-", "_")
    .lowercase()
val mcVer = mcMap[target] ?: error("未知目标版本: $target，可选: ${mcMap.keys.joinToString()}")
val mcVersion = mcVer.mc
val generation = mcVer.gen

group = "com.example"
version = "1.2"

the<JavaPluginExtension>().apply {
    sourceCompatibility = JavaVersion.toVersion(mcVer.java)
    targetCompatibility = JavaVersion.toVersion(mcVer.java)
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

// 动态应用插件（无 plugins{} 块），统一用扩展 API + 字符串形式声明配置
val loomApi = extensions.getByName("loom") as net.fabricmc.loom.api.LoomGradleExtensionAPI

// 编译期桩类（不打包进最终 jar，运行时使用 AllMusic 客户端模组中的真实类）
val stubsSourceSet = the<SourceSetContainer>().create("stubs") {
    java.srcDir("stubs/src")
}
the<SourceSetContainer>().named("main") {
    // 按世代加入 Compat 适配层源码（legacy=1.21.x / compat_1_21_11=1.21.11 专属 / modern=26.x）
    java.srcDir("src/${generation}/java")
}

dependencies {
    "minecraft"("com.mojang:minecraft:${mcVer.mc}")
    if (mcVer.java == 21) {
        // 1.21.x 世代（混淆环境）：官方映射 + mod 依赖处理（fabric-api access widener 等）
        "mappings"(loomApi.officialMojangMappings())
        "modImplementation"("net.fabricmc:fabric-loader:0.19.3")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:${mcVer.api}")
    } else {
        // 26.x 世代（官方命名环境）：无需显式映射
        "implementation"("net.fabricmc:fabric-loader:0.19.3")
        "implementation"("net.fabricmc.fabric-api:fabric-api:${mcVer.api}")
    }

    // AllMusic 编解码类（编译期引用，运行时由 AllMusic 客户端模组提供）
    "compileOnly"(files("libs/allmusic-codec.jar"))
    // 桩类输出加入主源集编译类路径（等效 compileClasspath += stubs.output）
    "compileOnly"(stubsSourceSet.output)
    // 桩类自身的编解码依赖
    "stubsCompileOnly"(files("libs/allmusic-codec.jar"))
}

tasks.named<ProcessResources>("processResources") {
    // mcVersion/version 作为任务输入：切换 -Pmc 构建时必须重新展开 fabric.mod.json，
    // 否则 Gradle 判定 UP-TO-DATE 复用上一次构建的版本约束（如 1.21.8 产物却写 >=1.21.9）
    inputs.property("version", project.version)
    inputs.property("mcVersion", mcVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "mcVersion" to mcVersion)
    }
}
if (generation == "modern") {
    // 26.x 官方命名环境：官方 jar 即官方名，运行时类名与编译名一致，直接发布 jar 产物
    tasks.named<Jar>("jar") {
        archiveFileName.set("AllmusicConnect-${project.version}-mc${mcVersion}.jar")
        destinationDirectory.set(file("build/libs"))
    }
} else {
    // 1.21.x 混淆环境：官方 jar 是混淆名（intermediary），发布产物必须经 remapJar 转换，
    // 否则运行时找不到官方名类（如 net.minecraft.commands.CommandBuildContext）导致 NoClassDefFoundError 崩溃
    tasks.named<Jar>("jar") {
        // 编译期 named jar（官方映射名），仅作为 remapJar 的输入，不作为发布产物
        archiveFileName.set("AllmusicConnect-${project.version}-mc${mcVersion}-dev.jar")
        destinationDirectory.set(file("build/libs"))
    }
    tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("remapJar") {
        // 发布产物：remap 为 intermediary 名（运行时 Fabric 生态标准命名）
        archiveFileName.set("AllmusicConnect-${project.version}-mc${mcVersion}.jar")
        destinationDirectory.set(file("build/libs"))
    }
    tasks.named("build") {
        dependsOn("remapJar")
    }
}
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}