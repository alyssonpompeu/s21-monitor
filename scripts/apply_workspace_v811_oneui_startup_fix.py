#!/usr/bin/env python3
from pathlib import Path
import re

p = Path('offlineai/src/main/java/com/alysson/offlineai/UiThemeController.kt')
s = p.read_text(encoding='utf-8')

# One UI / Android 12 on the SM-G991B can throw inside DecorView.getWindowInsetsController()
# when Window#setDecorFitsSystemWindows(false) / Window#getInsetsController are touched before
# the content DecorView is fully attached. Keep startup conservative: system bars fit content by
# default and icon appearance is applied later through legacy View flags on an attached root.
s = s.replace('import android.view.WindowInsets\n', '')
s = s.replace('import android.view.WindowInsetsController\n', '')

old = '''    fun applyWindow(activity: Activity) {
        val p = palette()
        activity.window.statusBarColor = p.background
        activity.window.navigationBarColor = p.background
        activity.window.setDecorFitsSystemWindows(false)
        activity.window.isNavigationBarContrastEnforced = false
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
'''
new = '''    fun applyWindow(activity: Activity) {
        val p = palette()
        // Do not request edge-to-edge or WindowInsetsController here. On this One UI build the
        // DecorView controller may still be null during Activity.onCreate().
        runCatching { activity.window.statusBarColor = p.background }
        runCatching { activity.window.navigationBarColor = p.background }
        runCatching { activity.window.isNavigationBarContrastEnforced = false }
    }

    @Suppress("DEPRECATION")
    fun applySystemInsets(view: View) {
        // Called only after setContentView(). Posting once guarantees an attached root and avoids
        // the One UI WindowInsetsController startup path entirely.
        view.post {
            runCatching {
                val root = view.rootView ?: return@runCatching
                val lightMask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                val base = root.systemUiVisibility and lightMask.inv()
                root.systemUiVisibility = if (isDark()) base else base or lightMask
            }
        }
    }
'''
if old not in s:
    raise SystemExit('One UI UiThemeController patch point missing')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# Fresh diagnostic identity so the user can install this fix beside the broken v8.1 Fresh even
# if that APK was signed by a different ephemeral CI debug key.
g = Path('offlineai/build.gradle')
t = g.read_text(encoding='utf-8')
t, n = re.subn(r"applicationId\s+'[^']+'", "applicationId 'com.alysson.unilaw.s21.oneuifix'", t, count=1)
if n != 1: raise SystemExit('applicationId patch failed')
t, n = re.subn(r'versionCode\s+\d+', 'versionCode 23', t, count=1)
if n != 1: raise SystemExit('versionCode patch failed')
t, n = re.subn(r"versionName\s+'[^']+'", "versionName '8.1.1-oneui-startup-fix'", t, count=1)
if n != 1: raise SystemExit('versionName patch failed')
g.write_text(t, encoding='utf-8')

print('v8.1.1 One UI startup fix applied')
