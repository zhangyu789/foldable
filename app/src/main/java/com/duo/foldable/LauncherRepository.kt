package com.duo.foldable

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.util.Log

private const val TAG = "LauncherRepository"

data class AppEntry(
    val label: String,
    val icon: Drawable,
    val user: UserHandle,
    val component: ComponentName
)

/**
 * Launcher app repository: enumerates launchable apps and provides foreground launch.
 * Pixel / Android 15+ enforce BAL (background activity launch) more strictly; foreground Activity options are required.
 */
class LauncherRepository(private val context: Context) {

    private val launcherApps: LauncherApps by lazy {
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    }

    fun getApps(): List<AppEntry> {
        val fromLauncher = fromLauncherApps()
        if (fromLauncher.isNotEmpty()) return fromLauncher
        return fromPackageManager()
    }

    private fun fromLauncherApps(): List<AppEntry> {
        val out = mutableListOf<AppEntry>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return out
        return try {
            for (profile in launcherApps.profiles) {
                for (info in launcherApps.getActivityList(null, profile)) {
                    val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        info.getIcon(0)
                    } else {
                        info.applicationInfo?.loadIcon(context.packageManager) ?: continue
                    }
                    out.add(
                        AppEntry(
                            label = info.label?.toString().orEmpty(),
                            icon = icon,
                            user = profile,
                            component = info.componentName
                        )
                    )
                }
            }
            out.filter { it.label.isNotBlank() }
                .distinctBy { it.component }
                .sortedBy { it.label.lowercase() }
        } catch (t: Throwable) {
            Log.w(TAG, "fromLauncherApps failed", t)
            emptyList()
        }
    }

    private fun fromPackageManager(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { resolve ->
                val ai = resolve.activityInfo ?: return@mapNotNull null
                AppEntry(
                    label = ai.loadLabel(pm).toString(),
                    icon = ai.loadIcon(pm),
                    user = android.os.Process.myUserHandle(),
                    component = ComponentName(ai.packageName, ai.name)
                )
            }
            .filter { it.label.isNotBlank() }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Launch from a foreground Activity to avoid Android 15 BAL blocking.
     */
    fun launch(app: AppEntry, activity: Activity, sourceBounds: Rect = Rect()) {
        val optsBundle = try {
            ActivityOptions.makeBasic().toBundle()
        } catch (_: Throwable) {
            null
        }

        try {
            launcherApps.startMainActivity(app.component, app.user, sourceBounds, optsBundle)
            return
        } catch (t: Throwable) {
            Log.w(TAG, "LauncherApps.startMainActivity failed, fallback", t)
        }

        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = app.component
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                setSourceBounds(sourceBounds)
            }
            if (optsBundle != null) {
                activity.startActivity(intent, optsBundle)
            } else {
                activity.startActivity(intent)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startActivity failed for ${app.component}", t)
        }
    }

    /** Legacy API: without Activity, use NEW_TASK (may still be blocked by BAL). */
    fun launch(app: AppEntry, sourceBounds: Rect = Rect()) {
        (context as? Activity)?.let { launch(app, it, sourceBounds); return }
        try {
            launcherApps.startMainActivity(app.component, app.user, sourceBounds, Bundle())
        } catch (t: Throwable) {
            val intent = Intent().setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
