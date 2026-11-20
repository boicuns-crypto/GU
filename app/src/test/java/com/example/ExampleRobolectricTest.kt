package com.example

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.example.core.projection.CockpitProjectionManager
import com.example.core.projection.VideoSourceMode
import com.example.core.usb.BridgeForegroundService
import com.example.core.usb.BridgeTelemetryBus
import com.example.feature.cockpit.CarLifeLauncherRepository
import com.example.feature.cockpit.InstalledAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    assertEquals(VideoSourceMode.CANVAS_TEST_CARD, BridgeTelemetryBus.runtimeState.value.videoSourceMode)
    val shadowService = Shadows.shadowOf(service)
    assertTrue(shadowService.isLastForegroundNotificationAttached)

    serviceController.destroy()
  }

  @Test
  fun `bridge foreground service switches video source mode between Canvas Test Card and VirtualDisplay`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = Robolectric.buildService(BridgeForegroundService::class.java).create()
    val service = controller.get()

    val switchToVd = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_SET_VIDEO_SOURCE_MODE
      putExtra(BridgeForegroundService.EXTRA_VIDEO_SOURCE_MODE, VideoSourceMode.VIRTUAL_DISPLAY.id)
    }
    service.onStartCommand(switchToVd, 0, 1)
    assertEquals(VideoSourceMode.VIRTUAL_DISPLAY, BridgeTelemetryBus.runtimeState.value.videoSourceMode)

    val switchToCanvas = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_SET_VIDEO_SOURCE_MODE
      putExtra(BridgeForegroundService.EXTRA_VIDEO_SOURCE_MODE, VideoSourceMode.CANVAS_TEST_CARD.id)
    }
    service.onStartCommand(switchToCanvas, 0, 2)
    assertEquals(VideoSourceMode.CANVAS_TEST_CARD, BridgeTelemetryBus.runtimeState.value.videoSourceMode)

    controller.destroy()
  }

  @Test
  fun `cockpit projection manager renders synthetic Jetta VS7 test card onto 800x480 Canvas`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = CockpitProjectionManager(
      context = context,
      onEncodedVideoNal = { _, _, _ -> }
    )
    assertEquals(VideoSourceMode.CANVAS_TEST_CARD, manager.videoSourceMode)

    val drawnTexts = mutableListOf<String>()
    val drawnRectLefts = mutableListOf<Float>()
    val drawnRectColors = mutableListOf<Int>()

    val bitmap = Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888)
    val recordingCanvas = object : Canvas(bitmap) {
      override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: android.graphics.Paint) {
        drawnRectLefts.add(left)
        drawnRectColors.add(paint.color)
        super.drawRect(left, top, right, bottom, paint)
      }

      override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
        drawnTexts.add(text)
        super.drawText(text, x, y, paint)
      }
    }

    manager.drawTestCardFrame(
      canvas = recordingCanvas,
      width = 800,
      height = 480,
      frameSeq = 123L,
      timeWithMsString = "12:34:56.789"
    )

    assertTrue("Expected bright background + SMPTE bars + sweep rects", drawnRectColors.size >= 18)
    assertTrue("Expected distinct colors for blue/green split and SMPTE bars", drawnRectColors.distinct().size >= 8)
    assertTrue(drawnTexts.contains("JETTA VS7 TEST"))
    assertTrue(drawnTexts.contains("TIME 12:34:56.789"))
    assertTrue(drawnTexts.contains("FRAME #123"))

    val firstFrameRects = drawnRectLefts.toList()
    drawnRectLefts.clear()
    drawnTexts.clear()

    manager.drawTestCardFrame(
      canvas = recordingCanvas,
      width = 800,
      height = 480,
      frameSeq = 124L,
      timeWithMsString = "12:34:56.822"
    )

    assertTrue(drawnTexts.contains("FRAME #124"))
    assertNotEquals(firstFrameRects, drawnRectLefts)

    bitmap.recycle()
    kotlinx.coroutines.runBlocking { manager.stopProjection() }
  }

  @Test
  fun `main activity launches cleanly on Android 16 SDK 36`() {
    val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = activityController.get()

    assertTrue(!activity.isFinishing)

    activityController.pause().resume().destroy()
  }

  @Test
  fun `cockpit launcher uses NEW_TASK and RESET_TASK_IF_NEEDED and falls back when VirtualDisplay rejects launch`() {
    val baseContext = ApplicationProvider.getApplicationContext<Context>()
    var vdAttempted = false
    var fallbackIntent: Intent? = null

    val wrappedContext = object : ContextWrapper(baseContext) {
      override fun startActivity(intent: Intent?, options: Bundle?) {
        if (options != null) {
          vdAttempted = true
          throw SecurityException("Android 16 VirtualDisplay launch blocked")
        }
        fallbackIntent = intent
      }

      override fun startActivity(intent: Intent?) {
        fallbackIntent = intent
      }
    }

    val testApp = InstalledAppInfo(
      packageName = baseContext.packageName,
      activityName = MainActivity::class.java.name,
      label = "CarLife Bridge",
      iconBitmap = null
    )

    val launched = CarLifeLauncherRepository.launchAppOnVirtualDisplay(
      context = wrappedContext,
      app = testApp,
      targetDisplayId = 2
    )

    assertTrue(launched)
    assertTrue("Expected VirtualDisplay ActivityOptions attempt first", vdAttempted)
    val launchedIntent = fallbackIntent
    assertTrue("Expected fallback startActivity(intent) call", launchedIntent != null)
    val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    assertEquals(expectedFlags, launchedIntent!!.flags and expectedFlags)
    assertTrue(CarLifeLauncherRepository.lastLaunchStatus.value.contains("VD #2"))
  }
}
