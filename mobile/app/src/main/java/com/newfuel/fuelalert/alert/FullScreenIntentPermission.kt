package com.newfuel.fuelalert.alert

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * PRD.md ss5.5 says to research this, not assume it - researched via
 * WebSearch against developer.android.com and cross-checked against a
 * second source before writing this file (both cited in
 * mobile/PROGRESS.md milestone 5's note). The confirmed facts this is
 * built on:
 *
 * - For apps targeting API 34+ that aren't calling/alarm apps (this
 *   one isn't), the Google Play Store revokes the default
 *   USE_FULL_SCREEN_INTENT grant at install time - it is NOT
 *   auto-granted the way it was on earlier Android versions, and this
 *   app must expect to start without it.
 * - `NotificationManager.canUseFullScreenIntent()` (API 34+ only) is
 *   the real, current API to check the live grant state.
 * - `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (API 34+ only)
 *   opens the system settings screen where the user can grant it -
 *   there is no in-app runtime-permission dialog for this, unlike
 *   ACCESS_FINE_LOCATION etc.
 * - Below API 34, USE_FULL_SCREEN_INTENT is a normal (non-runtime)
 *   manifest permission and is auto-granted at install, same as it
 *   always has been - `canUseFullScreenIntent()` doesn't exist there,
 *   so [isGranted] short-circuits to true rather than calling an API
 *   that wouldn't exist on that device.
 * - Even without the permission, a full-screen-intent notification
 *   degrades to an ordinary heads-up notification (still shown over a
 *   locked/off screen, but capped at ~60 seconds) rather than being
 *   silently dropped - so a user who never grants this still gets
 *   *something*, just not the guaranteed-unmissable full-screen
 *   activity PRD.md ss5.5 actually calls for.
 */
object FullScreenIntentPermission {

    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.canUseFullScreenIntent()
    }

    /** Only meaningful (and only ever needs to be called) on API 34+ -
      * [isGranted] already returns true unconditionally below that, so
      * a caller following "check isGranted before asking" never reaches
      * this on an older device. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
