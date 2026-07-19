import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
    kotlin("plugin.serialization") version "1.9.24"
}

// Numéro de version qui change à CHAQUE build sur GitHub Actions (via la
// variable GITHUB_RUN_NUMBER, fournie automatiquement par GitHub, incrémentée
// à chaque exécution du workflow - aucune configuration supplémentaire
// nécessaire côté repo).
//
// C'est important : Windows Installer (.msi) se base sur ce numéro pour
// savoir s'il doit remplacer une installation existante. S'il reste identique
// d'un build à l'autre (ex: "1.0.0" codé en dur), Windows considère souvent
// que la version "est déjà installée" et ne remplace rien du tout, même si
// le contenu a réellement changé - symptôme typique : "j'installe la
// nouvelle version mais rien ne change", alors que le nouveau code est
// pourtant bien présent dans l'installeur généré.
//
// En local (pas de CI), retombe sur "1.0.0" - si tu testes des installations
// répétées en local, augmente ce nombre à la main ou désinstalle l'ancienne
// version depuis "Applications et fonctionnalités" avant de réinstaller.
val appVersion = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .map { "1.0.$it" }
    .getOrElse("1.0.0")

group = "com.solplay.desktop"
version = appVersion

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Lecture vidéo : ExoPlayer (Android) n'existe pas sur desktop.
    //
    // Changement de moteur (v18) : abandon de VLC/vlcj au profit d'un
    // décodage FFmpeg "maison" via javacv (FFmpegFrameGrabber). Raison :
    // vlcj intègre VLC comme composant natif "lourd" (SwingPanel), sur
    // lequel Compose ne peut PAS dessiner par-dessus proprement (pas de
    // vrais calques/overlays, contrôles obligés d'être des voisins de mise
    // en page plutôt que superposés) - limite structurelle de Compose
    // Desktop avec tout composant vidéo natif, pas spécifique à VLC.
    //
    // javacv-platform embarque les binaires FFmpeg pour chaque OS (dont
    // Windows) : chaque frame vidéo est décodée nous-mêmes et convertie en
    // simple image Compose (voir VideoDecoder.kt) - donc plus de composant
    // natif du tout, overlays/centrage 100% libres comme n'importe quel
    // autre écran Compose. Bonus : l'utilisateur n'a plus besoin d'installer
    // VLC séparément, FFmpeg est embarqué dans l'app.
    // javacv (sans "-platform") + classifier windows-x86_64 UNIQUEMENT :
    // "javacv-platform" embarque par défaut les binaires FFmpeg de TOUS les
    // OS (Windows, Linux, macOS, Android...), soit plusieurs centaines de Mo
    // à télécharger inutilement - vu la connexion limitée, on ne prend que
    // ce qui sert réellement pour un build/usage Windows.
    implementation("org.bytedeco:javacv:1.5.10") {
        exclude(group = "org.bytedeco", module = "ffmpeg")
        exclude(group = "org.bytedeco", module = "opencv")
        exclude(group = "org.bytedeco", module = "flycapture")
        exclude(group = "org.bytedeco", module = "spinnaker")
        exclude(group = "org.bytedeco", module = "libdc1394")
        exclude(group = "org.bytedeco", module = "libfreenect")
        exclude(group = "org.bytedeco", module = "libfreenect2")
        exclude(group = "org.bytedeco", module = "librealsense")
        exclude(group = "org.bytedeco", module = "librealsense2")
        exclude(group = "org.bytedeco", module = "videoinput")
        exclude(group = "org.bytedeco", module = "artoolkitplus")
        exclude(group = "org.bytedeco", module = "leptonica")
        exclude(group = "org.bytedeco", module = "tesseract")
    }
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:windows-x86_64")

    // Requêtes HTTP vers Firebase REST, Xtream, TMDB, M3U - remplace les
    // appels utilisant le SDK Android Firebase (indisponible hors Android).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // Chiffrement au repos des identifiants Xtream (voir SecureStorage.kt /
    // PlaylistStore.kt) via l'API Windows DPAPI - jna-platform embarque
    // Crypt32Util (wrapper haut-niveau de CryptProtectData/CryptUnprotectData)
    // et entraîne "jna" en dépendance transitive.
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
}

// --- Génère com.solplay.iptv.BuildConfig, absent d'un projet Kotlin/JVM
// classique (contrairement à un projet Android, où c'est une classe générée
// automatiquement). TmdbClient.kt référence BuildConfig.TMDB_API_KEY tel
// quel (fichier repris sans modification depuis l'app Android) : sans cette
// génération, le module ne compile pas du tout.
//
// Valeur de la clé, par ordre de priorité :
//   1. -PtmdbApiKey=xxx sur la ligne de commande (ou dans gradle.properties en local)
//   2. variable d'environnement TMDB_API_KEY (utilisée par le workflow GitHub
//      Actions ci-dessous, elle-même alimentée par le secret de dépôt du même nom)
//   3. chaîne vide - l'app démarre quand même, simplement sans affiches TMDB
//      (TmdbClient le détecte et journalise un message clair au lieu de planter)
val generatedBuildConfigDir = layout.buildDirectory.dir("generated/solplayBuildConfig/kotlin")

val generateBuildConfig by tasks.registering {
    outputs.dir(generatedBuildConfigDir)

    doLast {
        val tmdbApiKey = ((project.findProperty("tmdbApiKey") as? String) ?: System.getenv("TMDB_API_KEY") ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        val packageDir = generatedBuildConfigDir.get().asFile.resolve("com/solplay/iptv")
        packageDir.mkdirs()
        packageDir.resolve("BuildConfig.kt").writeText(
            """
            package com.solplay.iptv

            // Fichier généré automatiquement par la tâche Gradle "generateBuildConfig"
            // (voir build.gradle.kts) - NE PAS éditer à la main, et ne pas committer
            // (le dossier build/ est ignoré par git).
            object BuildConfig {
                const val TMDB_API_KEY: String = "$tmdbApiKey"
                const val VERSION_NAME: String = "${project.version}"
            }
            """.trimIndent()
        )
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir(generatedBuildConfigDir)
    // onPointerEvent (utilisé dans PlayerScreen pour détecter l'activité
    // souris et ré-afficher les contrôles) est marqué @ExperimentalComposeUiApi
    // par Compose - sans cette autorisation explicite, Kotlin le traite comme
    // une ERREUR de compilation ("This API is experimental..."), pas juste un
    // avertissement. C'est ce qui faisait échouer :compileKotlin.
    sourceSets.all {
        languageSettings {
            optIn("androidx.compose.ui.ExperimentalComposeUiApi")
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildConfig)
}

compose.desktop {
    application {
        mainClass = "com.solplay.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "SolPlay"
            packageVersion = appVersion
            windows {
                menuGroup = "SolPlay"
                // upgradeUuid figé pour que les mises à jour MSI remplacent
                // proprement l'installation précédente au lieu d'en créer une seconde.
                upgradeUuid = "8f2c9b3a-6e2d-4a1b-9c3e-1d7f5a2b8e40"
                // Icône de l'app (identique à celle de la version Android),
                // convertie en .ico multi-résolutions (16 à 256px) à partir du
                // logo source. Utilisée pour l'exécutable, le raccourci du
                // menu Démarrer, et l'installeur .msi lui-même.
                iconFile.set(project.file("src/main/resources/solplay.ico"))

                // IMPORTANT : ces deux options sont à FALSE par défaut chez
                // jpackage/Compose Desktop. Sans elles, le .msi installe bien
                // l'app (elle tourne, elle apparaît dans "Applications et
                // fonctionnalités") mais ne crée AUCUN raccourci visible nulle
                // part - ni sur le Bureau, ni dans le menu Démarrer, donc pas
                // trouvable non plus via la barre de recherche Windows (qui
                // n'indexe que les raccourcis du menu Démarrer, pas les .exe
                // dans Program Files). C'était la cause du souci signalé.
                menu = true      // entrée dans le menu Démarrer -> apparaît aussi dans la recherche Windows
                shortcut = true  // raccourci créé directement sur le Bureau
            }
        }
    }
}
