plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.oauth"

    // fork 保留：app 的 pre build type 需要库模块同步暴露 pre 变体
    buildTypes {
        create("pre") {
            initWith(getByName("release"))
        }
    }
}

dependencies {
    api(libs.okhttp)

    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    testImplementation(libs.junit)
}
