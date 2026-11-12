package com.kdgames.wake_on_lan.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.kdgames.wake_on_lan"
private const val WAIT_MS = 5_000L

/** Ortak senaryolar (UiAutomator; metin ve erişilebilirlik etiketleriyle). */
private fun MacrobenchmarkScope.waitForHome() {
    device.wait(Until.hasObject(By.text("Cihazlar")), WAIT_MS)
    device.waitForIdle()
}

private fun MacrobenchmarkScope.switchTabs() {
    device.findObject(By.text("Otomasyon"))?.click()
    device.wait(Until.hasObject(By.textContains("Zamanlayıcılar telefondan")), WAIT_MS)
    device.waitForIdle()
    device.findObject(By.text("Cihazlar"))?.click()
    device.waitForIdle()
}

/** Kartlar erişilebilirlik etiketinde "ad, durum" taşır; ikinci karta, sonra ilkine dokun (hero morph). */
private fun MacrobenchmarkScope.heroMorph() {
    val cards = device.findObjects(By.descContains(", ").clickable(true))
    if (cards.size < 2) return
    cards[1].click()
    device.waitForIdle()
    device.findObjects(By.descContains(", ").clickable(true)).firstOrNull()?.click()
    device.waitForIdle()
}

private fun MacrobenchmarkScope.openSheet() {
    device.findObject(By.desc("Cihaz ekle"))?.click()
    device.wait(Until.hasObject(By.textContains("Cihaz ekle")), WAIT_MS)
    Thread.sleep(900)
    device.pressBack()
    device.waitForIdle()
}

private fun MacrobenchmarkScope.scrollList() {
    val w = device.displayWidth
    val h = device.displayHeight
    device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 12)
    device.waitForIdle()
    device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 12)
    device.waitForIdle()
}

/**
 * S13 performans ölçümleri (spec 17 faz 12): açılış, sekme geçişi, hero morph, panel açma, liste kaydırma.
 * Sonuçlar `macrobenchmark/build/outputs/connected_android_test_additional_output/` altında JSON olarak çıkar.
 */
@RunWith(AndroidJUnit4::class)
class Benchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startup() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        pressHome()
        startActivityAndWait()
        waitForHome()
    }

    private fun frames(block: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForHome()
        },
        measureBlock = block,
    )

    @Test fun tabSwitch() = frames { repeat(3) { switchTabs() } }

    @Test fun heroMorph() = frames { repeat(3) { heroMorph() } }

    @Test fun sheetOpen() = frames { repeat(2) { openSheet() } }

    @Test fun listScroll() = frames { repeat(3) { scrollList() } }
}

/** Baseline profile: açılış + en sık yollar. Çıktı app/src/main/baseline-prof.txt'ye kopyalanır. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, maxIterations = 6, stableIterations = 2) {
        pressHome()
        startActivityAndWait()
        waitForHome()
        switchTabs()
        heroMorph()
        scrollList()
        openSheet()
    }
}
