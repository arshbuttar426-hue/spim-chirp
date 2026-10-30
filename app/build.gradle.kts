plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

abstract class PrepareKotlinBuildScriptModel : DefaultTask() {
  @get:OutputFile
  abstract val output: RegularFileProperty

  @TaskAction
  fun run() {
    output.get().asFile.writeText(
      """
      {
        "version": "2.0.21",
        "sourceFiles": [],
        "classpath": []
      }
      """.trimIndent()
    )
  }
}

tasks.register<PrepareKotlinBuildScriptModel>("prepareKotlinBuildScriptModel") {
  output.set(layout.buildDirectory.file("kotlin-build-script-model.json"))
}

android {
  namespace = "com.prima.spimchirp"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.prima.spimchirp"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation(platform("androidx.compose:compose-bom:2024.10.01"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  debugImplementation("androidx.compose.ui:ui-tooling")
}
