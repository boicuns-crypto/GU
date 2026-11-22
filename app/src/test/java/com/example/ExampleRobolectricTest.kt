package com.example

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.example.core.audio.CarLifeAudioCaptureManager
import com.example.core.carlife.CarLifeHardKeyPacket
import com.example.core.carlife.CarLifeTouchPacket
import com.example.core.projection.AppMode
import com.example.core.projection.CockpitProjectionManager
import com.example.core.projection.VideoSourceMode
import com.example.core.shizuku.CarLifeShizukuManager
import com.example.core.shizuku.ICarLifeShizukuService
import com.example.core.usb.BridgeForegroundService
import com.example.core.usb.BridgeTelemetryBus
import com.example.feature.cockpit.AppLaunchMode
import com.example.feature.cockpit.CarLifeLauncherRepository
import com.example.feature.cockpit.InstalledAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    assertEquals(VideoSourceMode.SCREEN_MIRRORING, BridgeTelemetryBus.runtimeState.value.videoSourceMode)
    val shadowService = Shadows.shadowOf(service)
    assertTrue(shadowService.isLastForegroundNotificationAttached)

    serviceController.destroy()
  }

  @Test
  fun `bridge foreground service switches video source mode across Screen Mirroring, VirtualDisplay and Canvas Test Card`() {
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

    val switchToMirror = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_SET_VIDEO_SOURCE_MODE
      putExtra(BridgeForegroundService.EXTRA_VIDEO_SOURCE_MODE, VideoSourceMode.SCREEN_MIRRORING.id)
    }
    service.onStartCommand(switchToMirror, 0, 3)
    assertEquals(VideoSourceMode.SCREEN_MIRRORING, BridgeTelemetryBus.runtimeState.value.videoSourceMode)

    controller.destroy()
  }

  @Test
  fun `cockpit projection manager renders synthetic Jetta VS7 test card onto 800x480 Canvas`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = CockpitProjectionManager(
      context = context,
      onEncodedVideoNal = { _, _, _ -> }
    )
    assertEquals(VideoSourceMode.SCREEN_MIRRORING, manager.videoSourceMode)

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

    CarLifeLauncherRepository.setAppLaunchMode(AppLaunchMode.VIRTUAL_DISPLAY_IF_AVAILABLE)
    val launched = CarLifeLauncherRepository.launchAppOnVirtualDisplay(
      context = wrappedContext,
      app = testApp,
      targetDisplayId = 2
    )

    assertTrue(launched)
    assertTrue("Expected VirtualDisplay ActivityOptions attempt first", vdAttempted)
    val launchedIntent = fallbackIntent
    assertTrue("Expected fallback startActivity(intent) call without ActivityOptions", launchedIntent != null)
    val expectedFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    assertEquals(expectedFlags, launchedIntent!!.flags and expectedFlags)
    assertTrue(CarLifeLauncherRepository.lastLaunchStatus.value.contains("VD #2"))

    // Mode 2: SCREEN_MIRRORING must skip ActivityOptions entirely and call startActivity(intent) directly
    vdAttempted = false
    fallbackIntent = null
    CarLifeLauncherRepository.setAppLaunchMode(AppLaunchMode.SCREEN_MIRRORING)
    val mirroredLaunched = CarLifeLauncherRepository.launchAppOnVirtualDisplay(
      context = wrappedContext,
      app = testApp,
      targetDisplayId = 2
    )
    assertTrue(mirroredLaunched)
    assertTrue("SCREEN_MIRRORING must NOT pass VirtualDisplay ActivityOptions", !vdAttempted)
    assertTrue("SCREEN_MIRRORING must launch directly on default display", fallbackIntent != null)
    CarLifeLauncherRepository.setAppLaunchMode(AppLaunchMode.VIRTUAL_DISPLAY_IF_AVAILABLE)
  }

  @Test
  fun `bridge service toggles AppMode COCKPIT vs MIRRORING, UI packet log, and speaker mute`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = Robolectric.buildService(BridgeForegroundService::class.java).create()
    val service = controller.get()

    val switchToCockpit = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_SET_APP_MODE
      putExtra(BridgeForegroundService.EXTRA_APP_MODE, AppMode.COCKPIT.id)
    }
    service.onStartCommand(switchToCockpit, 0, 1)
    assertEquals(AppMode.COCKPIT, BridgeTelemetryBus.runtimeState.value.appMode)
    assertEquals(VideoSourceMode.VIRTUAL_DISPLAY, BridgeTelemetryBus.runtimeState.value.videoSourceMode)
    assertFalse(BridgeTelemetryBus.runtimeState.value.logPacketsInUi)

    val enableLog = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_SET_LOG_PACKETS_IN_UI
      putExtra(BridgeForegroundService.EXTRA_LOG_PACKETS_IN_UI, true)
    }
    service.onStartCommand(enableLog, 0, 2)
    assertTrue(BridgeTelemetryBus.runtimeState.value.logPacketsInUi)

    val disableLog = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_SET_LOG_PACKETS_IN_UI
      putExtra(BridgeForegroundService.EXTRA_LOG_PACKETS_IN_UI, false)
    }
    service.onStartCommand(disableLog, 0, 3)
    assertFalse(BridgeTelemetryBus.runtimeState.value.logPacketsInUi)

    val disableMute = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_SET_MUTE_PHONE_SPEAKER
      putExtra(BridgeForegroundService.EXTRA_MUTE_PHONE_SPEAKER, false)
    }
    service.onStartCommand(disableMute, 0, 4)
    assertFalse(BridgeTelemetryBus.runtimeState.value.mutePhoneSpeakerDuringUsbAudio)

    val switchToMirror = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_SET_APP_MODE
      putExtra(BridgeForegroundService.EXTRA_APP_MODE, AppMode.MIRRORING.id)
    }
    service.onStartCommand(switchToMirror, 0, 5)
    assertEquals(AppMode.MIRRORING, BridgeTelemetryBus.runtimeState.value.appMode)
    assertEquals(VideoSourceMode.SCREEN_MIRRORING, BridgeTelemetryBus.runtimeState.value.videoSourceMode)
    assertFalse(BridgeTelemetryBus.runtimeState.value.logPacketsInUi)

    val enableMute = Intent(context, BridgeForegroundService::class.java).apply {
      action = BridgeForegroundService.ACTION_SET_MUTE_PHONE_SPEAKER
      putExtra(BridgeForegroundService.EXTRA_MUTE_PHONE_SPEAKER, true)
    }
    service.onStartCommand(enableMute, 0, 6)
    assertTrue(BridgeTelemetryBus.runtimeState.value.mutePhoneSpeakerDuringUsbAudio)

    controller.destroy()
  }

  @Test
  fun `cockpit projection manager builds VBR Baseline Level 3 MediaFormat matching MediaFormatUtils`() {
    val fmt = CockpitProjectionManager.buildPreferredAvcMediaFormat(
      width = 1280,
      height = 720,
      frameRate = 30,
      bitRate = 2_000_000
    )
    assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, fmt.getString(MediaFormat.KEY_MIME))
    assertEquals(1280, fmt.getInteger(MediaFormat.KEY_WIDTH))
    assertEquals(720, fmt.getInteger(MediaFormat.KEY_HEIGHT))
    assertEquals(30, fmt.getInteger(MediaFormat.KEY_FRAME_RATE))
    assertEquals(30f, fmt.getFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER), 0.001f)
    assertEquals(30, fmt.getInteger(MediaFormat.KEY_CAPTURE_RATE))
    assertEquals(2_000_000, fmt.getInteger(MediaFormat.KEY_BIT_RATE))
    assertEquals(1, fmt.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))
    assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, fmt.getInteger(MediaFormat.KEY_BITRATE_MODE))
    assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, fmt.getInteger(MediaFormat.KEY_PROFILE))
    assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel3, fmt.getInteger(MediaFormat.KEY_LEVEL))
    assertEquals(0, fmt.getInteger(MediaFormat.KEY_PRIORITY))
    assertEquals(100_000L, fmt.getLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER))
    assertEquals(36, fmt.getInteger(CockpitProjectionManager.SAMSUNG_QP_P_MAX_KEY))
  }

  @Test
  fun `audio capture manager saves and mutes STREAM_MUSIC then restores original volume`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)

    val captureManager = CarLifeAudioCaptureManager(
      context = context,
      onSendAudioFrame = { _, _ -> true }
    )
    assertTrue(captureManager.stats.value.mutePhoneSpeakerDuringUsbAudio)

    captureManager.mutePhoneSpeakerIfEnabled()
    assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertTrue(captureManager.stats.value.isPhoneSpeakerMutedByBridge)
    assertEquals(9, captureManager.stats.value.savedPhoneMusicVolume)

    // Repeated mute call while muted must NOT overwrite saved volume 9 with 0
    captureManager.mutePhoneSpeakerIfEnabled()
    assertEquals(9, captureManager.stats.value.savedPhoneMusicVolume)

    // Restoring or toggling mute off restores original volume 9
    captureManager.setMutePhoneSpeakerEnabled(false)
    assertEquals(9, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    assertFalse(captureManager.stats.value.isPhoneSpeakerMutedByBridge)
  }

  @Test
  fun `cockpit projection manager isolates 0x0006 touch routing between COCKPIT and MIRRORING`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = CockpitProjectionManager(
      context = context,
      onEncodedVideoNal = { _, _, _ -> }
    )

    val sampleTouch = CarLifeTouchPacket(
      androidAction = MotionEvent.ACTION_DOWN,
      rawX = 640,
      rawY = 360,
      huWidth = 1280,
      huHeight = 720,
      synthesizeClickUp = true
    )

    // Mode COCKPIT: never routes through CarLifeAccessibilityService
    manager.appMode = AppMode.COCKPIT
    kotlinx.coroutines.runBlocking {
      manager.injectTouchPacket(sampleTouch)
    }
    assertTrue(
      "Expected Cockpit Touch status without A11y routing, got: ${manager.stats.value.statusMessage}",
      manager.stats.value.statusMessage.startsWith("Cockpit Touch ->")
    )

    // Mode MIRRORING: routes strictly through CarLifeAccessibilityService path
    manager.appMode = AppMode.MIRRORING
    kotlinx.coroutines.runBlocking {
      manager.injectTouchPacket(sampleTouch)
    }
    assertTrue(
      "Expected Mirror/Accessibility touch status, got: ${manager.stats.value.statusMessage}",
      manager.stats.value.statusMessage.contains("CarLifeAccessibilityService") ||
        manager.stats.value.statusMessage.startsWith("Mirror Touch")
    )

    kotlinx.coroutines.runBlocking { manager.stopProjection() }
  }

  @Test
  fun `shizuku cockpit mode routes app launches, scaled touches with preserved downTime, and hardkeys via ICarLifeShizukuService`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    data class RecordedLaunch(val pkg: String, val activity: String, val displayId: Int)
    data class RecordedTouch(
      val action: Int,
      val x: Float,
      val y: Float,
      val downTime: Long,
      val eventTime: Long,
      val displayId: Int
    )
    data class RecordedKey(val keyCode: Int, val displayId: Int)

    val launches = mutableListOf<RecordedLaunch>()
    val touches = mutableListOf<RecordedTouch>()
    val keys = mutableListOf<RecordedKey>()
    var hiddenCallbackDisplayId: Int? = null

    val fakeStub = object : ICarLifeShizukuService.Stub() {
      override fun launchAppOnDisplay(packageName: String, activityName: String, displayId: Int) {
        launches.add(RecordedLaunch(packageName, activityName, displayId))
      }

      override fun injectTouch(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        displayId: Int
      ) {
        touches.add(RecordedTouch(action, x, y, downTime, eventTime, displayId))
      }

      override fun sendKeyEvent(keyCode: Int, displayId: Int) {
        keys.add(RecordedKey(keyCode, displayId))
      }

      override fun destroy() = Unit
    }

    try {
      CarLifeShizukuManager.setServiceForTesting(fakeStub)
      assertEquals("Работает", CarLifeShizukuManager.status.value.statusLabel)

      // 1. Launcher click routes to Shizuku launchAppOnDisplay + notifies hide callback
      val previousCallback = CarLifeLauncherRepository.onShizukuAppLaunchedOnVirtualDisplay
      CarLifeLauncherRepository.onShizukuAppLaunchedOnVirtualDisplay = { hiddenCallbackDisplayId = it }
      CarLifeLauncherRepository.setAppLaunchMode(AppLaunchMode.SHIZUKU_VIRTUAL_DISPLAY)
      val appInfo = InstalledAppInfo(
        label = "2GIS",
        packageName = "ru.dublgis.dgismobile",
        activityName = "ru.dublgis.dgismobile.MainActivity",
        iconBitmap = null
      )
      val launched = CarLifeLauncherRepository.launchAppOnVirtualDisplay(
        context = context,
        app = appInfo,
        targetDisplayId = 7
      )
      CarLifeLauncherRepository.onShizukuAppLaunchedOnVirtualDisplay = previousCallback
      assertTrue(launched)
      assertEquals(1, launches.size)
      assertEquals(RecordedLaunch("ru.dublgis.dgismobile", "ru.dublgis.dgismobile.MainActivity", 7), launches[0])
      assertEquals(7, hiddenCallbackDisplayId)
      assertTrue(CarLifeLauncherRepository.lastLaunchStatus.value.contains("VirtualDisplay #7"))

      // 2. Touch routing in SHIZUKU_COCKPIT preserves downTime from DOWN through MOVE and UP
      val manager = CockpitProjectionManager(
        context = context,
        onEncodedVideoNal = { _, _, _ -> }
      )
      manager.appMode = AppMode.SHIZUKU_COCKPIT

      var touchStatusAfterInject = ""
      var hardKeyStatusAfterInject = ""
      kotlinx.coroutines.runBlocking {
        manager.injectTouchPacket(
          CarLifeTouchPacket(
            androidAction = MotionEvent.ACTION_DOWN,
            rawX = 320,
            rawY = 180,
            huWidth = 1280,
            huHeight = 720,
            synthesizeClickUp = false
          )
        )
        manager.injectTouchPacket(
          CarLifeTouchPacket(
            androidAction = MotionEvent.ACTION_MOVE,
            rawX = 640,
            rawY = 360,
            huWidth = 1280,
            huHeight = 720,
            synthesizeClickUp = false
          )
        )
        manager.injectTouchPacket(
          CarLifeTouchPacket(
            androidAction = MotionEvent.ACTION_UP,
            rawX = 640,
            rawY = 360,
            huWidth = 1280,
            huHeight = 720,
            synthesizeClickUp = false
          )
        )
        touchStatusAfterInject = manager.stats.value.statusMessage

        manager.injectHardKeyPacket(
          CarLifeHardKeyPacket(carLifeKeyCode = 0x0E, androidKeyCode = KeyEvent.KEYCODE_BACK)
        )
        manager.injectHardKeyPacket(
          CarLifeHardKeyPacket(carLifeKeyCode = 0x0C, androidKeyCode = KeyEvent.KEYCODE_HOME)
        )
        hardKeyStatusAfterInject = manager.stats.value.statusMessage
        manager.stopProjection()
      }

      assertEquals(3, touches.size)
      assertEquals(MotionEvent.ACTION_DOWN, touches[0].action)
      assertEquals(320f, touches[0].x, 0.5f)
      assertEquals(180f, touches[0].y, 0.5f)
      assertEquals(MotionEvent.ACTION_MOVE, touches[1].action)
      assertEquals(640f, touches[1].x, 0.5f)
      assertEquals(360f, touches[1].y, 0.5f)
      assertEquals(MotionEvent.ACTION_UP, touches[2].action)
      assertEquals(touches[0].downTime, touches[1].downTime)
      assertEquals(touches[0].downTime, touches[2].downTime)
      assertTrue(
        "Expected Shizuku Touch status, got: $touchStatusAfterInject",
        touchStatusAfterInject.startsWith("Shizuku Touch")
      )

      assertEquals(2, keys.size)
      assertEquals(KeyEvent.KEYCODE_BACK, keys[0].keyCode)
      assertEquals(KeyEvent.KEYCODE_HOME, keys[1].keyCode)
      assertTrue(
        "Expected Shizuku HardKey status, got: $hardKeyStatusAfterInject",
        hardKeyStatusAfterInject.startsWith("Shizuku HardKey")
      )

      // 3. BridgeForegroundService mode switching supports SHIZUKU_COCKPIT
      val svcController = Robolectric.buildService(BridgeForegroundService::class.java).create()
      val svc = svcController.get()
      val switchShizukuIntent = Intent(context, BridgeForegroundService::class.java).apply {
        action = BridgeForegroundService.ACTION_SET_APP_MODE
        putExtra(BridgeForegroundService.EXTRA_APP_MODE, AppMode.SHIZUKU_COCKPIT.id)
      }
      svc.onStartCommand(switchShizukuIntent, 0, 1)
      assertEquals(AppMode.SHIZUKU_COCKPIT, BridgeTelemetryBus.runtimeState.value.appMode)
      assertEquals(VideoSourceMode.VIRTUAL_DISPLAY, BridgeTelemetryBus.runtimeState.value.videoSourceMode)
      assertEquals(AppLaunchMode.SHIZUKU_VIRTUAL_DISPLAY, BridgeTelemetryBus.runtimeState.value.appLaunchMode)
      svcController.destroy()
    } finally {
      CarLifeShizukuManager.resetForTesting()
      CarLifeLauncherRepository.setAppLaunchMode(AppLaunchMode.VIRTUAL_DISPLAY_IF_AVAILABLE)
      BridgeTelemetryBus.setAppMode(AppMode.MIRRORING)
    }
  }
}


