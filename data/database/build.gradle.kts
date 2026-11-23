plugins {
	alias(libs.plugins.recorderapp.android.library)
	alias(libs.plugins.recorderapp.hilt)
	alias(libs.plugins.recorderapp.room)
}
android {
	namespace = "com.eva.database"
}

dependencies {
	implementation(project(":core:utils"))
	implementation(libs.androidx.media3.transformer)
	implementation(libs.androidx.media3.effects)
}
