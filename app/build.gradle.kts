import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.jetbrains.kotlin.android)
	// custom plugins
	alias(libs.plugins.recorderapp.hilt)
	alias(libs.plugins.recorderapp.compose.compiler)
}

android {
	namespace = "com.eva.recorderapp"
	compileSdk = libs.versions.compileSdk.get().toInt()
	ndkVersion = "28.2.13676358"

	defaultConfig {
		applicationId = "com.gujiu502.lectureframe"
		minSdk = libs.versions.minSdk.get().toInt()
		targetSdk = libs.versions.compileSdk.get().toInt()
		versionCode = 4
		versionName = "0.3.0"
		ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
		val webClientId = providers.gradleProperty("GOOGLE_WEB_CLIENT_ID").orElse(providers.environmentVariable("GOOGLE_WEB_CLIENT_ID")).getOrElse("")
		require(webClientId.isEmpty() || webClientId.matches(Regex("[A-Za-z0-9.-]+\\.apps\\.googleusercontent\\.com")))
		buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClientId\"")

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		vectorDrawables {
			useSupportLibrary = true
		}
	}

	androidResources {
		localeFilters.addAll(setOf("zh"))
	}

	signingConfigs {
        val properties = Properties()
        rootProject.file(".signing/release.properties").takeIf { it.exists() }?.inputStream()?.use { properties.load(it) }
        fun secret(name: String): String? = System.getenv(name) ?: properties.getProperty(name)
        if (rootProject.file(".signing/release.jks").exists()) create("release") {
            storeFile = rootProject.file(".signing/release.jks")
            storePassword = secret("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = secret("ANDROID_KEY_ALIAS")
            keyPassword = secret("ANDROID_KEY_PASSWORD")
        }
    }

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			multiDexEnabled = true
			// change the signing config if release is not found
			signingConfig = signingConfigs.findByName("release")
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}
		debug {
			applicationIdSuffix = ".debug"
			resValue("string", "app_name", "照片關鍵幀錄音（測試版）")
		}
	}
	flavorDimensions += "distribution"
	productFlavors {
		create("direct") { dimension = "distribution" }
		create("play") { dimension = "distribution" }
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	buildFeatures {
		compose = true
		buildConfig = true
	}
	packaging {
		resources {
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
		}
	}
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_17
	}
}

dependencies {

	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.navigation.compose)
	implementation(libs.androidx.core.splashscreen)
	implementation(libs.work.runtime.ktx)
	implementation(libs.androidx.hilt.work)

	implementation(project(":core:utils"))
	implementation(project(":data:database"))
	implementation(libs.androidx.room.ktx)
	implementation(project(":data:cloud"))
	"playImplementation"("com.google.android.play:app-update:2.1.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
	implementation(project(":core:ui"))
	implementation(project(":data:worker"))
	implementation(project(":data:interactions"))
	implementation(project(":feature:categories"))
	implementation(project(":feature:player"))
	implementation(project(":feature:recorder"))
	implementation(project(":feature:recordings"))
	implementation(project(":feature:editor"))
	implementation(project(":feature:settings"))
	implementation(project(":feature:widget"))
	implementation(project(":feature:onboarding"))

	// android testing
	androidTestImplementation(libs.androidx.runner)
}
