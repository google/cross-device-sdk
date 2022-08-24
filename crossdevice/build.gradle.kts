import com.google.protobuf.gradle.generateProtoTasks
import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc

plugins {
  id("com.android.library") version "7.2.0"
  id("com.google.protobuf") version "0.8.19"
  id("kotlin-android")
  id("maven-publish")
}

android {
  compileSdk = 31
  buildToolsVersion = "33.0.0"
  /**
   * The defaultConfig block encapsulates default settings and entries for all build variants, and
   * can override some attributes in main/AndroidManifest.xml dynamically from the build system. You
   * can configure product flavors to override these values for different versions of your app.
   */
  defaultConfig {
    minSdk = 19
    targetSdk = 31
  }

  dependencies {
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("androidx.annotation:annotation:1.4.0")
    implementation("androidx.appcompat:appcompat:1.4.1")
    implementation("androidx.activity:activity-ktx:1.4.0")
    implementation("com.google.android.gms:play-services-nearby:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.6.0")
    implementation("com.google.android.gms:play-services-dtdi:16.0.0-beta01")
    implementation("com.google.android.datatransport:transport-api:3.0.0")
    implementation("com.google.android.datatransport:transport-runtime:3.1.6")
    implementation("com.google.android.datatransport:transport-backend-cct:3.1.6")
    implementation("com.google.protobuf:protobuf-javalite:3.21.3")
    implementation("com.google.protobuf:protobuf-kotlin-lite:3.21.3")
  }

  publishing {
    singleVariant("release") {
      withSourcesJar()
      withJavadocJar()
    }
  }
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:3.21.3" }
  generateProtoTasks {
    all().forEach { task ->
      task.plugins {
        id("kotlin")
        id("java") { option("lite") }
      }
    }
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

afterEvaluate {
  // Create the task `./gradlew publishSdkPublicationToMavenRepository` to create a maven repo
  // under `build/m2repo`, which can then be downloaded by the developer into maven local.
  publishing {
    publications {
      create<MavenPublication>("sdk") {
        groupId = "com.google.ambient.crossdevice"
        artifactId = "crossdevice"
        version = "0.1.0-preview01"
        pom {
          url.set("https://github.com/google/cross-device-sdk")
          licenses {
            license {
              name.set("The Apache License, Version 2.0")
              url.set("http://www.apache.org/licenses/LICENSE-2.0")
            }
          }
        }

        from(components["release"])
      }
    }
    repositories { maven { url = uri("${rootProject.buildDir}/m2repo") } }
  }
}
