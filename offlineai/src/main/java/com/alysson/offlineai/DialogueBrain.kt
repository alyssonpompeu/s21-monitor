package com.alysson.offlineai

/**
 * Lightweight local conversational layer for intent resolution and project continuity.
 * V5 reads history from SQLite so process death or model switching does not erase context.
 */
class DialogueBrain(private val libraryStore: LibraryStore? = null) {

    fun buildPrompt(
        projectId: Long,
        projectName: String,
        originalQuestion: String,
        lexicalContext: String,
        libraryContext: String,
        userName: String,
        answerLength: AppPreferences.AnswerLength,
        qualityProfile: AppPreferences.QualityProfile,
        specificInstruction: String,
    ): String {
        val interpretationHint = normalizeCasualPtBr(originalQuestion)
        val historyLimit = when (qualityProfile) {
            AppPreferences.QualityProfile.ADVANCED -> 8
            AppPreferences.QualityProfile.INTERMEDIATE -> 4
            AppPreferences.QualityProfile.FAST -> 2
        }
        val recent = recentConversation(projectId, historyLimit)

        return buildString {
            appendLine("<preferencias_locais>")
            appendLine("Projeto ativo: $projectName")
            if (userName.isNotBlank()) appendLine("Nome informado pelo usuário: $userName")
            appendLine("Qualidade local: Alta qualidade")
            appendLine(AppPreferences.QualityProfile.ADVANCED.instruction)
            appendLine("Extensão desejada: ${answerLength.label}")
            appendLine(answerLength.instruction)
            if (answerLength == AppPreferences.AnswerLength.SPECIFIC && specificInstruction.isNotBlank()) {
                appendLine("Preferência específica: ${specificInstruction.take(1000)}")
            }
            appendLine("O perfil de qualidade é configuração local; não alegue ser ou usar um modelo de nuvem específico.")
            appendLine("</preferencias_locais>")
            appendLine()

            if (recent.isNotBlank()) {
                appendLine(recent)
                appendLine()
            }
            if (lexicalContext.isNotBlank()) {
                appendLine(lexicalContext)
                appendLine()
            }
            if (libraryContext.isNotBlank()) {
                appendLine(libraryContext)
                appendLine()
            }

            appendLine("<orientacao_de_intencao>")
            appendLine("Entenda primeiro o que a pessoa provavelmente quis dizer; não responda de forma excessivamente literal.")
            appendLine("Considere erros de digitação, abreviações, falta de acentos, gírias, frases incompletas e referências às mensagens anteriores do mesmo projeto.")
            appendLine("Se houver uma interpretação claramente mais provável e de baixo risco, use-a sem interromper a conversa com confirmação desnecessária.")
            appendLine("Só peça esclarecimento quando interpretações plausíveis levarem a respostas materialmente diferentes ou faltar um dado essencial.")
            appendLine("Se o pedido envolver código, preserve requisitos, identifique riscos de implementação e entregue a solução mais executável possível.")
            appendLine("Nunca mostre tags <think>, raciocínio interno ou texto de scratchpad. Entregue apenas a resposta final útil.")
            if (interpretationHint != originalQuestion) {
                appendLine("Leitura auxiliar normalizada: $interpretationHint")
                appendLine("A frase original continua sendo a fonte principal; a leitura auxiliar serve apenas para compreender linguagem informal.")
            }
            appendLine("</orientacao_de_intencao>")
            appendLine()
            appendLine("<pergunta_usuario>")
            appendLine(originalQuestion)
            append("</pergunta_usuario>")
        }
    }

    fun recordTurn(projectId: Long, user: String, assistant: String) {
        libraryStore?.recordTurn(projectId, user, assistant, "text.qwen")
    }

    fun clearProject(projectId: Long) {
        libraryStore?.clearConversation(projectId)
    }

    private fun recentConversation(projectId: Long, limit: Int): String {
        val turns = libraryStore?.recentConversationTurns(projectId, limit).orEmpty()
        if (turns.isEmpty()) return ""
        return buildString {
            appendLine("<historico_conversacional_local>")
            appendLine("Histórico persistente apenas deste projeto. Use-o para continuidade, pronomes, elipses e preferências já expressas.")
            turns.forEach { turn ->
                appendLine("Usuário: ${turn.user.take(MAX_USER_CONTEXT)}")
                if (turn.assistant.isNotBlank()) appendLine("Assistente: ${turn.assistant.take(MAX_ASSISTANT_CONTEXT)}")
            }
            append("</historico_conversacional_local>")
        }
    }

    private fun normalizeCasualPtBr(text: String): String {
        var normalized = text
        REPLACEMENTS.forEach { (from, to) ->
            normalized = Regex("(?i)\\b${Regex.escape(from)}\\b").replace(normalized) { match ->
                preserveInitialCase(match.value, to)
            }
        }
        return normalized.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    private fun preserveInitialCase(original: String, replacement: String): String {
        return if (original.firstOrNull()?.isUpperCase() == true) {
            replacement.replaceFirstChar { it.uppercase() }
        } else replacement
    }

    companion object {
        private const val MAX_USER_CONTEXT = 1800
        private const val MAX_ASSISTANT_CONTEXT = 5000

        private val REPLACEMENTS = linkedMapOf(
            "vc" to "você",
            "vcs" to "vocês",
            "oq" to "o que",
            "q" to "que",
            "pq" to "porque",
            "tb" to "também",
            "tbm" to "também",
            "nao" to "não",
            "n" to "não",
            "pfv" to "por favor",
            "dps" to "depois",
            "agr" to "agora",
            "blz" to "beleza",
            "msg" to "mensagem",
            "app" to "aplicativo",
        )
    }
}
