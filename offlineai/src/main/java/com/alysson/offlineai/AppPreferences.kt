package com.alysson.offlineai

import android.content.Context

class AppPreferences(context: Context) {

    enum class AnswerLength(val label: String, val instruction: String) {
        VERY_LONG("Muito grande", "Responda de forma extensa, com detalhes, contexto, exemplos e etapas quando forem úteis."),
        MEDIUM("Médio", "Responda com equilíbrio: completo, mas sem alongar pontos que não ajudam."),
        SUMMARY("Resumido", "Responda de forma curta e direta, preservando apenas o essencial."),
        SPECIFIC("Específico", "Foque estritamente no que foi pedido e evite conteúdo lateral.")
    }

    enum class QualityProfile(
        val label: String,
        val maxPredictTokens: Int,
        val maxLibrarySnippets: Int,
        val instruction: String,
    ) {
        ADVANCED(
            "Avançado",
            1536,
            9,
            "Analise com mais cuidado, verifique premissas, conecte contexto anterior e fontes locais, e priorize precisão antes de velocidade."
        ),
        INTERMEDIATE(
            "Intermediário",
            900,
            6,
            "Equilibre profundidade, clareza e velocidade. Resolva a intenção provável sem raciocínio desnecessariamente longo."
        ),
        FAST(
            "Rápido",
            420,
            3,
            "Priorize resposta rápida e objetiva. Use apenas o contexto necessário e vá direto ao resultado."
        )
    }

    data class Settings(
        val userName: String,
        val answerLength: AnswerLength,
        val qualityProfile: QualityProfile,
        val specificInstruction: String,
        val activeProjectId: Long,
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        userName = prefs.getString(KEY_NAME, "")?.trim().orEmpty(),
        answerLength = enumValueOrDefault(prefs.getString(KEY_LENGTH, null), AnswerLength.MEDIUM),
        qualityProfile = enumValueOrDefault(prefs.getString(KEY_QUALITY, null), QualityProfile.INTERMEDIATE),
        specificInstruction = prefs.getString(KEY_SPECIFIC, "")?.trim().orEmpty(),
        activeProjectId = prefs.getLong(KEY_PROJECT, 1L),
    )

    fun setUserName(value: String) {
        prefs.edit().putString(KEY_NAME, value.trim().take(80)).apply()
    }

    fun setAnswerLength(value: AnswerLength) {
        prefs.edit().putString(KEY_LENGTH, value.name).apply()
    }

    fun setQualityProfile(value: QualityProfile) {
        prefs.edit().putString(KEY_QUALITY, value.name).apply()
    }

    fun setSpecificInstruction(value: String) {
        prefs.edit().putString(KEY_SPECIFIC, value.trim().take(1200)).apply()
    }

    fun setActiveProjectId(value: Long) {
        prefs.edit().putLong(KEY_PROJECT, value).apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T {
        return runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)
    }

    companion object {
        private const val PREFS = "offline_ai_preferences_v2"
        private const val KEY_NAME = "user_name"
        private const val KEY_LENGTH = "answer_length"
        private const val KEY_QUALITY = "quality_profile"
        private const val KEY_SPECIFIC = "specific_instruction"
        private const val KEY_PROJECT = "active_project_id"
    }
}
