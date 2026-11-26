package com.example

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.example.core.audio.AudioCapturePreferences
import com.example.core.audio.CarLifeAudioCaptureManager
import com.example.core.carlife.CarLifeHardKeyPacket
import com.example.core.carlife.CarLifeTouchPacket
import com.example.core.config.AppEditionConfig
import com.example.core.config.AppPermissionKey
import com.example.core.config.ConnectionModeRequirements
import com.example.core.config.RequirementRelevance
import com.example.core.input.CarLifeAccessibilityService
import com.example.core.input.TouchSensitivityConfig
import com.example.core.input.TouchSensitivityPreset
import com.example.core.input.TouchSettingsPreferences
import com.example.core.projection.AppMode
import com.example.core.projection.AppModePreferences
import com.example.core.projection.CockpitProjectionManager
import com.example.core.projection.MediaProjectionTokenStore
import com.example.core.projection.ScreenKeepAliveManager
import com.example.core.projection.ScreenKeepAlivePreferences
import com.example.core.projection.VideoSourceMode
import com.example.core.shizuku.CarLifeShizukuManager
import com.example.core.shizuku.ICarLifeShizukuService
import com.example.core.shizuku.CarLifeShizukuService
import com.example.core.usb.BridgeForegroundService
import com.example.core.usb.BridgeTelemetryBus
import com.example.core.usb.CarLifeUsbWriteQueue
import com.example.core.usb.UsbAccessoryTransport
import com.example.protocol.carlife.CarLifeFrameCodec
import com.example.protocol.carlife.CarLifeHardKey
import com.example.protocol.carlife.CarLifeInputEvent
import com.example.protocol.carlife.CarLifeMsgType
import com.example.protocol.carlife.CarLifeTouchDecoder
import androidx.compose.ui.graphics.Color
import com.example.feature.cockpit.AppLaunchMode
import com.example.feature.cockpit.CarLifeLauncherRepository
import com.example.feature.cockpit.CockpitRightPane
import com.example.feature.cockpit.CockpitUiStateController
import com.example.feature.cockpit.EmbeddedYandexMapsController
import com.example.feature.cockpit.InstalledAppInfo
import com.example.feature.cockpit.MediaPlayerController
import com.example.feature.cockpit.MediaState
import com.example.feature.cockpit.VW_LEFT_PANEL_WIDTH_PX
import com.example.ui.guide.SetupGuideCatalog
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences
import com.example.ui.theme.VwDarkColorScheme
import com.example.ui.theme.VwLightColorScheme
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
    assertEquals("JETTA Connect", appName)
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
      label = "Jetta VS7",
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
    assertEquals(2, fmt.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))
    assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, fmt.getInteger(MediaFormat.KEY_BITRATE_MODE))
    assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, fmt.getInteger(MediaFormat.KEY_PROFILE))
    assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel3, fmt.getInteger(MediaFormat.KEY_LEVEL))
    assertEquals(0, fmt.getInteger(MediaFormat.KEY_PRIORITY))
    assertEquals(120, fmt.getInteger(MediaFormat.KEY_OPERATING_RATE))
    assertEquals(100_000L, fmt.getLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER))
    assertEquals(36, fmt.getInteger(CockpitProjectionManager.SAMSUNG_QP_P_MAX_KEY))
    assertEquals(8, UsbAccessoryTransport.MAX_QUEUED_VIDEO_FRAMES)
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

      override fun setNoCutoutOverlayEnabled(enabled: Boolean): Boolean = true

      override fun applyCustomDisplayAspect(targetWidth: Int, targetHeight: Int): Boolean = true

      override fun restoreNativeDisplayAspect(): Boolean = true

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
      AppModePreferences.clear(context)
      BridgeTelemetryBus.setAppMode(AppMode.MIRRORING, context)
    }
  }

  @Test
  fun `app mode persists in SharedPreferences and restores across BridgeForegroundService restart`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      // Save SHIZUKU_COCKPIT via BridgeTelemetryBus -> must persist in SharedPreferences
      BridgeTelemetryBus.setAppMode(AppMode.SHIZUKU_COCKPIT, context)
      assertEquals(AppMode.SHIZUKU_COCKPIT, AppModePreferences.loadAppMode(context))

      // Reset in-memory state to COCKPIT, then start BridgeForegroundService -> must restore SHIZUKU_COCKPIT
      BridgeTelemetryBus.setAppMode(AppMode.COCKPIT, context = null)
      val svc1Controller = Robolectric.buildService(BridgeForegroundService::class.java).create()
      assertEquals(AppMode.SHIZUKU_COCKPIT, BridgeTelemetryBus.runtimeState.value.appMode)
      svc1Controller.destroy()

      // Switch to MIRRORING and verify service restart preserves MIRRORING (never resets to COCKPIT)
      BridgeTelemetryBus.setAppMode(AppMode.MIRRORING, context)
      BridgeTelemetryBus.setAppMode(AppMode.COCKPIT, context = null)
      val svc2Controller = Robolectric.buildService(BridgeForegroundService::class.java).create()
      assertEquals(AppMode.MIRRORING, BridgeTelemetryBus.runtimeState.value.appMode)
      assertEquals(VideoSourceMode.SCREEN_MIRRORING, BridgeTelemetryBus.runtimeState.value.videoSourceMode)
      assertEquals(AppLaunchMode.SCREEN_MIRRORING, BridgeTelemetryBus.runtimeState.value.appLaunchMode)
      svc2Controller.destroy()
    } finally {
      AppModePreferences.clear(context)
      BridgeTelemetryBus.setAppMode(AppMode.MIRRORING, context)
    }
  }

  @Test
  fun `media projection token store consumes resultData single-use and tracks active and error diagnostics`() {
    MediaProjectionTokenStore.clearToken()
    try {
      val tokenIntent = Intent("android.media.projection.TEST").putExtra("token_nonce", 42)
      MediaProjectionTokenStore.saveToken(Activity.RESULT_OK, tokenIntent)

      assertTrue(MediaProjectionTokenStore.hasPendingToken.value)
      assertTrue(MediaProjectionTokenStore.hasValidToken.value)
      assertFalse(MediaProjectionTokenStore.isProjectionActive.value)

      // First consume returns the copy and immediately nullifies resultData (Android 14+ single-use)
      val firstConsume = MediaProjectionTokenStore.consumeToken()
      assertTrue("Expected non-null token on first consume", firstConsume != null)
      assertEquals(Activity.RESULT_OK, firstConsume!!.first)
      assertEquals(42, firstConsume.second.getIntExtra("token_nonce", -1))
      assertEquals(null, MediaProjectionTokenStore.resultData)
      assertFalse(MediaProjectionTokenStore.hasPendingToken.value)

      // Second consume must return null — never reuse stale resultData!
      assertEquals(null, MediaProjectionTokenStore.consumeToken())

      // Activating live MediaProjection keeps hasValidToken true for FGS & UI without keeping stale Intent
      MediaProjectionTokenStore.setActiveProjection(active = true, detail = "Live MP")
      assertTrue(MediaProjectionTokenStore.isProjectionActive.value)
      assertTrue(MediaProjectionTokenStore.hasValidToken.value)

      // Recording an error preserves exact diagnostic message
      MediaProjectionTokenStore.setActiveProjection(
        active = false,
        detail = "Stopped",
        error = "SecurityException: single-use token expired"
      )
      assertFalse(MediaProjectionTokenStore.isProjectionActive.value)
      assertEquals("SecurityException: single-use token expired", MediaProjectionTokenStore.lastError.value)
    } finally {
      MediaProjectionTokenStore.clearToken()
    }
  }

  @Test
  fun `mirroring mode renders diagnostic waiting screen without falling back to cockpit launcher`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = CockpitProjectionManager(
      context = context,
      onEncodedVideoNal = { _, _, _ -> }
    )
    manager.appMode = AppMode.MIRRORING
    manager.videoSourceMode = VideoSourceMode.SCREEN_MIRRORING

    val drawnTexts = mutableListOf<String>()
    val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
    val recordingCanvas = object : Canvas(bitmap) {
      override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
        drawnTexts.add(text)
        super.drawText(text, x, y, paint)
      }
    }

    manager.drawMirroringWaitingOrErrorFrame(
      canvas = recordingCanvas,
      width = 1280,
      height = 720,
      frameSeq = 5L,
      timeString = "12:00:00.000",
      diagnosticError = "Нет токена Весь экран"
    )

    assertTrue(drawnTexts.any { it.contains("РЕЖИМ ЗЕРКАЛИРОВАНИЯ ЭКРАНА") })
    assertTrue(drawnTexts.any { it.contains("MediaProjection: Нет токена Весь экран") })
    assertTrue(drawnTexts.any { it.contains("Откат в Кокпит отключён") })
    assertFalse(
      "Must never draw Cockpit Launcher header in Mirroring mode",
      drawnTexts.any { it.contains("JETTA Connect • Приложения") || it.contains("CarLife Кокпит") }
    )
    assertEquals(AppMode.MIRRORING, manager.appMode)

    bitmap.recycle()
    kotlinx.coroutines.runBlocking { manager.stopProjection() }
  }

  @Test
  fun `cold start service onCreate does not consume pending single-use MediaProjection token before FGS promotion`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    MediaProjectionTokenStore.clearToken()
    try {
      val tokenIntent = Intent("android.media.projection.COLD_START_TEST").putExtra("nonce", 99)
      MediaProjectionTokenStore.saveToken(Activity.RESULT_OK, tokenIntent)
      assertTrue("Pending token must be present before onCreate", MediaProjectionTokenStore.hasPendingToken.value)

      // Cold-start onCreate() alone MUST NOT start projection or burn the single-use Intent
      val controller = Robolectric.buildService(BridgeForegroundService::class.java).create()
      val service = controller.get()

      // Give background dispatcher a moment to prove onCreate() didn't launch a racing startProjection
      Thread.sleep(100L)
      assertTrue(
        "Single-use MediaProjection token must NOT be consumed in onCreate() before promoteToForeground()",
        MediaProjectionTokenStore.hasPendingToken.value
      )

      // Also verify CockpitProjectionManager FGS gate directly blocks audio projection creation while FGS is not ready
      val mpBeforeFgs = kotlinx.coroutines.runBlocking {
        service.projectionManager.getOrCreateMediaProjectionForAudio()
      }
      assertEquals(null, mpBeforeFgs)
      assertTrue(
        "Token must remain unconsumed while isMediaProjectionFgsReady is false",
        MediaProjectionTokenStore.hasPendingToken.value
      )

      controller.destroy()
    } finally {
      MediaProjectionTokenStore.clearToken()
      AppModePreferences.clear(context)
    }
  }

  @Test
  fun `mirroring touch mapping rejects black pillarbox bars and applies edge-back guard on S26 Ultra`() {
    // Portrait S26 Ultra (1440x3120) projected onto 1280x720 HU:
    // Active region width = 720 * (1440 / 3120) = 332.3px, centered -> X in [473.8, 806.2]
    val leftBlackBarTap = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 50,
      rawY = 360,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 1440,
      screenHeight = 3120,
      applyEdgeGuard = true
    )
    assertFalse("Tap deep in left black pillarbox bar must be ignored", leftBlackBarTap.isInsideActiveArea)
    assertFalse(leftBlackBarTap.isLandscape)

    val rightBlackBarTap = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 1230,
      rawY = 360,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 1440,
      screenHeight = 3120,
      applyEdgeGuard = true
    )
    assertFalse("Tap deep in right black pillarbox bar must be ignored", rightBlackBarTap.isInsideActiveArea)

    val centerTap = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 640,
      rawY = 360,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 1440,
      screenHeight = 3120,
      applyEdgeGuard = true
    )
    assertTrue("Center tap must be inside active mirrored region", centerTap.isInsideActiveArea)
    assertEquals(720f, centerTap.x, 8f)
    assertEquals(1560f, centerTap.y, 12f)

    // Tap right on the inner left border of active portrait area -> clamped away from One UI Back edge (>= 14px)
    val leftEdgeTap = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 474,
      rawY = 360,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 1440,
      screenHeight = 3120,
      applyEdgeGuard = true
    )
    assertTrue(leftEdgeTap.isInsideActiveArea)
    assertTrue(
      "Expected left-edge touch to stay inside edge-back guard (>= ${CarLifeAccessibilityService.EDGE_BACK_GUARD_PX}), got ${leftEdgeTap.x}",
      leftEdgeTap.x >= CarLifeAccessibilityService.EDGE_BACK_GUARD_PX
    )

    // Landscape S26 Ultra (3120x1440) projected onto 1280x720 HU:
    // Active region height = 1280 / (3120 / 1440) = 590.8px, centered -> Y in [64.6, 655.4]
    val topLetterboxTap = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 640,
      rawY = 10,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 3120,
      screenHeight = 1440,
      applyEdgeGuard = true
    )
    assertTrue(topLetterboxTap.isLandscape)
    assertFalse("Tap deep in top letterbox bar must be ignored", topLetterboxTap.isInsideActiveArea)

    val landscapeCenterTap = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 640,
      rawY = 360,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 3120,
      screenHeight = 1440,
      applyEdgeGuard = true
    )
    assertTrue(landscapeCenterTap.isInsideActiveArea)
    assertEquals(1560f, landscapeCenterTap.x, 8f)
    assertEquals(720f, landscapeCenterTap.y, 8f)
  }

  @Test
  fun `audio speaker mute preference persists across service restart and detects muted silence threshold`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      AudioCapturePreferences.clear(context)
      assertTrue("Default mute speaker preference should be true", AudioCapturePreferences.loadMutePhoneSpeaker(context))

      // Persist false -> reset in-memory bus -> restart BridgeForegroundService -> must restore false
      BridgeTelemetryBus.setMutePhoneSpeakerDuringUsbAudio(false, context)
      assertFalse(AudioCapturePreferences.loadMutePhoneSpeaker(context))

      BridgeTelemetryBus.setMutePhoneSpeakerDuringUsbAudio(true, context = null)
      val svcController = Robolectric.buildService(BridgeForegroundService::class.java).create()
      assertFalse(
        "BridgeForegroundService must restore persisted mutePhoneSpeakerDuringUsbAudio=false",
        BridgeTelemetryBus.runtimeState.value.mutePhoneSpeakerDuringUsbAudio
      )
      svcController.destroy()

      // Verify silence-under-mute diagnostic heuristic
      assertFalse(
        CarLifeAudioCaptureManager.shouldShowMuteSilenceWarning(
          sentPcmPackets = 0L,
          skippedSilentPackets = 79L,
          isMutedByBridge = true
        )
      )
      assertTrue(
        CarLifeAudioCaptureManager.shouldShowMuteSilenceWarning(
          sentPcmPackets = 0L,
          skippedSilentPackets = 80L,
          isMutedByBridge = true
        )
      )
      assertFalse(
        CarLifeAudioCaptureManager.shouldShowMuteSilenceWarning(
          sentPcmPackets = 5L,
          skippedSilentPackets = 200L,
          isMutedByBridge = true
        )
      )
      assertFalse(
        CarLifeAudioCaptureManager.shouldShowMuteSilenceWarning(
          sentPcmPackets = 0L,
          skippedSilentPackets = 200L,
          isMutedByBridge = false
        )
      )
    } finally {
      AudioCapturePreferences.clear(context)
      BridgeTelemetryBus.setMutePhoneSpeakerDuringUsbAudio(true, context)
    }
  }

  @Test
  fun `mirroring long press and double click touch packets preserve mirroring isolation and diagnostics`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = CockpitProjectionManager(
      context = context,
      onEncodedVideoNal = { _, _, _ -> }
    )
    manager.appMode = AppMode.MIRRORING
    manager.videoSourceMode = VideoSourceMode.SCREEN_MIRRORING

    kotlinx.coroutines.runBlocking {
      manager.injectTouchPacket(
        CarLifeTouchPacket(
          androidAction = MotionEvent.ACTION_DOWN,
          rawX = 640,
          rawY = 360,
          huWidth = 1280,
          huHeight = 720,
          synthesizeClickUp = false,
          isDoubleClick = false,
          isLongPress = true
        )
      )
      val longPressDiag = manager.stats.value.lastTouchDiagnostic
      assertTrue(
        "Expected Mirror/A11y diagnostic for LONG_PRESS, got: $longPressDiag",
        longPressDiag.contains("CarLifeAccessibilityService") || longPressDiag.contains("Mirror Touch")
      )

      manager.injectTouchPacket(
        CarLifeTouchPacket(
          androidAction = MotionEvent.ACTION_DOWN,
          rawX = 640,
          rawY = 360,
          huWidth = 1280,
          huHeight = 720,
          synthesizeClickUp = false,
          isDoubleClick = true,
          isLongPress = false
        )
      )
      assertEquals(AppMode.MIRRORING, manager.appMode)
      manager.stopProjection()
    }
  }

  @Test
  fun `vw mib3 theme palettes match exact dark and light specification tokens`() {
    // VW MIB3 Night (Dark)
    assertEquals(Color(0xFF0B0F17), VwDarkColorScheme.background)
    assertEquals(Color(0xFF151C28), VwDarkColorScheme.surface)
    assertEquals(Color(0xFF1D2636), VwDarkColorScheme.surfaceVariant)
    assertEquals(Color(0xFF273347), VwDarkColorScheme.outline)
    assertEquals(Color(0xFF00A3FF), VwDarkColorScheme.primary)
    assertEquals(Color(0xFF041322), VwDarkColorScheme.onPrimary)
    assertEquals(Color(0xFFE53935), VwDarkColorScheme.error)
    assertEquals(Color(0xFFF2F6FA), VwDarkColorScheme.onBackground)
    assertEquals(Color(0xFFF2F6FA), VwDarkColorScheme.onSurface)
    assertEquals(Color(0xFF8C9BB0), VwDarkColorScheme.onSurfaceVariant)

    // VW Silver Day (Light)
    assertEquals(Color(0xFFF1F4F8), VwLightColorScheme.background)
    assertEquals(Color(0xFFFFFFFF), VwLightColorScheme.surface)
    assertEquals(Color(0xFFE5ECF4), VwLightColorScheme.surfaceVariant)
    assertEquals(Color(0xFFCFD9E6), VwLightColorScheme.outline)
    assertEquals(Color(0xFF001E50), VwLightColorScheme.primary)
    assertEquals(Color(0xFF0066CC), VwLightColorScheme.secondary)
    assertEquals(Color(0xFFFFFFFF), VwLightColorScheme.onPrimary)
    assertEquals(Color(0xFFD32F2F), VwLightColorScheme.error)
    assertEquals(Color(0xFF0D1522), VwLightColorScheme.onBackground)
    assertEquals(Color(0xFF0D1522), VwLightColorScheme.onSurface)
    assertEquals(Color(0xFF56657A), VwLightColorScheme.onSurfaceVariant)

    // TopBar high-contrast container & compact landscape height (44..48.dp)
    assertEquals(Color(0xFF0D1626), VwDarkColorScheme.surfaceContainer)
    assertEquals(Color(0xFFFFFFFF), VwLightColorScheme.surfaceContainer)
    assertEquals(52f, resolveTopBarContentHeightDp(isLandscape = false).value, 0.01f)
    val landscapeTopBarDp = resolveTopBarContentHeightDp(isLandscape = true).value
    assertTrue("Expected landscape TopBar height in 44..48.dp, got $landscapeTopBarDp", landscapeTopBarDp in 44f..48f)
  }

  @Test
  fun `theme mode persists in SharedPreferences and restores across service and activity launches`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      ThemePreferences.clear(context)
      assertEquals(ThemeMode.SYSTEM, ThemePreferences.loadThemeMode(context))
      assertEquals(ThemeMode.SYSTEM, ThemePreferences.themeMode.value)

      // Save LIGHT and verify BridgeForegroundService restores LIGHT on start
      ThemePreferences.setThemeMode(ThemeMode.LIGHT, context)
      assertEquals(ThemeMode.LIGHT, ThemePreferences.loadThemeMode(context))
      ThemePreferences.setThemeMode(ThemeMode.DARK, context = null) // mutate in-memory only
      val svcController = Robolectric.buildService(BridgeForegroundService::class.java).create()
      assertEquals(ThemeMode.LIGHT, ThemePreferences.themeMode.value)
      svcController.destroy()

      // Launch MainActivity cleanly in LIGHT, DARK, and SYSTEM modes
      for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)) {
        ThemePreferences.setThemeMode(mode, context)
        val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = activityController.get()
        assertFalse(activity.isFinishing)
        assertEquals(mode, ThemePreferences.themeMode.value)
        activityController.pause().resume().destroy()
      }
    } finally {
      ThemePreferences.clear(context)
    }
  }

  @Test
  fun `touch sensitivity presets and custom sliders persist and drive tap slop and swipe speed`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      TouchSettingsPreferences.clear(context)
      assertEquals(TouchSensitivityPreset.STANDARD, TouchSettingsPreferences.config.value.preset)

      // Apply SMOOTH preset (42 px tap slop, 70 ms tap, 0.85x swipe speed)
      val smooth = TouchSettingsPreferences.applyPreset(TouchSensitivityPreset.SMOOTH, context)
      assertEquals(TouchSensitivityPreset.SMOOTH, smooth.preset)
      assertEquals(42f, smooth.tapSlopPx, 0.01f)
      assertEquals(70L, smooth.tapDurationMs)

      // A 30 px road wobble is rejected as swipe under HIGH (12 px) but accepted as TAP under SMOOTH (42 px)
      assertTrue(
        CarLifeAccessibilityService.isShortTapGesture(
          startX = 500f,
          startY = 500f,
          endX = 530f,
          endY = 500f,
          points = listOf(500f to 500f, 515f to 502f, 530f to 500f),
          tapSlopPx = smooth.tapSlopPx
        )
      )
      val high = TouchSettingsPreferences.applyPreset(TouchSensitivityPreset.HIGH, context)
      assertFalse(
        CarLifeAccessibilityService.isShortTapGesture(
          startX = 500f,
          startY = 500f,
          endX = 530f,
          endY = 500f,
          points = listOf(500f to 500f, 515f to 502f, 530f to 500f),
          tapSlopPx = high.tapSlopPx
        )
      )

      // Higher swipeSpeedFactor reduces dispatched swipe duration for snappier response
      val stdSwipeMs = CarLifeAccessibilityService.computeSwipeDurationMs(1000L, 1400L, swipeSpeedFactor = 1.0f)
      val fastSwipeMs = CarLifeAccessibilityService.computeSwipeDurationMs(1000L, 1400L, swipeSpeedFactor = high.swipeSpeedFactor)
      assertTrue("Expected fast swipe ($fastSwipeMs) < standard swipe ($stdSwipeMs)", fastSwipeMs < stdSwipeMs)

      // Manual slider adjustment transitions to CUSTOM and persists across init()
      val custom = TouchSettingsPreferences.updateConfig(context) {
        it.copy(tapSlopPx = 55f, edgeBackGuardEnabled = false)
      }
      assertEquals(TouchSensitivityPreset.CUSTOM, custom.preset)
      assertEquals(55f, custom.tapSlopPx, 0.01f)
      assertFalse(custom.edgeBackGuardEnabled)

      val reloaded = TouchSettingsPreferences.init(context)
      assertEquals(TouchSensitivityPreset.CUSTOM, reloaded.preset)
      assertEquals(55f, reloaded.tapSlopPx, 0.01f)
      assertFalse(reloaded.edgeBackGuardEnabled)

      // Reset restores STANDARD defaults
      val resetCfg = TouchSettingsPreferences.reset(context)
      assertEquals(TouchSensitivityPreset.STANDARD, resetCfg.preset)
      assertEquals(TouchSensitivityConfig.DEFAULT_TAP_SLOP_PX, resetCfg.tapSlopPx, 0.01f)
      assertTrue(resetCfg.edgeBackGuardEnabled)
    } finally {
      TouchSettingsPreferences.clear(context)
    }
  }

  @Test
  fun `screen keep-alive preferences persist and USB mirroring enforces 1pct brightness and landscape-left policy`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      ScreenKeepAlivePreferences.clear(context)
      val initial = ScreenKeepAlivePreferences.init(context)
      assertTrue(initial.keepAliveOnScreenOff)
      assertTrue(initial.keepScreenAwakeOverlay)
      assertTrue(initial.dimPhoneScreenDuringMirroring)
      assertTrue(initial.autoRotateLeftOnUsb)
      assertFalse(initial.autoWakeOnScreenOff)

      // 1. USB disconnected in Mirroring -> normal screen brightness & orientation (overlay detached)
      val disconnectedPolicy = ScreenKeepAliveManager.computeOverlayPolicy(
        config = initial,
        isSessionActive = true,
        isUsbConnected = false,
        isMirroringMode = true,
        hasOverlayPermission = true
      )
      assertFalse(disconnectedPolicy.shouldAttachOverlay)
      assertFalse(disconnectedPolicy.dimScreen)
      assertFalse(disconnectedPolicy.rotateLeft)
      assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, disconnectedPolicy.targetBrightness, 0.0001f)
      assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, disconnectedPolicy.targetOrientation)

      // 2. USB connected in Mirroring -> automatic 1% OLED brightness & rotate left (landscape)
      val connectedMirrorPolicy = ScreenKeepAliveManager.computeOverlayPolicy(
        config = initial,
        isSessionActive = true,
        isUsbConnected = true,
        isMirroringMode = true,
        hasOverlayPermission = true
      )
      assertTrue(connectedMirrorPolicy.shouldAttachOverlay)
      assertTrue(connectedMirrorPolicy.keepScreenOn)
      assertTrue(connectedMirrorPolicy.dimScreen)
      assertTrue(connectedMirrorPolicy.rotateLeft)
      assertEquals(ScreenKeepAliveManager.MIN_OLED_BRIGHTNESS, connectedMirrorPolicy.targetBrightness, 0.0001f)
      assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, connectedMirrorPolicy.targetOrientation)

      // 3. USB connected in non-Mirroring mode (Cockpit / Shizuku) -> normal screen brightness & orientation
      val nonMirrorPolicy = ScreenKeepAliveManager.computeOverlayPolicy(
        config = initial,
        isSessionActive = true,
        isUsbConnected = true,
        isMirroringMode = false,
        hasOverlayPermission = true
      )
      assertFalse(nonMirrorPolicy.shouldAttachOverlay)
      assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, nonMirrorPolicy.targetBrightness, 0.0001f)
      assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, nonMirrorPolicy.targetOrientation)

      // 4. Independent toggles persist across init() and update overlay policy cleanly
      ScreenKeepAlivePreferences.updateConfig(context) {
        it.copy(
          dimPhoneScreenDuringMirroring = false,
          autoRotateLeftOnUsb = false,
          autoWakeOnScreenOff = true
        )
      }
      val reloaded = ScreenKeepAlivePreferences.init(context)
      assertFalse(reloaded.dimPhoneScreenDuringMirroring)
      assertFalse(reloaded.autoRotateLeftOnUsb)
      assertTrue(reloaded.autoWakeOnScreenOff)

      val disabledPolicy = ScreenKeepAliveManager.computeOverlayPolicy(
        config = reloaded,
        isSessionActive = true,
        isUsbConnected = true,
        isMirroringMode = true,
        hasOverlayPermission = true
      )
      assertTrue(disabledPolicy.shouldAttachOverlay) // keepScreenAwakeOverlay still true
      assertFalse(disabledPolicy.dimScreen)
      assertFalse(disabledPolicy.rotateLeft)
      assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, disabledPolicy.targetBrightness, 0.0001f)
      assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, disabledPolicy.targetOrientation)

      // 5. BridgeForegroundService handles config updates & screen-off transitions cleanly
      val updateIntent = Intent(context, BridgeForegroundService::class.java).apply {
        action = BridgeForegroundService.ACTION_UPDATE_SCREEN_KEEP_ALIVE
      }
      val svcController = Robolectric.buildService(BridgeForegroundService::class.java, updateIntent)
        .create()
        .startCommand(0, 1)
      val service = svcController.get()
      service.projectionManager.onPhoneScreenStateChanged(isScreenOff = true)
      assertTrue(service.projectionManager.stats.value.isPhoneScreenOff)
      service.projectionManager.onPhoneScreenStateChanged(isScreenOff = false)
      assertFalse(service.projectionManager.stats.value.isPhoneScreenOff)
      svcController.destroy()
    } finally {
      ScreenKeepAlivePreferences.clear(context)
    }
  }

  @Test
  fun `mirroring zero-alloc NAL inspection and GOP-safe USB write queue prevent decoder freezes`() {
    // 1. Verify zero-alloc leading NAL inspector on SPS+PPS+IDR and P-slice buffers
    val spsPpsIdr = byteArrayOf(
      0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E,
      0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(),
      0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x00
    )
    val infoKey = CarLifeFrameCodec.inspectLeadingNalHeader(spsPpsIdr)
    assertTrue(infoKey.hasSps)
    assertTrue(infoKey.hasPps)
    assertTrue(infoKey.hasIdr)
    assertTrue(infoKey.hasSpsAndPps)
    assertTrue(CarLifeFrameCodec.containsSpsAndPps(spsPpsIdr))

    val pSlice = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x9A.toByte(), 0x12, 0x34, 0x56, 0x78)
    val infoP = CarLifeFrameCodec.inspectLeadingNalHeader(pSlice)
    assertFalse(infoP.hasSps)
    assertFalse(infoP.hasPps)
    assertFalse(infoP.hasIdr)
    assertEquals(1, infoP.firstNalType)
    assertFalse(CarLifeFrameCodec.containsSpsAndPps(pSlice))

    // 2. Verify CarLifeUsbWriteQueue GOP-safe congestion dropping + recovery IDR flush
    var congestionTriggers = 0
    val queue = CarLifeUsbWriteQueue(
      maxVideoFrames = 3,
      onVideoCongestionDrop = { congestionTriggers++ }
    )
    val idrFrame1 = CarLifeFrameCodec.createVideoFrame(spsPpsIdr, timestampMs = 100L)
    val pFrame1 = CarLifeFrameCodec.createVideoFrame(pSlice, timestampMs = 133L)
    val pFrame2 = CarLifeFrameCodec.createVideoFrame(pSlice, timestampMs = 166L)
    val pFrame3Overflow = CarLifeFrameCodec.createVideoFrame(pSlice, timestampMs = 200L)
    val pFrame4BrokenGop = CarLifeFrameCodec.createVideoFrame(pSlice, timestampMs = 233L)
    val recoveryIdr = CarLifeFrameCodec.createVideoFrame(spsPpsIdr, timestampMs = 266L)

    queue.enqueue(idrFrame1, isKeyFrameHint = true)
    queue.enqueue(pFrame1, isKeyFrameHint = false)
    queue.enqueue(pFrame2, isKeyFrameHint = false)
    assertEquals(3, queue.videoSize)
    assertFalse(queue.isAwaitingRecoveryKeyFrame)

    // Overflow triggers GOP recovery and requests an instant IDR
    queue.enqueue(pFrame3Overflow, isKeyFrameHint = false)
    assertEquals(1, congestionTriggers)
    assertTrue(queue.isAwaitingRecoveryKeyFrame)
    assertTrue(queue.droppedVideoPFrames.get() >= 2L)

    // Subsequent dependent P-frame is dropped instead of feeding broken GOP to HU decoder
    val droppedBefore = queue.droppedVideoPFrames.get()
    queue.enqueue(pFrame4BrokenGop, isKeyFrameHint = false)
    assertEquals(droppedBefore + 1L, queue.droppedVideoPFrames.get())

    // Fresh recovery IDR flushes stale queued frames and resets recovery flag
    queue.enqueue(recoveryIdr, isKeyFrameHint = true)
    assertFalse(queue.isAwaitingRecoveryKeyFrame)
    assertEquals(1, queue.videoSize)
    val dequeued = queue.pollNonBlocking()
    assertEquals(recoveryIdr.header.timestamp, dequeued?.header?.timestamp)

    // 3. Verify LowLatency candidate key on API 30+ (SDK 36)
    val lowLatencyFmt = CockpitProjectionManager.buildPreferredAvcMediaFormat(includeLowLatency = true)
    assertEquals(1, lowLatencyFmt.getInteger(MediaFormat.KEY_LOW_LATENCY))
  }

  @Test
  fun `MediaPlayerController publishes MediaState and resolves friendly player names`() {
    try {
      MediaPlayerController.resetForTesting()
      assertEquals("Яндекс Музыка", MediaPlayerController.friendlyPlayerName("ru.yandex.music"))
      assertEquals("Spotify", MediaPlayerController.friendlyPlayerName("com.spotify.music"))
      assertEquals("Poweramp", MediaPlayerController.friendlyPlayerName("com.maxmpz.audioplayer"))
      assertEquals("VK Музыка", MediaPlayerController.friendlyPlayerName("com.vkontakte.android"))

      MediaPlayerController.onNotificationListenerStateChanged(true)
      assertTrue(MediaPlayerController.mediaState.value.hasNotificationAccess)

      val cover = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
      MediaPlayerController.setStateForTesting(
        MediaState(
          title = "Nightcall",
          artist = "Kavinsky",
          albumArt = cover,
          isPlaying = true,
          packageName = "ru.yandex.music",
          hasNotificationAccess = true,
          playerAppTitle = "Яндекс Музыка"
        )
      )
      val snap = MediaPlayerController.mediaState.value
      assertTrue(snap.hasActiveSession)
      assertTrue(snap.isPlaying)
      assertEquals("Nightcall", snap.title)
      assertEquals("Kavinsky", snap.artist)
      assertEquals(cover, snap.albumArt)

      MediaPlayerController.onNotificationListenerStateChanged(false)
      assertFalse(MediaPlayerController.mediaState.value.hasNotificationAccess)
    } finally {
      MediaPlayerController.resetForTesting()
    }
  }

  @Test
  fun `CockpitUiStateController toggles embedded Yandex Maps and validates trusted origins`() {
    try {
      CockpitUiStateController.showGrid()
      assertEquals(CockpitRightPane.APP_GRID, CockpitUiStateController.activeRightPane.value)

      CockpitUiStateController.toggleMap()
      assertEquals(CockpitRightPane.EMBEDDED_MAP, CockpitUiStateController.activeRightPane.value)

      CockpitUiStateController.toggleMap()
      assertEquals(CockpitRightPane.APP_GRID, CockpitUiStateController.activeRightPane.value)

      CockpitUiStateController.showMap()
      assertEquals(CockpitRightPane.EMBEDDED_MAP, CockpitUiStateController.activeRightPane.value)

      assertTrue(EmbeddedYandexMapsController.isTrustedYandexOrigin("https://yandex.ru/maps/?l=trf"))
      assertTrue(EmbeddedYandexMapsController.isTrustedYandexOrigin("https://maps.yandex.ru/"))
      assertFalse(EmbeddedYandexMapsController.isTrustedYandexOrigin("https://evil.example.com/"))
      assertFalse(EmbeddedYandexMapsController.isTrustedYandexOrigin(null))
      assertTrue(EmbeddedYandexMapsController.HIDE_SMART_BANNER_JS.contains("MutationObserver"))
    } finally {
      CockpitUiStateController.showGrid()
    }
  }

  @Test
  fun `CarLifeShizukuService builds Freeform and fullscreen launch commands and parses taskId`() {
    val freeform = CarLifeShizukuService.buildStartCommand(
      packageName = "ru.yandex.yandexnavi",
      activityName = "ru.yandex.yandexnavi.core.NavigatorActivity",
      displayId = 9,
      useFreeform = true
    )
    assertEquals(
      listOf(
        "am", "start", "--display", "9",
        "--windowingMode", "5",
        "-n", "ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity"
      ),
      freeform.toList()
    )

    val fullscreen = CarLifeShizukuService.buildStartCommand(
      packageName = "ru.dublgis.dgismobile",
      activityName = "",
      displayId = 9,
      useFreeform = false
    )
    assertEquals(
      listOf(
        "am", "start", "--display", "9",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "ru.dublgis.dgismobile"
      ),
      fullscreen.toList()
    )

    val sampleStackDump = """
      RootTask id=1 bounds=[0,0][1080,2400] displayId=0 userId=0
        taskId=42: com.example/com.example.MainActivity bounds=[0,0][1080,2400]
      RootTask id=2 bounds=[250,0][1280,720] displayId=9 userId=0
        taskId=108: ru.yandex.yandexnavi/ru.yandex.yandexnavi.core.NavigatorActivity bounds=[250,0][1280,720]
    """.trimIndent()
    assertEquals(
      108,
      CarLifeShizukuService.extractTaskIdForPackage(sampleStackDump, "ru.yandex.yandexnavi", 9)
    )
    assertEquals(
      null,
      CarLifeShizukuService.extractTaskIdForPackage(sampleStackDump, "ru.yandex.yandexnavi", 0)
    )
  }

  @Test
  fun `Shizuku smart touch routing separates left VW panel from right navigator area`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val injectedTouches = mutableListOf<Triple<Int, Float, Float>>()
    val leftPanelEvents = mutableListOf<Triple<Int, Float, Float>>()

    val fakeLeftPanelView = object : android.view.View(context) {
      override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        leftPanelEvents += Triple(event.actionMasked, event.x, event.y)
        return true
      }
    }

    val fakeShizuku = object : ICarLifeShizukuService.Stub() {
      override fun launchAppOnDisplay(packageName: String?, activityName: String?, displayId: Int) = Unit

      override fun injectTouch(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        displayId: Int
      ) {
        injectedTouches += Triple(action, x, y)
      }

      override fun sendKeyEvent(keyCode: Int, displayId: Int) = Unit
      override fun setNoCutoutOverlayEnabled(enabled: Boolean): Boolean = false
      override fun applyCustomDisplayAspect(targetWidth: Int, targetHeight: Int): Boolean = false
      override fun restoreNativeDisplayAspect(): Boolean = false
      override fun destroy() = Unit
    }

    val manager = CockpitProjectionManager(
      context = context,
      onEncodedVideoNal = { _, _, _ -> }
    )
    try {
      CarLifeShizukuManager.setServiceForTesting(fakeShizuku)
      manager.appMode = AppMode.SHIZUKU_COCKPIT
      manager.shizukuLeftPanelViewForTesting = fakeLeftPanelView

      kotlinx.coroutines.runBlocking {
        // Touch in left panel (x = 120 < 250) dispatches to Left VW Panel, NOT Shizuku right-pane app
        manager.injectTouchPacket(
          CarLifeTouchPacket(
            androidAction = MotionEvent.ACTION_DOWN,
            rawX = 120,
            rawY = 300,
            huWidth = 1280,
            huHeight = 720,
            synthesizeClickUp = false
          )
        )
        manager.injectTouchPacket(
          CarLifeTouchPacket(
            androidAction = MotionEvent.ACTION_UP,
            rawX = 120,
            rawY = 300,
            huWidth = 1280,
            huHeight = 720,
            synthesizeClickUp = false
          )
        )
        assertEquals(2, leftPanelEvents.size)
        assertTrue(
          "Touches inside left 250px VW panel should not be forwarded to Shizuku right-pane app",
          injectedTouches.isEmpty()
        )

        // Touch in right navigator area (x = 450 >= 250) routes via Shizuku injectTouch
        manager.injectTouchPacket(
          CarLifeTouchPacket(
            androidAction = MotionEvent.ACTION_DOWN,
            rawX = VW_LEFT_PANEL_WIDTH_PX + 200,
            rawY = 360,
            huWidth = 1280,
            huHeight = 720,
            synthesizeClickUp = false
          )
        )
        assertEquals(1, injectedTouches.size)
        assertEquals(MotionEvent.ACTION_DOWN, injectedTouches[0].first)
        assertEquals((VW_LEFT_PANEL_WIDTH_PX + 200).toFloat(), injectedTouches[0].second, 1f)
        assertEquals(360f, injectedTouches[0].third, 1f)
        assertEquals(2, leftPanelEvents.size)

        manager.stopProjection()
      }
    } finally {
      manager.shizukuLeftPanelViewForTesting = null
      CarLifeShizukuManager.setServiceForTesting(null)
    }
  }

  @Test
  fun `CarLifeAccessibilityService arms disarms and matches Samsung SystemUI MediaProjection dialog labels`() {
    try {
      CarLifeAccessibilityService.disarmAutoConfirmMediaProjection()
      assertFalse(CarLifeAccessibilityService.isAutoConfirmMediaProjectionArmed())

      CarLifeAccessibilityService.armAutoConfirmMediaProjection(timeoutMs = 10_000L)
      assertTrue(CarLifeAccessibilityService.isAutoConfirmMediaProjectionArmed())

      CarLifeAccessibilityService.disarmAutoConfirmMediaProjection()
      assertFalse(CarLifeAccessibilityService.isAutoConfirmMediaProjectionArmed())

      assertTrue(CarLifeAccessibilityService.isMediaProjectionDialogPackage("com.android.systemui"))
      assertFalse(CarLifeAccessibilityService.isMediaProjectionDialogPackage("com.example.other"))

      assertTrue(CarLifeAccessibilityService.isSingleAppSelectorLabel("Одно приложение"))
      assertTrue(CarLifeAccessibilityService.isSingleAppSelectorLabel("A single app"))
      assertFalse(CarLifeAccessibilityService.isSingleAppSelectorLabel("Весь экран"))

      assertTrue(CarLifeAccessibilityService.isEntireScreenOptionLabel("Весь экран"))
      assertTrue(CarLifeAccessibilityService.isEntireScreenOptionLabel("Entire screen"))
      assertTrue(CarLifeAccessibilityService.isEntireScreenOptionLabel("Поделиться всем экраном"))
      assertFalse(CarLifeAccessibilityService.isEntireScreenOptionLabel("Одно приложение"))

      assertTrue(CarLifeAccessibilityService.isPositiveConfirmButtonLabel("Начать"))
      assertTrue(CarLifeAccessibilityService.isPositiveConfirmButtonLabel("Start now"))
      assertTrue(CarLifeAccessibilityService.isPositiveConfirmButtonLabel("Разрешить"))
      assertFalse(CarLifeAccessibilityService.isPositiveConfirmButtonLabel("Отмена"))
      assertFalse(CarLifeAccessibilityService.isPositiveConfirmButtonLabel("Cancel"))
    } finally {
      CarLifeAccessibilityService.disarmAutoConfirmMediaProjection()
    }
  }

  @Test
  fun `Mirror-Only edition locks AppMode to MIRRORING and requires 4 checklist prerequisites`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      AppEditionConfig.setMirrorOnlyOverrideForTesting(true)
      assertTrue(AppEditionConfig.isMirrorOnly)
      assertEquals("Mirror Only", AppEditionConfig.editionLabel)
      assertEquals("Jetta Mirror", AppEditionConfig.appDisplayName)

      AppModePreferences.saveAppMode(context, AppMode.COCKPIT)
      assertEquals(AppMode.MIRRORING, AppModePreferences.loadAppMode(context))

      BridgeTelemetryBus.setAppMode(AppMode.SHIZUKU_COCKPIT, context)
      assertEquals(AppMode.MIRRORING, BridgeTelemetryBus.runtimeState.value.appMode)

      val mirrorChecklist = SetupChecklistStatus(
        step1BatteryUnrestricted = true,
        step2OverlayGranted = true,
        step3AccessibilityEnabled = true,
        step4NotificationAccessGranted = false,
        step5MediaProjectionGranted = true
      )
      assertEquals(4, mirrorChecklist.totalMandatorySteps)
      assertEquals(4, mirrorChecklist.completedMandatorySteps)
      assertTrue(mirrorChecklist.allPrerequisitesReady)

      ScreenKeepAlivePreferences.clear(context)
      assertTrue(ScreenKeepAlivePreferences.init(context).autoConfirmMediaProjection)
      ScreenKeepAlivePreferences.updateConfig(context) { it.copy(autoConfirmMediaProjection = false) }
      assertFalse(ScreenKeepAlivePreferences.init(context).autoConfirmMediaProjection)
    } finally {
      AppEditionConfig.setMirrorOnlyOverrideForTesting(null)
      ScreenKeepAlivePreferences.clear(context)
    }
  }

  @Test
  fun `steering wheel 0x00060008 hardkeys decode protobuf tag 0x08, support OK 20, track inversion, AudioManager fallback, and Toast diagnostics`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    TouchSettingsPreferences.clear(context)

    val expectedCodes = listOf(
      1 to CarLifeHardKey.HOME,
      13 to CarLifeHardKey.NAVI,
      14 to CarLifeHardKey.BACK,
      15 to CarLifeHardKey.SEEK_SUB,
      7 to CarLifeHardKey.SELECTOR_PREVIOUS,
      16 to CarLifeHardKey.SEEK_ADD,
      6 to CarLifeHardKey.SELECTOR_NEXT,
      20 to CarLifeHardKey.OK,
      31 to CarLifeHardKey.MEDIA_START,
      32 to CarLifeHardKey.MEDIA_STOP,
      19 to CarLifeHardKey.MUTE,
      22 to CarLifeHardKey.PLAY_PAUSE_22,
      23 to CarLifeHardKey.PLAY_PAUSE_ALT,
      24 to CarLifeHardKey.PLAY_PAUSE,
      33 to CarLifeHardKey.VR_START
    )

    for ((code, expectedKey) in expectedCodes) {
      val frame = CarLifeTouchDecoder.createRawHardKeyFrame(
        rawCode = code,
        serviceType = CarLifeMsgType.MSG_TOUCH_CAR_HARD_KEY_CODE_ALT
      )
      val decoded = CarLifeTouchDecoder.decodeTouchChannelFrame(frame)
      assertTrue("Expected HardKey event for code=$code", decoded is CarLifeInputEvent.HardKey)
      val hardKey = decoded as CarLifeInputEvent.HardKey
      assertEquals(code, hardKey.rawKeyCode)
      assertEquals(expectedKey, hardKey.mappedKey)
    }

    // Custom non-standard wheel code should still extract keycode cleanly and format as "Не назначено"
    val customFrame = CarLifeTouchDecoder.createRawHardKeyFrame(
      rawCode = 42,
      serviceType = CarLifeMsgType.MSG_TOUCH_CAR_HARD_KEY_CODE_ALT
    )
    val customDecoded = CarLifeTouchDecoder.decodeTouchChannelFrame(customFrame) as CarLifeInputEvent.HardKey
    assertEquals(42, customDecoded.rawKeyCode)
    assertEquals(null, customDecoded.mappedKey)
    assertEquals(
      "Руль: code=42 (0x2a) → Не назначено",
      CarLifeHardKey.formatDiagnosticToast(42, swapTrackButtons = false)
    )
    assertEquals(
      "Руль: code=16 (0x10) → След. трек",
      CarLifeHardKey.formatDiagnosticToast(16, swapTrackButtons = false)
    )
    assertEquals(
      "Руль: code=20 (0x14) → Пауза / Воспр.",
      CarLifeHardKey.formatDiagnosticToast(20, swapTrackButtons = false)
    )
    assertEquals(
      "Руль: code=33 (0x21) → Голосовой ассистент",
      CarLifeHardKey.formatDiagnosticToast(33, swapTrackButtons = false)
    )

    // Verify SteeringWheelConfig persistence (swapTrackButtons default false, showKeyCodesToast default true)
    assertFalse(TouchSettingsPreferences.steeringWheelConfig.value.swapTrackButtons)
    assertTrue(TouchSettingsPreferences.steeringWheelConfig.value.showKeyCodesToast)
    TouchSettingsPreferences.setSwapSteeringWheelTrackButtons(true, context)
    TouchSettingsPreferences.setShowSteeringWheelKeyCodesToast(false, context)
    assertTrue(TouchSettingsPreferences.loadSteeringWheelConfig(context).swapTrackButtons)
    assertFalse(TouchSettingsPreferences.loadSteeringWheelConfig(context).showKeyCodesToast)
    TouchSettingsPreferences.setSwapSteeringWheelTrackButtons(false, context)
    TouchSettingsPreferences.setShowSteeringWheelKeyCodesToast(true, context)

    // Verify Cockpit routing + track button swap + AudioManager fallback when no MediaSession is active
    MediaPlayerController.resetForTesting()
    MediaPlayerController.initialize(context)
    val manager = CockpitProjectionManager(
      context = context,
      onEncodedVideoNal = { _, _, _ -> }
    )
    try {
      manager.appMode = AppMode.COCKPIT
      CockpitUiStateController.showGrid()
      kotlinx.coroutines.runBlocking {
        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(13, KeyEvent.KEYCODE_NAVIGATE_IN)))
        assertEquals(CockpitRightPane.EMBEDDED_MAP, CockpitUiStateController.activeRightPane.value)

        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(14, KeyEvent.KEYCODE_BACK)))
        assertEquals(CockpitRightPane.APP_GRID, CockpitUiStateController.activeRightPane.value)

        CockpitUiStateController.showMap()
        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(1, KeyEvent.KEYCODE_HOME)))
        assertEquals(CockpitRightPane.APP_GRID, CockpitUiStateController.activeRightPane.value)

        // Default track direction (swap = false): 15/7 -> previous(), 16/6 -> next()
        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(15, KeyEvent.KEYCODE_MEDIA_PREVIOUS)))
        assertEquals("AUDIO_MANAGER_PREVIOUS", MediaPlayerController.lastTransportAction)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaPlayerController.lastDispatchedSystemKeyCode)

        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(7, KeyEvent.KEYCODE_MEDIA_PREVIOUS)))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaPlayerController.lastDispatchedSystemKeyCode)

        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(16, KeyEvent.KEYCODE_MEDIA_NEXT)))
        assertEquals("AUDIO_MANAGER_NEXT", MediaPlayerController.lastTransportAction)
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaPlayerController.lastDispatchedSystemKeyCode)

        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(6, KeyEvent.KEYCODE_MEDIA_NEXT)))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaPlayerController.lastDispatchedSystemKeyCode)

        // Inverted track direction (swap = true): 15/7 -> next(), 16/6 -> previous()
        TouchSettingsPreferences.setSwapSteeringWheelTrackButtons(true, context)
        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(15, KeyEvent.KEYCODE_MEDIA_PREVIOUS)))
        assertEquals("AUDIO_MANAGER_NEXT", MediaPlayerController.lastTransportAction)
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaPlayerController.lastDispatchedSystemKeyCode)

        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(16, KeyEvent.KEYCODE_MEDIA_NEXT)))
        assertEquals("AUDIO_MANAGER_PREVIOUS", MediaPlayerController.lastTransportAction)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaPlayerController.lastDispatchedSystemKeyCode)

        // All Play/Pause codes (20 OK, 31 MEDIA_START, 32 MEDIA_STOP, 19 MUTE, 22, 23, 24)
        for (playPauseCode in listOf(20, 31, 32, 19, 22, 23, 24)) {
          val pairsBefore = MediaPlayerController.dispatchedSystemKeyPairCount
          assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(playPauseCode, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
          assertEquals("AUDIO_MANAGER_PLAY_PAUSE", MediaPlayerController.lastTransportAction)
          assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, MediaPlayerController.lastDispatchedSystemKeyCode)
          assertEquals(pairsBefore + 1, MediaPlayerController.dispatchedSystemKeyPairCount)
        }

        // Voice assistant code 33 (0x21)
        assertTrue(manager.injectHardKeyPacket(CarLifeHardKeyPacket(33, KeyEvent.KEYCODE_VOICE_ASSIST)))

        manager.stopProjection()
      }
    } finally {
      TouchSettingsPreferences.clear(context)
      MediaPlayerController.resetForTesting()
      CockpitUiStateController.registerMapBackHandler(null)
      CockpitUiStateController.showGrid()
    }
  }

  @Test
  fun `display cutout fill preference and Shizuku noCutout overlay policy toggle cleanly on USB state`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      ScreenKeepAlivePreferences.clear(context)
      val defaults = ScreenKeepAlivePreferences.init(context)
      assertTrue(defaults.fillCameraCutoutOnUsb)

      ScreenKeepAlivePreferences.updateConfig(context) { it.copy(fillCameraCutoutOnUsb = false) }
      assertFalse(ScreenKeepAlivePreferences.init(context).fillCameraCutoutOnUsb)

      assertTrue(
        ScreenKeepAliveManager.shouldApplyDisplayCutoutFill(
          fillCameraCutoutOnUsb = true,
          isUsbConnected = true,
          isStreamingOrProjecting = true
        )
      )
      assertFalse(
        ScreenKeepAliveManager.shouldApplyDisplayCutoutFill(
          fillCameraCutoutOnUsb = true,
          isUsbConnected = false,
          isStreamingOrProjecting = true
        )
      )
      assertFalse(
        ScreenKeepAliveManager.shouldApplyDisplayCutoutFill(
          fillCameraCutoutOnUsb = false,
          isUsbConnected = true,
          isStreamingOrProjecting = true
        )
      )

      assertTrue(
        ScreenKeepAliveManager.shouldApplyShizukuNoCutoutOverlay(
          fillCameraCutoutOnUsb = true,
          isUsbConnected = true,
          isStreamingOrProjecting = true,
          isMirroringMode = true,
          isShizukuReady = true
        )
      )
      assertFalse(
        ScreenKeepAliveManager.shouldApplyShizukuNoCutoutOverlay(
          fillCameraCutoutOnUsb = true,
          isUsbConnected = true,
          isStreamingOrProjecting = true,
          isMirroringMode = false,
          isShizukuReady = true
        )
      )
      assertFalse(
        ScreenKeepAliveManager.shouldApplyShizukuNoCutoutOverlay(
          fillCameraCutoutOnUsb = true,
          isUsbConnected = false,
          isStreamingOrProjecting = true,
          isMirroringMode = true,
          isShizukuReady = true
        )
      )

      val enableCmd = CarLifeShizukuService.buildNoCutoutOverlayCommand(enable = true).toList()
      val disableCmd = CarLifeShizukuService.buildNoCutoutOverlayCommand(enable = false).toList()
      assertEquals(
        listOf("cmd", "overlay", "enable", CarLifeShizukuService.NO_CUTOUT_OVERLAY_PACKAGE),
        enableCmd
      )
      assertEquals(
        listOf("cmd", "overlay", "disable", CarLifeShizukuService.NO_CUTOUT_OVERLAY_PACKAGE),
        disableCmd
      )
    } finally {
      ScreenKeepAlivePreferences.clear(context)
    }
  }

  @Test
  fun `mirroring mode uses native phone screen resolution bounds and never invokes wm size commands`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      ScreenKeepAlivePreferences.clear(context)
      val cfg = ScreenKeepAlivePreferences.init(context)
      assertTrue(cfg.keepAliveOnScreenOff)
      assertTrue(cfg.fillCameraCutoutOnUsb)

      // Ensure ICarLifeShizukuService has no setMirroringAspect16by9 or resetDisplaySize methods
      val aidlMethodNames = ICarLifeShizukuService::class.java.declaredMethods.map { it.name }
      assertFalse(aidlMethodNames.contains("setMirroringAspect16by9"))
      assertFalse(aidlMethodNames.contains("resetDisplaySize"))

      // CockpitProjectionManager calculates physical screen bounds strictly from native phone metrics
      val projManager = CockpitProjectionManager(context = context, onEncodedVideoNal = { _, _, _ -> })
      projManager.recalculatePhysicalScreenBounds(requestSyncOnChange = false)
      assertTrue(projManager.stats.value.phoneScreenWidth > 0)
      assertTrue(projManager.stats.value.phoneScreenHeight > 0)
      kotlinx.coroutines.runBlocking { projManager.stopProjection() }
    } finally {
      CarLifeShizukuManager.resetForTesting()
      ScreenKeepAlivePreferences.clear(context)
    }
  }

  @Test
  fun `unified 8-item AppPermissionsSnapshot computes counters, Shizuku status, and missing runtime permissions`() {
    val allMissing = AppPermissionsSnapshot()
    assertEquals(8, allMissing.totalCount)
    assertEquals(0, allMissing.grantedCount)
    assertFalse(allMissing.allGranted)
    assertEquals(
      listOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.ACCESS_FINE_LOCATION
      ),
      allMissing.missingRuntimePermissions(sdkInt = 35)
    )
    assertEquals(
      listOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.ACCESS_FINE_LOCATION
      ),
      allMissing.missingRuntimePermissions(sdkInt = 31)
    )

    // Shizuku requires BOTH binder alive and permission granted
    val binderOnly = AppPermissionsSnapshot(shizukuBinderAlive = true, shizukuPermissionGranted = false)
    assertFalse(binderOnly.shizukuGranted)
    assertEquals(0, binderOnly.grantedCount)

    val partial = AppPermissionsSnapshot(
      recordAudioGranted = true,
      postNotificationsGranted = true,
      fineLocationGranted = false,
      accessibilityEnabled = true,
      notificationListenerGranted = false,
      overlayGranted = true,
      batteryOptimizationIgnored = true,
      shizukuBinderAlive = true,
      shizukuPermissionGranted = true
    )
    assertEquals(6, partial.grantedCount)
    assertFalse(partial.allGranted)
    assertEquals(listOf(android.Manifest.permission.ACCESS_FINE_LOCATION), partial.missingRuntimePermissions(sdkInt = 35))

    val complete = partial.copy(
      fineLocationGranted = true,
      notificationListenerGranted = true
    )
    assertEquals(8, complete.grantedCount)
    assertTrue(complete.allGranted)
    assertTrue(complete.missingRuntimePermissions(sdkInt = 35).isEmpty())
  }

  @Test
  fun `JETTA Connect branding resources and Edition display name match specification`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("JETTA Connect", context.getString(R.string.app_name))
    assertEquals("JETTA Connect", context.getString(R.string.notif_title_active))
    assertEquals(
      "JETTA Connect (Управление экраном)",
      context.getString(R.string.accessibility_service_label)
    )

    try {
      AppEditionConfig.setMirrorOnlyOverrideForTesting(false)
      assertEquals("JETTA Connect", AppEditionConfig.appDisplayName)
    } finally {
      AppEditionConfig.setMirrorOnlyOverrideForTesting(null)
    }
  }

  @Test
  fun `SetupGuideCatalog provides 6 VW MIB3 steps and returns 0 when optional drawable screenshots are absent`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val steps = SetupGuideCatalog.steps
    assertEquals(6, steps.size)
    assertEquals((1..6).toList(), steps.map { it.stepNumber })
    assertEquals(
      (1..6).map { "guide_step_$it" },
      steps.map { it.drawableName }
    )
    assertTrue(steps.all { it.badge.isNotBlank() && it.title.isNotBlank() && it.summary.isNotBlank() && it.highlights.isNotEmpty() })
    assertEquals(0, SetupGuideCatalog.resolveOptionalGuideDrawableId(context, 0))
    assertEquals(0, SetupGuideCatalog.resolveOptionalGuideDrawableId(context, 1))
    assertEquals(0, SetupGuideCatalog.resolveOptionalGuideDrawableId(context, 7))
  }

  @Test
  fun `ConnectionModeRequirements separates mandatory, optional, and not-needed permissions per AppMode`() {
    val cockpitSpec = ConnectionModeRequirements.specFor(AppMode.COCKPIT)
    assertEquals(RequirementRelevance.OPTIONAL_FEATURE, cockpitSpec.mediaProjectionRelevance)
    assertTrue(cockpitSpec.notNeededPermissions.any { it.key == AppPermissionKey.ACCESSIBILITY })
    assertTrue(cockpitSpec.notNeededPermissions.any { it.key == AppPermissionKey.SHIZUKU })
    assertTrue(cockpitSpec.notNeededPermissions.any { it.key == AppPermissionKey.OVERLAY })

    val mirroringSpec = ConnectionModeRequirements.specFor(AppMode.MIRRORING)
    assertEquals(RequirementRelevance.REQUIRED, mirroringSpec.mediaProjectionRelevance)
    assertEquals(
      RequirementRelevance.REQUIRED,
      mirroringSpec.permissions.first { it.key == AppPermissionKey.ACCESSIBILITY }.relevance
    )
    assertTrue(mirroringSpec.notNeededPermissions.any { it.key == AppPermissionKey.FINE_LOCATION })

    val shizukuSpec = ConnectionModeRequirements.specFor(AppMode.SHIZUKU_COCKPIT)
    assertEquals(RequirementRelevance.OPTIONAL_FEATURE, shizukuSpec.mediaProjectionRelevance)
    assertEquals(
      RequirementRelevance.REQUIRED,
      shizukuSpec.permissions.first { it.key == AppPermissionKey.SHIZUKU }.relevance
    )
    assertEquals(
      RequirementRelevance.REQUIRED,
      shizukuSpec.permissions.first { it.key == AppPermissionKey.OVERLAY }.relevance
    )
    assertTrue(shizukuSpec.notNeededPermissions.any { it.key == AppPermissionKey.ACCESSIBILITY })

    // Cockpit readiness succeeds without Accessibility, Shizuku, Overlay, or MediaProjection
    val cockpitSnapshot = AppPermissionsSnapshot(
      postNotificationsGranted = true,
      batteryOptimizationIgnored = true,
      notificationListenerGranted = true,
      fineLocationGranted = true
    )
    val cockpitReadiness = ConnectionModeRequirements.evaluateReadiness(
      mode = AppMode.COCKPIT,
      grantedMap = cockpitSnapshot.toGrantedMap(),
      hasMediaProjectionToken = false
    )
    assertTrue(cockpitReadiness.allMandatoryReady)
    assertEquals(4, cockpitReadiness.totalRequiredCount)
    assertEquals(4, cockpitReadiness.grantedRequiredCount)
    assertEquals(
      listOf(android.Manifest.permission.RECORD_AUDIO),
      cockpitSnapshot.missingRuntimePermissionsFor(cockpitSpec, sdkInt = 35)
    )

    // Mirroring readiness requires BOTH Accessibility and MediaProjection
    val mirrorSnapshotWithoutMp = AppPermissionsSnapshot(
      postNotificationsGranted = true,
      batteryOptimizationIgnored = true,
      accessibilityEnabled = true
    )
    val mirrorMissingMp = ConnectionModeRequirements.evaluateReadiness(
      mode = AppMode.MIRRORING,
      grantedMap = mirrorSnapshotWithoutMp.toGrantedMap(),
      hasMediaProjectionToken = false
    )
    assertFalse(mirrorMissingMp.allMandatoryReady)
    assertEquals(4, mirrorMissingMp.totalRequiredCount)
    assertEquals(3, mirrorMissingMp.grantedRequiredCount)
    assertTrue(mirrorMissingMp.missingRequiredLabels.any { it.contains("Весь экран") })
    // Location is NOT requested in Mirroring runtime batch
    assertEquals(
      listOf(android.Manifest.permission.RECORD_AUDIO),
      mirrorSnapshotWithoutMp.missingRuntimePermissionsFor(mirroringSpec, sdkInt = 35)
    )

    val mirrorReady = ConnectionModeRequirements.evaluateReadiness(
      mode = AppMode.MIRRORING,
      grantedMap = mirrorSnapshotWithoutMp.toGrantedMap(),
      hasMediaProjectionToken = true
    )
    assertTrue(mirrorReady.allMandatoryReady)

    // Shizuku Cockpit readiness requires Shizuku + Overlay + NotificationListener + Battery + Notifications
    val shizukuMissing = AppPermissionsSnapshot(
      postNotificationsGranted = true,
      batteryOptimizationIgnored = true,
      notificationListenerGranted = true,
      overlayGranted = true,
      shizukuBinderAlive = false,
      shizukuPermissionGranted = false
    )
    val shizukuNotReady = ConnectionModeRequirements.evaluateReadiness(
      mode = AppMode.SHIZUKU_COCKPIT,
      grantedMap = shizukuMissing.toGrantedMap(),
      hasMediaProjectionToken = false
    )
    assertFalse(shizukuNotReady.allMandatoryReady)
    assertEquals(5, shizukuNotReady.totalRequiredCount)
    assertEquals(4, shizukuNotReady.grantedRequiredCount)

    val shizukuReady = ConnectionModeRequirements.evaluateReadiness(
      mode = AppMode.SHIZUKU_COCKPIT,
      grantedMap = shizukuMissing.copy(shizukuBinderAlive = true, shizukuPermissionGranted = true).toGrantedMap(),
      hasMediaProjectionToken = false
    )
    assertTrue(shizukuReady.allMandatoryReady)
  }

  @Test
  fun `touch mapping accurately handles 800x480 and 1024x800 letterbox and adaptive left panel while keeping 1280x720 unchanged`() {
    // 1. Adaptive left panel width: 1280x720 stays strictly 250px, 1024x800 uses 220px, 800x480 uses 190px
    assertEquals(250, com.example.feature.cockpit.computeVwLeftPanelWidthPx(1280))
    assertEquals(220, com.example.feature.cockpit.computeVwLeftPanelWidthPx(1024))
    assertEquals(190, com.example.feature.cockpit.computeVwLeftPanelWidthPx(800))

    // 2. Mirroring letterbox calculation on S26 Ultra (3120x1440, 19.5:9) when HU sends 1280x720 normalized touch coordinates
    // while VirtualDisplay is streaming at 1024x800 (5:4) or 800x480 (5:3):
    // In 1024x800, drawnH = 1440 * (1024 / 3120) = 472.615 px -> offsetY = (800 - 472.615) / 2 = 163.69 px (20.46% of height).
    // In 1280x720 HU space, 20.46% of 720 = 147.3 px. A tap at rawY = 100 (13.9% from top) is inside the top black bar in 1024x800!
    val mirror1024x800BlackBarTap = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 640,
      rawY = 100,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 3120,
      screenHeight = 1440,
      applyEdgeGuard = false,
      captureWidth = 1024f,
      captureHeight = 800f
    )
    assertFalse(
      "rawY=100 out of 720 is inside the 20.5% letterbox bar of a 1024x800 VirtualDisplay",
      mirror1024x800BlackBarTap.isInsideActiveArea
    )

    // Center tap (640, 360) must always map to exact center (1560, 720) across 1280x720, 1024x800, and 800x480
    val center1024x800 = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 640,
      rawY = 360,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 3120,
      screenHeight = 1440,
      applyEdgeGuard = false,
      captureWidth = 1024f,
      captureHeight = 800f
    )
    assertTrue(center1024x800.isInsideActiveArea)
    assertEquals(1560f, center1024x800.x, 1.5f)
    assertEquals(720f, center1024x800.y, 1.5f)

    val center800x480 = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 400,
      rawY = 240,
      huWidth = 800f,
      huHeight = 480f,
      screenWidth = 3120,
      screenHeight = 1440,
      applyEdgeGuard = false,
      captureWidth = 800f,
      captureHeight = 480f
    )
    assertTrue(center800x480.isInsideActiveArea)
    assertEquals(1560f, center800x480.x, 1.5f)
    assertEquals(720f, center800x480.y, 1.5f)

    // 3. 1280x720 behavior remains 100% identical
    val legacy1280 = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 640,
      rawY = 360,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 3120,
      screenHeight = 1440,
      applyEdgeGuard = false
    )
    val explicit1280 = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 640,
      rawY = 360,
      huWidth = 1280f,
      huHeight = 720f,
      screenWidth = 3120,
      screenHeight = 1440,
      applyEdgeGuard = false,
      captureWidth = 1280f,
      captureHeight = 720f
    )
    assertEquals(legacy1280.x, explicit1280.x, 0.001f)
    assertEquals(legacy1280.y, explicit1280.y, 0.001f)
    assertEquals(legacy1280.isInsideActiveArea, explicit1280.isInsideActiveArea)

    // 4. Full-screen stretched mode (FullScreenMirrorGlScaler active): strips top/bottom letterbox bars across
    // 800x480, 1024x800, and 1280x720 in landscape and maps 100% of HU screen directly to phone screen
    val crop1280 = com.example.core.projection.FullScreenMirrorGlScaler.computeNormalizedActiveCropRect(
      captureWidth = 1280,
      captureHeight = 720,
      screenWidth = 3120,
      screenHeight = 1440
    )
    assertEquals(0f, crop1280.uOffset, 0.001f)
    assertEquals(1f, crop1280.uScale, 0.001f)
    assertTrue("Top/bottom black bars should be stripped in 1280x720 landscape", crop1280.vOffset > 0.05f && crop1280.vScale < 0.9f)

    val crop800x480 = com.example.core.projection.FullScreenMirrorGlScaler.computeNormalizedActiveCropRect(
      captureWidth = 800,
      captureHeight = 480,
      screenWidth = 3120,
      screenHeight = 1440
    )
    assertEquals(0f, crop800x480.uOffset, 0.001f)
    assertEquals(1f, crop800x480.uScale, 0.001f)
    assertTrue("Top/bottom black bars should be stripped in 800x480 landscape", crop800x480.vOffset > 0.1f && crop800x480.vScale < 0.8f)

    val crop1024x800 = com.example.core.projection.FullScreenMirrorGlScaler.computeNormalizedActiveCropRect(
      captureWidth = 1024,
      captureHeight = 800,
      screenWidth = 3120,
      screenHeight = 1440
    )
    assertEquals(0f, crop1024x800.uOffset, 0.001f)
    assertEquals(1f, crop1024x800.uScale, 0.001f)
    assertTrue("Top/bottom black bars should be stripped in 1024x800 landscape", crop1024x800.vOffset > 0.19f && crop1024x800.vScale < 0.62f)

    // Center tap (512, 300) at 1024x600 must also map to exact center (1560, 720) with preserved aspect ratio
    val center1024x600 = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
      rawX = 512,
      rawY = 300,
      huWidth = 1024f,
      huHeight = 600f,
      screenWidth = 3120,
      screenHeight = 1440,
      applyEdgeGuard = false,
      captureWidth = 1024f,
      captureHeight = 600f,
      isFullScreenStretched = false
    )
    assertTrue(center1024x600.isInsideActiveArea)
    assertEquals(1560f, center1024x600.x, 1.5f)
    assertEquals(720f, center1024x600.y, 1.5f)
  }

  @Test
  fun `LauncherFilterPreferences defaults to AUTO filter and persists user selection`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences(
      com.example.feature.cockpit.LauncherFilterPreferences.PREFS_NAME,
      Context.MODE_PRIVATE
    ).edit().clear().commit()

    // Default must be FILTER_AUTO (1)
    assertEquals(
      com.example.feature.cockpit.LauncherFilterPreferences.FILTER_AUTO,
      com.example.feature.cockpit.LauncherFilterPreferences.loadSelectedFilter(context)
    )

    // Save FILTER_ALL (0) and verify reload
    com.example.feature.cockpit.LauncherFilterPreferences.saveSelectedFilter(
      context,
      com.example.feature.cockpit.LauncherFilterPreferences.FILTER_ALL
    )
    assertEquals(
      com.example.feature.cockpit.LauncherFilterPreferences.FILTER_ALL,
      com.example.feature.cockpit.LauncherFilterPreferences.loadSelectedFilter(context)
    )

    // Save FILTER_AUTO (1) and verify reload
    com.example.feature.cockpit.LauncherFilterPreferences.saveSelectedFilter(
      context,
      com.example.feature.cockpit.LauncherFilterPreferences.FILTER_AUTO
    )
    assertEquals(
      com.example.feature.cockpit.LauncherFilterPreferences.FILTER_AUTO,
      com.example.feature.cockpit.LauncherFilterPreferences.loadSelectedFilter(context)
    )
  }

  @Test
  fun `three MirrorVideoScaleMode variants persist, preserve 1-to-1 circular geometry in CENTER_CROP_FULLSCREEN, and map touch accurately`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    try {
      ScreenKeepAlivePreferences.clear(context)
      val defaultCfg = ScreenKeepAlivePreferences.init(context)
      assertEquals(
        com.example.core.projection.MirrorVideoScaleMode.CENTER_CROP_FULLSCREEN,
        defaultCfg.videoScaleMode
      )

      // Persist all 3 variants and verify reload + descriptions
      for (mode in com.example.core.projection.MirrorVideoScaleMode.entries) {
        assertTrue(mode.shortTitle.isNotBlank())
        assertTrue(mode.fullTitle.isNotBlank())
        assertTrue(mode.description.isNotBlank())
        ScreenKeepAlivePreferences.updateConfig(context) { it.copy(videoScaleMode = mode) }
        assertEquals(mode, ScreenKeepAlivePreferences.init(context).videoScaleMode)
      }

      // Verify 1:1 uniform Center-Crop (uScale == vScale) across all 4 HU resolutions on S26 Ultra (3120x1440)
      val resolutions = listOf(
        800 to 480,
        1024 to 600,
        1024 to 800,
        1280 to 720
      )
      for ((huW, huH) in resolutions) {
        val rect = com.example.core.projection.FullScreenMirrorGlScaler.computeCenterCrop1to1Rect(
          captureWidth = huW,
          captureHeight = huH,
          screenWidth = 3120,
          screenHeight = 1440
        )
        assertEquals(
          "uScale and vScale must be strictly equal for 1:1 circular proportions at ${huW}x${huH}",
          rect.uScale,
          rect.vScale,
          0.0001f
        )
        assertEquals(rect.uOffset, rect.vOffset, 0.0001f)
        assertTrue("Uniform span must crop top/bottom letterbox bars at ${huW}x${huH}", rect.uScale < 0.9f)

        // Center touch on Head Unit must map to exact phone center (1560, 720)
        val centerTouch = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
          rawX = huW / 2,
          rawY = huH / 2,
          huWidth = huW.toFloat(),
          huHeight = huH.toFloat(),
          screenWidth = 3120,
          screenHeight = 1440,
          applyEdgeGuard = false,
          captureWidth = huW.toFloat(),
          captureHeight = huH.toFloat(),
          isCenterCrop1to1 = true
        )
        assertTrue(centerTouch.isInsideActiveArea)
        assertEquals(1560f, centerTouch.x, 1.5f)
        assertEquals(720f, centerTouch.y, 1.5f)

        // Top and bottom edges of Head Unit in CENTER_CROP_FULLSCREEN map to top (0) and bottom (1440) of phone screen
        val topTouch = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
          rawX = huW / 2,
          rawY = 0,
          huWidth = huW.toFloat(),
          huHeight = huH.toFloat(),
          screenWidth = 3120,
          screenHeight = 1440,
          applyEdgeGuard = false,
          captureWidth = huW.toFloat(),
          captureHeight = huH.toFloat(),
          isCenterCrop1to1 = true
        )
        assertTrue(topTouch.isInsideActiveArea)
        assertEquals(0f, topTouch.y, 1.5f)

        val bottomTouch = CarLifeAccessibilityService.mapHuTouchToPhysicalScreenDetailed(
          rawX = huW / 2,
          rawY = huH,
          huWidth = huW.toFloat(),
          huHeight = huH.toFloat(),
          screenWidth = 3120,
          screenHeight = 1440,
          applyEdgeGuard = false,
          captureWidth = huW.toFloat(),
          captureHeight = huH.toFloat(),
          isCenterCrop1to1 = true
        )
        assertTrue(bottomTouch.isInsideActiveArea)
        assertEquals(1440f, bottomTouch.y, 1.5f)
      }

      // Shizuku aspect policy activates only for SHIZUKU_16_9 when USB + Mirroring + Shizuku are ready
      assertTrue(
        ScreenKeepAliveManager.shouldApplyShizukuDisplayAspect(
          videoScaleMode = com.example.core.projection.MirrorVideoScaleMode.SHIZUKU_16_9,
          isUsbConnected = true,
          isStreamingOrProjecting = true,
          isMirroringMode = true,
          isShizukuReady = true
        )
      )
      assertFalse(
        ScreenKeepAliveManager.shouldApplyShizukuDisplayAspect(
          videoScaleMode = com.example.core.projection.MirrorVideoScaleMode.CENTER_CROP_FULLSCREEN,
          isUsbConnected = true,
          isStreamingOrProjecting = true,
          isMirroringMode = true,
          isShizukuReady = true
        )
      )
    } finally {
      ScreenKeepAlivePreferences.clear(context)
    }
  }
}

