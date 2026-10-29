import java.awt.Font as AwtFont
import java.awt.font.FontRenderContext
import java.awt.geom.PathIterator
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.junit5)
    alias(libs.plugins.room)
}

/**
 * K17: Windows 11 güç ikonları. Lisans Segoe fontunun dağıtılmasına izin vermediği için glifler git'e girmez;
 * her derlemede build/ altına üretilir. İkon başına öncelik (README › "Windows güç ikonları"):
 *  1. app/winicons/custom/<ad>.svg  (kendi SVG'n; git'e girmez)
 *  2. C:\Windows\Fonts\SegoeIcons.ttf (Windows 11'in birebir glifi)
 *  3. app/winicons/fallback/win_<ad>.xml (Fluent UI System Icons, MIT)
 */
abstract class GenerateWinIcons : DefaultTask() {
    @get:Input abstract val fontPath: Property<String>
    @get:Input abstract val fontStamp: Property<String>
    @get:InputDirectory abstract val fallbackDir: DirectoryProperty
    @get:InputDirectory abstract val customDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile.resolve("drawable").apply { deleteRecursively(); mkdirs() }
        val font = File(fontPath.get())
        val custom = customDir.get().asFile
        val fallback = fallbackDir.get().asFile
        val awtFont = if (font.isFile) {
            System.setProperty("java.awt.headless", "true")
            AwtFont.createFont(AwtFont.TRUETYPE_FONT, font).deriveFont(2048f)
        } else null
        val glyphs = mapOf(
            "win_lock" to (0xE72E to "Lock"),
            "win_sleep" to (0xE708 to "QuietHours"),
            "win_hibernate" to (0xE823 to "Recent"),
            "win_restart" to (0xE777 to "UpdateRestore"),
            "win_shutdown" to (0xE7E8 to "PowerButton"),
        )
        for ((name, glyph) in glyphs) {
            val target = out.resolve("$name.xml")
            val svg = custom.resolve(name.removePrefix("win_") + ".svg")
            when {
                svg.isFile -> {
                    target.writeText(svgToVector(svg))
                    logger.lifecycle("Windows ikonu: ${svg.name} (custom)")
                }
                awtFont != null -> target.writeText(glyphToVector(awtFont, glyph.first, glyph.second))
                else -> {
                    fallback.resolve("$name.xml").copyTo(target)
                    logger.warn("SegoeIcons.ttf yok: $name için Fluent UI yedeği kullanılıyor.")
                }
            }
        }
    }

    private fun n(v: Float) = "%.1f".format(Locale.ROOT, v).removeSuffix(".0")

    private fun glyphToVector(awtFont: AwtFont, cp: Int, title: String): String {
            val em = 2048f
            val frc = FontRenderContext(null, true, true)
            val outline = awtFont.createGlyphVector(frc, String(Character.toChars(cp))).getGlyphOutline(0)
            val it = outline.getPathIterator(null)
            val c = FloatArray(6)
            val d = StringBuilder()
            // AWT: y aşağı, taban çizgisi 0; font kutusu 0..em → viewport y = em + y.
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    PathIterator.SEG_MOVETO -> d.append("M${n(c[0])} ${n(em + c[1])}")
                    PathIterator.SEG_LINETO -> d.append("L${n(c[0])} ${n(em + c[1])}")
                    PathIterator.SEG_QUADTO -> d.append("Q${n(c[0])} ${n(em + c[1])} ${n(c[2])} ${n(em + c[3])}")
                    PathIterator.SEG_CUBICTO -> d.append("C${n(c[0])} ${n(em + c[1])} ${n(c[2])} ${n(em + c[3])} ${n(c[4])} ${n(em + c[5])}")
                    PathIterator.SEG_CLOSE -> d.append("Z")
                }
                it.next()
            }
            val fillType = if (it.windingRule == PathIterator.WIND_EVEN_ODD) "evenOdd" else "nonZero"
            return """<?xml version="1.0" encoding="utf-8"?>
<!-- Üretildi (build): Segoe Fluent Icons U+${"%04X".format(cp)} ($title). Dağıtılmaz; bkz. assets/licenses/WINDOWS_ICONS.txt -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"
    android:viewportWidth="2048" android:viewportHeight="2048">
    <path android:fillColor="#FFFFFFFF" android:fillType="$fillType" android:pathData="$d" />
</vector>
"""
    }

    /**
     * Basit SVG → VectorDrawable: viewBox (yoksa width/height) + tüm <path d>, fill-rule korunur, renk beyaza
     * boyanır (uygulama tint uygular). transform, stroke, circle/rect desteklenmez: SVG'yi önce "nesneyi yola çevir"
     * ile tek dolgu yoluna indir (README).
     */
    private fun svgToVector(file: File): String {
        val text = file.readText()
        fun attr(tag: String, name: String) = Regex("""\s$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)
        val svgTag = Regex("""<svg\b[^>]*>""", RegexOption.IGNORE_CASE).find(text)?.value
            ?: throw GradleException("${file.name}: <svg> etiketi yok")
        val box = attr(svgTag, "viewBox")?.trim()?.split(Regex("""[\s,]+"""))?.map { it.toFloat() }
        val w = box?.get(2) ?: attr(svgTag, "width")?.removeSuffix("px")?.toFloat() ?: 24f
        val h = box?.get(3) ?: attr(svgTag, "height")?.removeSuffix("px")?.toFloat() ?: 24f
        val paths = Regex("""<path\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { m ->
            val d = attr(m.value, "d") ?: return@mapNotNull null
            val evenOdd = attr(m.value, "fill-rule") == "evenodd" || m.value.contains("fill-rule:evenodd")
            """    <path android:fillColor="#FFFFFFFF" android:fillType="${if (evenOdd) "evenOdd" else "nonZero"}" android:pathData="$d" />"""
        }.toList()
        if (paths.isEmpty()) throw GradleException("${file.name}: <path d=...> bulunamadı (yola çevirmeyi dene)")
        val minX = box?.get(0) ?: 0f
        val minY = box?.get(1) ?: 0f
        val group = minX != 0f || minY != 0f
        return buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("<!-- Üretildi (build): app/winicons/custom/${file.name} -->")
            appendLine("""<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"""")
            appendLine("""    android:viewportWidth="${n(w)}" android:viewportHeight="${n(h)}">""")
            if (group) appendLine("""  <group android:translateX="${n(-minX)}" android:translateY="${n(-minY)}">""")
            paths.forEach { appendLine(it) }
            if (group) appendLine("  </group>")
            appendLine("</vector>")
        }
    }
}

// -Pwol.segoeIcons=<yol> ile başka bir konum (ya da yedeği denemek için olmayan bir yol) verilebilir.
val segoeIcons = File(providers.gradleProperty("wol.segoeIcons").getOrElse("C:/Windows/Fonts/SegoeIcons.ttf"))
val generateWinIcons = tasks.register<GenerateWinIcons>("generateWinIcons") {
    fontPath.set(segoeIcons.absolutePath)
    fontStamp.set(if (segoeIcons.isFile) "${segoeIcons.length()}-${segoeIcons.lastModified()}" else "yok")
    fallbackDir.set(layout.projectDirectory.dir("winicons/fallback"))
    customDir.set(layout.projectDirectory.dir("winicons/custom"))
    outputDir.set(layout.buildDirectory.dir("generated/winicons/res"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateWinIcons, GenerateWinIcons::outputDir)
    }
}

// Ortak protokol klasörü (repo kökü/protocol). vectors.json hem testlere hem debug asset'ine gider.
val protocolDir = rootProject.layout.projectDirectory.dir("../protocol")

android {
    namespace = "com.kdgames.wake_on_lan"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kdgames.wake_on_lan"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("debug") {
            assets.directories.add(protocolDir.asFile.path)
            // Room göç testleri (MigrationTestHelper, Robolectric) şema JSON'larını debug asset'inden okur.
            assets.directories.add("$projectDir/schemas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

room {
    // Şema geçmişi git'te tutulur; ileride migration yazmak için gerekli.
    schemaDirectory("$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    systemProperty("wol.vectors", protocolDir.file("vectors.json").asFile.absolutePath)
    // Robolectric'in SDK 36 sandbox'ı Java 21 ister; uygulama bytecode'u 17'de kalır.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    implementation(libs.haze)
    implementation(libs.haze.blur)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
