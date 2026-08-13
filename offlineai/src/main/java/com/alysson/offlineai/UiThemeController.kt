package com.alysson.offlineai

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView

/**
 * Lightweight native theme layer for the S21 build.
 *
 * This deliberately avoids AppCompat/Compose so the UI keeps using Android framework Views that
 * are already compiled on the device. The visual language borrows Material 3 ideas (tonal
 * surfaces, large rounded fields, restrained accents) without adding a large UI runtime.
 */
class UiThemeController(private val context: Context) {

    enum class Mode(val label: String) {
        SYSTEM("Sistema"),
        LIGHT("Claro"),
        DARK("Escuro")
    }

    data class Palette(
        val background: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val text: Int,
        val secondaryText: Int,
        val accent: Int,
        val accentSoft: Int,
        val border: Int,
        val liveSurface: Int,
        val danger: Int,
        val success: Int,
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(): Mode = runCatching {
        Mode.valueOf(prefs.getString(KEY_MODE, Mode.SYSTEM.name).orEmpty())
    }.getOrDefault(Mode.SYSTEM)

    fun setMode(mode: Mode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun isDark(): Boolean = when (mode()) {
        Mode.DARK -> true
        Mode.LIGHT -> false
        Mode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(): Palette = if (isDark()) DARK else LIGHT

    fun applyWindow(activity: Activity) {
        val p = palette()
        activity.window.statusBarColor = p.background
        activity.window.navigationBarColor = p.background
        activity.window.setDecorFitsSystemWindows(false)
        val lightFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(
            if (isDark()) 0 else lightFlags,
            lightFlags,
        )
    }

    fun applySystemInsets(view: View) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
        view.requestApplyInsets()
    }

    fun fieldDrawable(radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(palette().surface)
        setStroke(1, palette().border)
    }

    fun cardDrawable(radiusPx: Float, live: Boolean = false): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(if (live) palette().liveSurface else palette().surfaceAlt)
        setStroke(1, palette().border)
    }

    fun circleDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(palette().surface)
        setStroke(1, palette().border)
    }

    /** Maps the legacy v5 colors to the new palette without inflating a second UI toolkit. */
    fun polishTextTree(root: View) {
        val p = palette()
        if (root is TextView) {
            val current = root.currentTextColor
            root.setTextColor(
                when (current) {
                    Color.rgb(26, 115, 232) -> p.accent
                    Color.rgb(95, 99, 104), Color.rgb(110, 110, 110), Color.rgb(60, 64, 67) -> p.secondaryText
                    Color.rgb(176, 0, 32) -> p.danger
                    else -> p.text
                }
            )
            if (root.hintTextColors != null) root.setHintTextColor(p.secondaryText)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) polishTextTree(root.getChildAt(i))
        }
    }

    companion object {
        private const val PREFS = "unilaw_ui_v6"
        private const val KEY_MODE = "theme_mode"

        private val LIGHT = Palette(
            background = Color.rgb(247, 248, 251),
            surface = Color.WHITE,
            surfaceAlt = Color.rgb(241, 243, 247),
            text = Color.rgb(25, 28, 34),
            secondaryText = Color.rgb(91, 98, 111),
            accent = Color.rgb(76, 70, 212),
            accentSoft = Color.rgb(235, 233, 255),
            border = Color.rgb(220, 224, 232),
            liveSurface = Color.rgb(243, 239, 255),
            danger = Color.rgb(179, 38, 30),
            success = Color.rgb(28, 125, 85),
        )

        private val DARK = Palette(
            background = Color.rgb(14, 16, 20),
            surface = Color.rgb(23, 26, 32),
            surfaceAlt = Color.rgb(31, 35, 43),
            text = Color.rgb(244, 246, 249),
            secondaryText = Color.rgb(174, 181, 193),
            accent = Color.rgb(175, 169, 255),
            accentSoft = Color.rgb(44, 39, 77),
            border = Color.rgb(48, 54, 65),
            liveSurface = Color.rgb(34, 29, 57),
            danger = Color.rgb(255, 180, 171),
            success = Color.rgb(98, 212, 161),
        )
    }
}
