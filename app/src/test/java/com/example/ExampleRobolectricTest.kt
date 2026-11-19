package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.core.usb.BridgeForegroundService
import com.example.core.usb.BridgeTelemetryBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("CarLife Bridge", appName)
  }

  @Test
  fun `bridge foreground service starts cleanly in standby without USB or MP permission`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val intent = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_START_PROJECTION
      putExtra(BridgeForegroundService.EXTRA_WIDTH, 800)
      putExtra(BridgeForegroundService.EXTRA_HEIGHT, 480)
      putExtra(BridgeForegroundService.EXTRA_FPS, 30)
      putExtra(BridgeForegroundService.EXTRA_BITRATE, 1_200_000)
    }

    val serviceController = Robolectric.buildService(BridgeForegroundService::class.java, intent)
    val service = serviceController.create().startCommand(0, 0).get()

    assertTrue(BridgeTelemetryBus.runtimeState.value.isServiceRunning)
    val shadowService = Shadows.shadowOf(service)
    assertTrue(shadowService.isLastForegroundNotificationAttached)

    serviceController.destroy()
  }

  @Test
  fun `main activity launches cleanly on Android 16 SDK 36`() {
    val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = activityController.get()

    assertTrue(!activity.isFinishing)

    activityController.pause().resume().destroy()
  }
}
