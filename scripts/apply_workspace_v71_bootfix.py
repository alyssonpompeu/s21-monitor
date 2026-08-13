#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'v7.1 patch point missing: {label}')
    return text.replace(old, new, 1)


main_path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
main = main_path.read_text(encoding='utf-8')

# Theme setup happens before onCreate. Never let a damaged/stale theme preference prevent launch.
main = replace_once(
    main,
    '''    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiThemeController.wrap(newBase))
    }
''',
    '''    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(runCatching { UiThemeController.wrap(newBase) }.getOrDefault(newBase))
    }
''',
    'safe attachBaseContext',
)

# Protect every synchronous first-boot component (preferences, SQLite, persistence, UI construction).
main = replace_once(
    main,
    '''        super.onCreate(savedInstanceState)
        uiTheme = UiThemeController(applicationContext)
''',
    '''        super.onCreate(savedInstanceState)
        try {
        uiTheme = UiThemeController(applicationContext)
''',
    'startup try begin',
)

main = replace_once(
    main,
    '''        maybeRequestPersistenceFolder()
        prepareOfflineEngine()
    }

    private fun buildUi() {
''',
    '''        maybeRequestPersistenceFolder()
        prepareOfflineEngine()
        } catch (t: Throwable) {
            showBootstrapFailure(t)
        }
    }

    private fun showBootstrapFailure(t: Throwable) {
        ready = false
        runCatching {
            File(filesDir, "unilaw-startup-crash.txt").writeText(
                buildString {
                    appendLine("Unilaw AI v7.1 startup recovery")
                    appendLine("time_ms=${System.currentTimeMillis()}")
                    appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("sdk=${android.os.Build.VERSION.SDK_INT}")
                    appendLine()
                    append(t.stackTraceToString())
                },
                Charsets.UTF_8,
            )
        }
        val detail = t.message ?: t.javaClass.simpleName
        setContentView(TextView(this).apply {
            text = "Unilaw iniciou em modo de recuperação.\n\nFalha na inicialização: $detail\n\nA v7.1 impediu o fechamento automático. Um diagnóstico foi salvo em unilaw-startup-crash.txt."
            textSize = 16f
            setTextColor(Color.rgb(32, 33, 36))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(22), dp(28), dp(22), dp(28))
            gravity = Gravity.TOP or Gravity.START
            setTextIsSelectable(true)
        })
    }

    private fun buildUi() {
''',
    'startup recovery catch',
)

# Resource telemetry is useful, but it must never be able to terminate the app.
old_monitor = '''    private fun startResourceMonitor() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                val sample = resourceMonitor.sample()
                val guard = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
                withContext(Dispatchers.Main) {
                    val cpu = sample.cpuPercent?.let { "$it%" } ?: "—"
                    val gpu = sample.gpuPercent?.let { "$it%" } ?: "N/D"
                    resourceStatus.text = "CPU $cpu   GPU $gpu   RAM ${sample.ramPercent}%"
                    safetyStatus.text = resourceGuard.shortStatus()
                    safetyStatus.setTextColor(if (guard.safe) Color.rgb(95, 99, 104) else Color.rgb(176, 0, 32))
                }
                delay(2000)
            }
        }
    }
'''
new_monitor = '''    private fun startResourceMonitor() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    val sample = resourceMonitor.sample()
                    val guard = resourceGuard.state(ResourceGuard.TaskKind.CHAT)
                    withContext(Dispatchers.Main) {
                        val cpu = sample.cpuPercent?.let { "$it%" } ?: "—"
                        val gpu = sample.gpuPercent?.let { "$it%" } ?: "N/D"
                        resourceStatus.text = "CPU $cpu   GPU $gpu   RAM ${sample.ramPercent}%"
                        safetyStatus.text = resourceGuard.shortStatus()
                        safetyStatus.setTextColor(if (guard.safe) Color.rgb(95, 99, 104) else Color.rgb(176, 0, 32))
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        if (::safetyStatus.isInitialized) safetyStatus.text = "Telemetria indisponível • app protegido"
                    }
                }
                delay(2000)
            }
        }
    }
'''
if old_monitor in main:
    main = main.replace(old_monitor, new_monitor, 1)
else:
    print('v7.1: resource monitor block already differs; leaving it unchanged')

main_path.write_text(main, encoding='utf-8')

# The v7 field report is an immediate launch crash. For the recovery APK, remove R8/shrinking
# from DEBUG so the code path matches the older known-good sideload lineage as closely as possible.
gradle_path = Path('offlineai/build.gradle')
gradle = gradle_path.read_text(encoding='utf-8')
gradle = re.sub(
    r'''debug\s*\{\s*minifyEnabled\s+true\s*shrinkResources\s+true\s*proguardFiles[^\n]*\n\s*\}''',
    '''debug {
            minifyEnabled false
            shrinkResources false
        }''',
    gradle,
    count=1,
    flags=re.S,
)
gradle, count = re.subn(r'versionCode\s+\d+', 'versionCode 21', gradle, count=1)
if count != 1:
    raise SystemExit('v7.1 could not update versionCode')
gradle, count = re.subn(r"versionName\s+'[^']+'", "versionName '7.1.0-corepacks-recovery'", gradle, count=1)
if count != 1:
    raise SystemExit('v7.1 could not update versionName')
gradle_path.write_text(gradle, encoding='utf-8')

manifest_path = Path('offlineai/src/main/AndroidManifest.xml')
manifest = manifest_path.read_text(encoding='utf-8')
manifest = re.sub(r'android:label="[^"]+"', 'android:label="Unilaw AI v7.1"', manifest, count=1)
manifest_path.write_text(manifest, encoding='utf-8')

print('Workspace v7.1 crash-safe boot patch applied')
