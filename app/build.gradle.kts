plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A "casca" é o app Android em si (a moldura). As telas do Journal vêm do site e se atualizam sozinhas;
// o número da casca só sobe (CASCA.txt) quando este app muda. O Journal compara esse número em Ajustes.
val casca = file("../CASCA.txt").readText().trim().toInt()
// No GitHub Actions cada build ganha um número maior, para instalar por cima da versão antiga.
val numeroBuild = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.jvhubert.journal"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jvhubert.journal"
        minSdk = 26
        targetSdk = 35
        versionCode = numeroBuild
        versionName = "casca $casca"
        manifestPlaceholders["casca"] = casca.toString()
    }

    signingConfigs {
        create("release") {
            // A chave fica fora do repositório; o GitHub Actions recebe pelos secrets.
            System.getenv("CHAVE_ARQUIVO")?.let { arquivo ->
                storeFile = file(arquivo)
                storeType = "pkcs12"
                storePassword = System.getenv("CHAVE_SENHA")
                keyAlias = System.getenv("CHAVE_ALIAS")
                keyPassword = System.getenv("CHAVE_SENHA")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
}
