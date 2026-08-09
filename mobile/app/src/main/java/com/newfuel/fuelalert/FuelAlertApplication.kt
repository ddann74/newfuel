package com.newfuel.fuelalert

import android.app.Application

/**
 * Deliberately empty beyond being a named Application class for now -
 * TripMonitorService's lifecycle (milestone 3, PROGRESS.md) and the
 * full-screen alert's NotificationChannel setup (milestone 5) will live
 * here once built. Declared now (and wired into AndroidManifest.xml)
 * rather than added later so those milestones don't need a manifest
 * change alongside their own code.
 */
class FuelAlertApplication : Application()
