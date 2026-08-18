#!/usr/bin/env python3
from pathlib import Path

src = Path('offlineai/src/main/java/com/alysson/offlineai')

main_path = src / 'MainActivity.kt'
main = main_path.read_text(encoding='utf-8')
if 'InteractionMode.TEXT' in main:
    main = main.replace('InteractionMode.TEXT', 'InteractionMode.AUTO')
main_path.write_text(main, encoding='utf-8')

# Rewrite the router with Kotlin raw-string regexes so Python/Kotlin escaping cannot regress.
(src / 'SmartCapabilityRouter.kt').write_text(r'''package com.alysson.offlineai

import java.util.Locale

class SmartCapabilityRouter(
    private val exactTools: ExactToolEngine = ExactToolEngine(),
) {
    enum class Route { EXACT_TOOL, CHAT, WORK_OFFLINE, IMAGE, CODER, BUILDER, APK_ANALYSIS, LOG_ANALYSIS }
    data class Decision(val route: Route, val reason: String, val direct: ExactToolEngine.Result? = null)

    fun decide(question: String, workMode: Boolean = false): Decision {
        val direct = exactTools.tryHandle(question)
        if (direct.handled) return Decision(Route.EXACT_TOOL, direct.tool, direct)

        val q = question.lowercase(Locale.ROOT)
        if (workMode) return Decision(Route.WORK_OFFLINE, "Work Offline: tarefa multi-etapas")
        if (Regex("""\b(crie|gere|desenhe|imagem|foto|ilustração|render)\b""").containsMatchIn(q))
            return Decision(Route.IMAGE, "pedido visual")
        if (Regex("""\b(apk|androidmanifest|assinatura|zipalign|apksigner|não foi instalado|nao foi instalado)\b""").containsMatchIn(q))
            return Decision(Route.APK_ANALYSIS, "diagnóstico Android/APK")
        if (Regex("""\b(logcat|stacktrace|fatal exception|anr|crash|segfault)\b""").containsMatchIn(q))
            return Decision(Route.LOG_ANALYSIS, "diagnóstico de execução")
        if (Regex("""\b(código|codigo|programa|função|funcao|classe|kotlin|java|python|javascript|c\+\+|sql)\b""").containsMatchIn(q))
            return Decision(Route.CODER, "programação")
        if (Regex("""\b(criar app|crie um app|gerar apk|compilar apk|builder|executável|executavel|\.exe)\b""").containsMatchIn(q))
            return Decision(Route.BUILDER, "construção de artefato")
        return Decision(Route.CHAT, "Qwen + Biblioteca Neural")
    }
}
''', encoding='utf-8')

print('v8.1 compile fixes applied: AUTO mode + raw-string router regexes')
