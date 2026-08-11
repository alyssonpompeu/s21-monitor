#!/usr/bin/env python3
from pathlib import Path

path = Path('offlineai/src/main/java/com/alysson/offlineai/MainActivity.kt')
text = path.read_text(encoding='utf-8')

needle = '''        toolbar.addView(projectTitle, LinearLayout.LayoutParams(0, dp(44), 1f))

        val settingsButton = smallAction("⚙", "Personalização") { showPersonalizationDialog() }
'''
replacement = '''        toolbar.addView(projectTitle, LinearLayout.LayoutParams(0, dp(44), 1f))

        val pluginButton = smallAction("◇", "Plugins e Builder Studio") { openPluginManager() }
        toolbar.addView(pluginButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        val settingsButton = smallAction("⚙", "Personalização") { showPersonalizationDialog() }
'''
if needle not in text:
    raise SystemExit('Toolbar insertion point not found')
text = text.replace(needle, replacement, 1)

needle = '''    private fun showPersonalizationDialog() {
'''
replacement = '''    private fun openPluginManager() {
        if (importing || generationJob?.isActive == true) return
        ready = false
        input.isEnabled = false
        input.hint = "Liberando memória para plugins…"
        scope.launch {
            try {
                if (engineModelLoaded && ::engine.isInitialized) {
                    withContext(Dispatchers.IO) { engine.cleanUp() }
                    engineModelLoaded = false
                }
                startActivityForResult(Intent(this@MainActivity, PluginManagerActivity::class.java), REQUEST_PLUGINS)
            } catch (t: Throwable) {
                ready = true
                input.isEnabled = true
                input.hint = "Pergunte do seu jeito"
                activateResultMode()
                answer.text = "Não foi possível abrir os plugins: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    private fun showPersonalizationDialog() {
'''
if needle not in text:
    raise SystemExit('Personalization insertion point not found')
text = text.replace(needle, replacement, 1)

needle = '''        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == REQUEST_IMAGE_MODEL) {
'''
replacement = '''        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PLUGINS) {
            scope.launch {
                try {
                    if (!engineModelLoaded && ::engine.isInitialized) loadChatModel()
                } catch (t: Throwable) {
                    ready = false
                    input.isEnabled = false
                    input.hint = "IA offline indisponível"
                    activateResultMode()
                    answer.text = "Não foi possível recarregar a IA após o Builder Studio: ${t.message ?: t.javaClass.simpleName}"
                }
            }
            return
        }
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == REQUEST_IMAGE_MODEL) {
'''
if needle not in text:
    raise SystemExit('Activity result insertion point not found')
text = text.replace(needle, replacement, 1)

needle = '''        private const val REQUEST_IMAGE_MODEL = 4012
'''
replacement = '''        private const val REQUEST_IMAGE_MODEL = 4012
        private const val REQUEST_PLUGINS = 4013
'''
if needle not in text:
    raise SystemExit('Request-code insertion point not found')
text = text.replace(needle, replacement, 1)

text = text.replace(
    'Perfis locais do Qwen: Avançado, Intermediário e Rápido • sem nuvem',
    'Perfis locais do Qwen • sem nuvem • ◇ abre plugins e Builder Studio',
    1,
)

path.write_text(text, encoding='utf-8')

# Kotlin 2.3 compatibility fixes discovered by the first Plugin v3 CI run.
artifact = Path('offlineai/src/main/java/com/alysson/offlineai/ArtifactBuilder.kt')
artifact_text = artifact.read_text(encoding='utf-8')
old = '''        if (head < 0) return html.replaceFirst(Regex("(?i)<html[^>]*>")) { it.value + "<head>$policy</head>" }
'''
new = '''        if (head < 0) {
            val match = Regex("(?i)<html[^>]*>").find(html) ?: return html
            val insertion = match.range.last + 1
            return html.substring(0, insertion) + "<head>$policy</head>" + html.substring(insertion)
        }
'''
if old not in artifact_text:
    raise SystemExit('ArtifactBuilder CSP compatibility point not found')
artifact.write_text(artifact_text.replace(old, new, 1), encoding='utf-8')

studio = Path('offlineai/src/main/java/com/alysson/offlineai/BuilderStudioActivity.kt')
studio_text = studio.read_text(encoding='utf-8')
old = '''        scroll.addView(sourceField, ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT))
'''
new = '''        scroll.addView(sourceField, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
'''
if old not in studio_text:
    raise SystemExit('BuilderStudio ScrollView layout compatibility point not found')
studio.write_text(studio_text.replace(old, new, 1), encoding='utf-8')

print('Workspace v3 UI and Kotlin compatibility patches applied')
