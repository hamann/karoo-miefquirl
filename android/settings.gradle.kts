pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Gradle only reads gradle.properties for project properties; local.properties
// is an Android Gradle Plugin convention and only sdk.dir/ndk.dir are picked up
// from it. Load it by hand so credentials can live in the gitignored file that
// everyone expects them to live in.
val localProperties = java.util.Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(key: String, env: String): String =
    localProperties.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: System.getenv(env)
        ?: ""

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // karoo-ext is published to GitHub Packages, which demands
        // authentication even for public packages. The token needs the
        // `read:packages` scope specifically — `repo` alone returns 401.
        //
        // The credential belongs to your GitHub account, not to this project,
        // so the best home for it is ~/.gradle/gradle.properties (chmod 600),
        // which Gradle merges into every build:
        //
        //   gpr.user=your-github-username
        //   gpr.key=ghp_xxxxxxxxxxxx
        //
        // android/local.properties (gitignored) works too if you would rather
        // keep it per-project, as do GITHUB_ACTOR / GITHUB_TOKEN in the
        // environment — which is what CI should use.
        maven {
            name = "karoo-ext"
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = secret("gpr.user", "GITHUB_ACTOR")
                password = secret("gpr.key", "GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "miefquirl"
include(":app")
include(":headwind")
