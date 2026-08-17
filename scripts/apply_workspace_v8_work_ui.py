#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v8 work-ui patch point missing: {label}')
    return text.replace(old, new, 1)

p = Path('workonline/src/main/java/com/alysson/workonline/WorkOnlineActivity.kt')
s = p.read_text(encoding='utf-8')

s = replace_once(s, 'import android.content.Context\n', 'import android.content.Context\nimport android.content.res.Configuration\n', 'Configuration import')
s = replace_once(s, 'import android.graphics.Typeface\n', 'import android.graphics.Typeface\nimport android.graphics.drawable.GradientDrawable\n', 'drawable import')

# Add a compact palette after class declaration; Work companion follows the system light/dark theme.
s = replace_once(
    s,
    '''class WorkOnlineActivity : Activity() {
''',
    '''class WorkOnlineActivity : Activity() {
    private data class Palette(
        val background: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val text: Int,
        val muted: Int,
        val accent: Int,
        val accentSoft: Int,
        val border: Int,
        val dark: Boolean,
    )

    private fun palette(): Palette {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (dark) Palette(
            background = Color.rgb(18, 18, 20),
            surface = Color.rgb(29, 29, 33),
            surfaceAlt = Color.rgb(38, 37, 44),
            text = Color.rgb(244, 243, 248),
            muted = Color.rgb(177, 176, 186),
            accent = Color.rgb(184, 158, 255),
            accentSoft = Color.rgb(49, 41, 67),
            border = Color.rgb(67, 65, 75),
            dark = true,
        ) else Palette(
            background = Color.rgb(248, 248, 251),
            surface = Color.WHITE,
            surfaceAlt = Color.rgb(244, 242, 248),
            text = Color.rgb(28, 27, 31),
            muted = Color.rgb(92, 91, 99),
            accent = Color.rgb(92, 64, 181),
            accentSoft = Color.rgb(239, 233, 255),
            border = Color.rgb(222, 220, 228),
            dark = false,
        )
    }

    private fun roundedSurface(radius: Int, alternate: Boolean = false) = GradientDrawable().apply {
        val p = palette()
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(if (alternate) p.surfaceAlt else p.surface)
        setStroke(dp(1), p.border)
    }
''',
    'Work palette',
)

s = replace_once(
    s,
    '''        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
''',
    '''        val colors = palette()
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        window.decorView.systemUiVisibility = if (colors.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
''',
    'Work window theme',
)

# Main labels and copy.
s = s.replace('text = "Work Free"', 'text = "Unilaw Work"', 1)
s = s.replace('text = "ONLINE • Gemini grátis primeiro • fallback automático"', 'text = "WORK ONLINE • pesquisa + entrega • provedor configurável"', 1)
s = s.replace('hint = "Descreva o resultado que você quer produzir"', 'hint = "Descreva o trabalho que você quer concluir"', 1)
s = s.replace('pill("Executar trabalho")', 'pill("Iniciar Work")', 1)

# Theme primary surfaces/text. Do targeted replacements, not every Color literal used for semantic errors.
s = s.replace('setBackgroundColor(Color.WHITE)', 'setBackgroundColor(palette().background)', 1)
s = s.replace('setTextColor(Color.rgb(32, 33, 36))', 'setTextColor(palette().text)', 1)
s = s.replace('setTextColor(Color.rgb(26, 115, 232))', 'setTextColor(palette().accent)', 1)
s = s.replace('setTextColor(Color.rgb(95, 99, 104))', 'setTextColor(palette().muted)', 4)
s = s.replace('setHintTextColor(Color.rgb(128, 134, 139))', 'setHintTextColor(palette().muted)', 1)
s = s.replace('setBackgroundColor(Color.rgb(248, 249, 250))', 'background = roundedSurface(18)', 1)
s = s.replace('setTextColor(Color.rgb(55, 48, 75))', 'setTextColor(palette().text)', 1)
s = s.replace('setBackgroundColor(Color.rgb(245, 240, 255))', 'background = roundedSurface(16, alternate = true)', 1)

# If the pill helper uses a hard-coded light-blue drawable, give it the same professional palette.
s = s.replace('setColor(Color.rgb(26, 115, 232))', 'setColor(palette().accent)', 1)
s = s.replace('setTextColor(Color.WHITE)', 'setTextColor(if (palette().dark) Color.rgb(20, 17, 26) else Color.WHITE)', 1)

p.write_text(s, encoding='utf-8')

# Companion identity remains same package so existing encrypted keys/consent survive update.
g = Path('workonline/build.gradle')
t = g.read_text(encoding='utf-8')
t, n = re.subn(r'versionCode\s+\d+', 'versionCode 3', t, count=1)
if n != 1: raise SystemExit('v8 Work versionCode patch failed')
t, n = re.subn(r"versionName\s+'[^']+'", "versionName '3.0.0-unilaw-work-v8'", t, count=1)
if n != 1: raise SystemExit('v8 Work versionName patch failed')
g.write_text(t, encoding='utf-8')

print('v8 Work UI patch applied: polished system light/dark companion')
