plugins {
    alias(libs.plugins.recorderapp.android.library)
    alias(libs.plugins.recorderapp.hilt)
}
android { namespace = "com.eva.cloud" }
dependencies {
    implementation(project(":data:database"))
    implementation(libs.androidx.room.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    api("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
