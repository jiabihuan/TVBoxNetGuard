package com.jiabihuan.tvnetguard.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(
    val name: String,
    val packageName: String,
    val uid: Int,
    val icon: Drawable?,
    val isSystem: Boolean,
    val hasInternet: Boolean
)

/** 读取设备上已安装、且具备联网能力的应用 */
object AppLoader {

    fun load(context: Context, includeSystem: Boolean): List<AppEntry> {
        val pm = context.packageManager
        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (t: Throwable) {
            emptyList<ApplicationInfo>()
        }
        val self = context.packageName
        val out = ArrayList<AppEntry>(installed.size)

        for (ai in installed) {
            val pkg = ai.packageName
            if (pkg == self) continue

            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystem) continue

            val hasInternet = hasInternetPermission(pm, pkg)
            if (!hasInternet && !includeSystem) continue

            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (t: Throwable) {
                pkg
            }
            val icon = try {
                pm.getApplicationIcon(ai)
            } catch (t: Throwable) {
                null
            }
            out.add(AppEntry(label, pkg, ai.uid, icon, isSystem, hasInternet))
        }
        out.sortWith(compareBy({ it.name.lowercase() }, { it.packageName }))
        return out
    }

    private fun hasInternetPermission(pm: PackageManager, pkg: String): Boolean {
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        } catch (t: Throwable) {
            false
        }
    }
}
