@file:Suppress("unused")

package dev.sweep.assistant.theme

import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import javax.swing.Icon

object SweepIcons {
    private fun loadIcon(path: String): Icon = IconLoader.getIcon(path, SweepIcons::class.java)

    // Using getter properties instead of val to prevent memory leaks during dynamic plugin unload
    val SweepIcon get() = loadIcon("/icons/sweep13x13.svg")
    val Sweep16x16 get() = loadIcon("/icons/sweep16x16.svg")

    fun Icon.scale(targetSize: Float): Icon = IconUtil.scale(this, null, targetSize / iconWidth.toFloat())
}
