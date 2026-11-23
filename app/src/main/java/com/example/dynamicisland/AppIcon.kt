package com.example.dynamicisland

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Ana ekran ikonunu beyaz / koyu / siyah arasında değiştirir (AndroidManifest'teki üç activity-alias). */
object AppIcon {
    const val LIGHT = 0
    const val DARK = 1
    const val BLACK = 2

    private val ALIASES = listOf(
        "com.example.dynamicisland.IconLight",
        "com.example.dynamicisland.IconDark",
        "com.example.dynamicisland.IconBlack",
    )

    private fun cn(ctx: Context, name: String) = ComponentName(ctx.packageName, name)

    /** Şu an açık olan ikon. IconLight manifestte varsayılan olarak açık, diğerleri ancak elle açılırsa ENABLED döner. */
    fun current(ctx: Context): Int {
        val pm = ctx.packageManager
        for (i in listOf(DARK, BLACK)) {
            if (pm.getComponentEnabledSetting(cn(ctx, ALIASES[i])) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i
        }
        return LIGHT
    }

    /** DONT_KILL_APP: ikon değişirken overlay ve erişilebilirlik servisleri kapanmasın. Önce yenisi açılır, sonra diğerleri kapanır. */
    fun set(ctx: Context, which: Int) {
        val pm = ctx.packageManager
        pm.setComponentEnabledSetting(cn(ctx, ALIASES[which]), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        ALIASES.forEachIndexed { i, name ->
            if (i != which) pm.setComponentEnabledSetting(cn(ctx, name), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    /** Eski beyaz/koyu API'si: eski kodla uyumluluk için duruyor. */
    fun isDark(ctx: Context): Boolean = current(ctx) == DARK

    fun set(ctx: Context, dark: Boolean) = set(ctx, if (dark) DARK else LIGHT)
}
