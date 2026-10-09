pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // GeckoView (the Gecko rendering engine + SpiderMonkey, packaged as an Android library) is
    // published by Mozilla here and is not mirrored on Maven Central. The content filter keeps this
    // repository from being consulted for anything else.
    maven {
      url = uri("https://maven.mozilla.org/maven2/")
      content { includeGroup("org.mozilla.geckoview") }
    }
  }
}

rootProject.name = "GeckoBrowser"

include(":app")
