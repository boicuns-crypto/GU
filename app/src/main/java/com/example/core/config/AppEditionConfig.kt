package com.example.core.config

import androidx.annotation.VisibleForTesting
import com.example.BuildConfig

/**
 * Compile-time edition descriptor distinguishing the two signed builds:
 * - **Full** (`BuildConfig.MIRROR_ONLY_EDITION == false`): Cockpit + Embedded Maps + Shizuku + Mirroring.
 * - **Mirror Only** (`BuildConfig.MIRROR_ONLY_EDITION == true`): Dedicated ultra-lightweight USB Mirroring edition
 *   without Cockpit Presentation, WebView, Launcher icon loading, NotificationListener, or Shizuku overhead.
 */
object AppEditionConfig {
    @Volatile
    private var mirrorOnlyOverrideForTesting: Boolean? = null

    val isMirrorOnly: Boolean
        get() = mirrorOnlyOverrideForTesting ?: BuildConfig.MIRROR_ONLY_EDITION

    val editionLabel: String
        get() = if (isMirrorOnly) "Mirror Only" else "Full"

    val appDisplayName: String
        get() = if (isMirrorOnly) "Jetta Mirror" else "Jetta VS7"

    @VisibleForTesting
    internal fun setMirrorOnlyOverrideForTesting(override: Boolean?) {
        mirrorOnlyOverrideForTesting = override
    }
}
