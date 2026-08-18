#!/usr/bin/env python3
from pathlib import Path
import re

p = Path('offlineai/src/main/java/com/alysson/offlineai/UiThemeController.kt')
s = p.read_text(encoding='utf-8')

# Galaxy S21 / One UI startup hardening:
# never touch WindowInsetsController, edge-to-edge, bar colors, contrast, or DecorView-dependent
# APIs while Activity.onCreate() is still constructing the content hierarchy. Everything related
# to system bars is deferred until after setContentView() through applySystemInsets(view).
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
        // Intentionally empty during startup. On this One UI build, touching Window/DecorView
        // appearance while onCreate() is still constructing the view tree can reach a null
        // DecorView.getWindowInsetsController(). System-bar polish is applied after attachment.
    }

    @Suppress("DEPRECATION")
    fun applySystemInsets(view: View) {
        // MainActivity calls this only after setContentView(). Post once more so the decor/root is
        // attached before any system-bar operation. No WindowInsetsController is used at all.
        view.post {
            runCatching {
                val root = view.rootView ?: return@runCatching
                val activity = view.context as? Activity
                val p = palette()
                if (activity != null) {
                    activity.window.statusBarColor = p.background
                    activity.window.navigationBarColor = p.background
                    activity.window.isNavigationBarContrastEnforced = false
                }
                val lightMask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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

# Fresh diagnostic identity so this fix installs beside the broken v8.1 build even if that APK
# was signed by another ephemeral CI debug key.
g = Path('offlineai/build.gradle')
t = g.read_text(encoding='utf-8')
t, n = re.subn(r"applicationId\s+'[^']+'", "applicationId 'com.alysson.unilaw.s21.oneuifix'", t, count=1)
if n != 1:
    raise SystemExit('applicationId patch failed')
t, n = re.subn(r'versionCode\s+\d+', 'versionCode 24', t, count=1)
if n != 1:
    raise SystemExit('versionCode patch failed')
t, n = re.subn(r"versionName\s+'[^']+'", "versionName '8.1.2-oneui-hardfix'", t, count=1)
if n != 1:
    raise SystemExit('versionName patch failed')
g.write_text(t, encoding='utf-8')

print('v8.1.2 One UI hard startup fix applied')
