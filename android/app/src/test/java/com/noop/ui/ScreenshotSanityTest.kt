package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Infra sanity check: renders one design-system component to a PNG on the JVM
 * (Robolectric native graphics). If this test records an image, the screenshot
 * pipeline works and screen-level tests can trust it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp-420dpi")
class ScreenshotSanityTest {

    @Before
    fun stillFrames() { Motion.animationsEnabled = false }

    @Test
    fun ringRenders() {
        captureRoboImage("build/outputs/roborazzi/sanity_ring.png") {
            NoopTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.surfaceBase)
                        .padding(24.dp),
                ) {
                    RecoveryRing(score = 74.0, supporting = "HRV 61 ms · RHR 61")
                }
            }
        }
    }
}
