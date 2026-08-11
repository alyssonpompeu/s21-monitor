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
print('Workspace v3 MainActivity patch applied')
