plugins {
	alias(libs.plugins.recorderapp.android.library)
	alias(libs.plugins.recorderapp.hilt)
	alias(libs.plugins.recorderapp.compose.compiler)
}

android {
	namespace = "com.eva.feature_recorder"
	buildFeatures {
		compose = true
	}
}

dependencies {

	//navigation
	implementation(libs.androidx.navigation.compose)
	implementation(libs.androidx.hilt.navigation.compose)

	//lifecycle service
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.concurrent.futures.ktx)
	implementation("com.google.guava:guava:33.4.8-android")

	implementation(project(":core:ui"))
	implementation(project(":core:utils"))
	implementation(project(":data:recorder"))
	implementation(project(":data:database"))
	implementation(project(":data:recordings"))
	implementation("androidx.camera:camera-camera2:1.5.3")
	implementation("androidx.camera:camera-lifecycle:1.5.3")
	implementation("androidx.camera:camera-view:1.5.3")
	implementation("io.coil-kt:coil-compose:2.7.0")
}
