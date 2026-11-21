package com.taylorlauncher.core

import android.content.Context
import android.content.Intent
import com.taylorlauncher.model.LauncherItem

object AppRepository {

    fun launcherPackages(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) }
        return (pm.queryIntentActivities(mainIntent, 0) + pm.queryIntentActivities(leanbackIntent, 0))
            .asSequence()
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName && !LauncherPrefs.HIDDEN_PACKAGES.contains(it.first) }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .toList()
    }

    fun launcherItems(context: Context): List<LauncherItem> {
        val pm = context.packageManager
        return launcherPackages(context).mapNotNull { (packageName, appName) ->
            val launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName)
                ?: pm.getLaunchIntentForPackage(packageName)
                ?: return@mapNotNull null
            LauncherItem(title = appName, intent = launchIntent, packageName = packageName)
        }
    }
}
