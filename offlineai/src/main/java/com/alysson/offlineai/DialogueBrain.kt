package com.alysson.offlineai

import java.util.ArrayDeque

/**
 * Lightweight conversational layer that helps a small local model understand casual PT-BR,
 * follow-up references and the user's likely intent without sending anything off-device.
 */
class DialogueBrain {

    private data class Turn(
        val user: String,
        val assistant: String,
    )

    private val history = ArrayDeque<Turn>()

    fun buildPrompt(
        originalQuestion: String,
        lexicalContext: String,
        libraryContext: String,
    ): String {
        val interpretationHint = normalizeCasualPtBr(originalQuestion)
        val recent = recentConversation()

        return buildString {
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
            appendLine("Considere erros de digitação, abreviações, falta de acentos, gírias, frases incompletas e referências a mensagens anteriores.")
            appendLine("Se houver uma interpretação claramente mais provável e de baixo risco, use-a sem interromper a conversa com pergunta de confirmação.")
            appendLine("Só peça esclarecimento quando interpretações plausíveis levarem a respostas realmente diferentes ou quando faltar um dado essencial.")
            if (interpretationHint != originalQuestion) {
                appendLine("Leitura auxiliar normalizada: $interpretationHint")
                appendLine("A frase original continua sendo a fonte principal; a leitura auxiliar serve apenas para compreender abreviações.")
            }
            appendLine("</orientacao_de_intencao>")
            appendLine()
            appendLine("<pergunta_usuario>")
            appendLine(originalQuestion)
            append("</pergunta_usuario>")
        }
    }

    fun recordTurn(user: String, assistant: String) {
        val cleanUser = user.trim()
        val cleanAssistant = assistant.trim()
        if (cleanUser.isEmpty() || cleanAssistant.isEmpty()) return

        history.addLast(
            Turn(
                user = cleanUser.take(MAX_USER_CHARS),
                assistant = cleanAssistant.take(MAX_ASSISTANT_CHARS),
            )
        )
        while (history.size > MAX_TURNS) history.removeFirst()
    }

    private fun recentConversation(): String {
        if (history.isEmpty()) return ""

        return buildString {
            appendLine("<historico_conversacional_local>")
            appendLine("Use este histórico apenas para resolver continuidade, pronomes, elipses e preferências já expressas pelo usuário.")
            history.forEach { turn ->
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
        private const val MAX_TURNS = 4
        private const val MAX_USER_CHARS = 700
        private const val MAX_ASSISTANT_CHARS = 1100

        private val REPLACEMENTS = linkedMapOf(
            "vc" to "você",
            "vcs" to "vocês",
            "oq" to "o que",
            "tb" to "também",
            "tbm" to "também",
            "nao" to "não",
            "pfv" to "por favor",
            "dps" to "depois",
            "agr" to "agora",
            "blz" to "beleza",
        )
    }
}
