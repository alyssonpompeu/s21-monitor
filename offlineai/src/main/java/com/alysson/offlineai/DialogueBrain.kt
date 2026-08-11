package com.alysson.offlineai

import java.util.ArrayDeque

/**
 * Lightweight local conversational layer for intent resolution, project continuity and
 * user-selected response depth. No information leaves the device.
 */
class DialogueBrain {

    private data class Turn(
        val projectId: Long,
        val user: String,
        val assistant: String,
    )

    private val history = ArrayDeque<Turn>()

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
            AppPreferences.QualityProfile.ADVANCED -> 6
            AppPreferences.QualityProfile.INTERMEDIATE -> 4
            AppPreferences.QualityProfile.FAST -> 2
        }
        val recent = recentConversation(projectId, historyLimit)

        return buildString {
            appendLine("<preferencias_locais>")
            appendLine("Projeto ativo: $projectName")
            if (userName.isNotBlank()) appendLine("Nome informado pelo usuário: $userName")
            appendLine("Perfil de qualidade local: ${qualityProfile.label}")
            appendLine(qualityProfile.instruction)
            appendLine("Extensão desejada: ${answerLength.label}")
            appendLine(answerLength.instruction)
            if (answerLength == AppPreferences.AnswerLength.SPECIFIC && specificInstruction.isNotBlank()) {
                appendLine("Preferência específica: ${specificInstruction.take(1000)}")
            }
            appendLine("Esses perfis são configurações do modelo local; não alegue ser ou usar um modelo de nuvem específico.")
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
        val cleanUser = user.trim()
        val cleanAssistant = assistant.trim()
        if (cleanUser.isEmpty() || cleanAssistant.isEmpty()) return

        history.addLast(
            Turn(
                projectId = projectId,
                user = cleanUser.take(MAX_USER_CHARS),
                assistant = cleanAssistant.take(MAX_ASSISTANT_CHARS),
            )
        )
        while (history.size > MAX_TURNS_TOTAL) history.removeFirst()
    }

    private fun recentConversation(projectId: Long, limit: Int): String {
        val turns = history.filter { it.projectId == projectId }.takeLast(limit)
        if (turns.isEmpty()) return ""

        return buildString {
            appendLine("<historico_conversacional_local>")
            appendLine("Histórico recente apenas deste projeto. Use-o para continuidade, pronomes, elipses e preferências já expressas.")
            turns.forEach { turn ->
                appendLine("Usuário: ${turn.user}")
                appendLine("Assistente: ${turn.assistant}")
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
        } else {
            replacement
        }
    }

    companion object {
        private const val MAX_TURNS_TOTAL = 18
        private const val MAX_USER_CHARS = 900
        private const val MAX_ASSISTANT_CHARS = 1600

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
