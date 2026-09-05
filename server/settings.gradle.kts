pluginManagement {
    repositories {
        // 国内镜像优先，保证家庭网络环境下依赖可解析
        maven("https://mirrors.cloud.tencent.com/nexus/content/groups/public")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "bookkeeping-sync-server"
