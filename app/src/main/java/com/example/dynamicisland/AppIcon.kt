package com.example.dynamicisland

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Ana ekran ikonunu beyaz / koyu arasında değiştirir (AndroidManifest'teki iki activity-alias). */
object AppIcon {
    private const val LIGHT = "com.example.dynamicisland.IconLight"
    private const val DARK = "com.example.dynamicisland.IconDark"

    private fun cn(ctx: Context, name: String) = ComponentName(ctx.packageName, name)

    fun isDark(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(cn(ctx, DARK)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** DONT_KILL_APP: ikon değişirken overlay ve erişilebilirlik servisleri kapanmasın. */
    fun set(ctx: Context, dark: Boolean) {
        val pm = ctx.packageManager
        val on = cn(ctx, if (dark) DARK else LIGHT)
        val off = cn(ctx, if (dark) LIGHT else DARK)
        pm.setComponentEnabledSetting(on, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(off, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}
