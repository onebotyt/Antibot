package com.example.gemini.domain.model

/**
 * Domain-specialized on-device coding models (< 500MB, 100% offline, 0 cloud tokens).
 * Tailored for Backend development, Frontend UI/UX, and Cybersecurity auditing.
 */
enum class LocalCodingDomain(
    val modelId: String,
    val displayName: String,
    val shortName: String,
    val tag: String,
    val description: String,
    val placeholderPrompt: String,
    val iconEmoji: String,
    val sizeText: String
) {
    DEV_TEAM(
        modelId = "local-dev-team",
        displayName = "AntiBot Autonomous Dev Team",
        shortName = "Dev Team",
        tag = "3 Models · Full Stack",
        description = "Autonomous team: Backend Architect + Frontend UI/UX + Cybersecurity Specialist working in sync",
        placeholderPrompt = "Ask Dev Team (Full Stack Web/App)...",
        iconEmoji = "👥",
        sizeText = "3 Models Sync"
    ),
    BACKEND(
        modelId = "local-backend",
        displayName = "AntiBot Backend Architect",
        shortName = "Backend",
        tag = "APIs · DBs · Microservices",
        description = "Specialized for REST/GraphQL APIs, SQL/NoSQL schemas, Redis caching, Auth/JWT, Docker & Node/Go/Python servers",
        placeholderPrompt = "Ask Backend Architect (APIs, DB, Server)...",
        iconEmoji = "🛠️",
        sizeText = "0MB Built-in"
    ),
    FRONTEND(
        modelId = "local-frontend",
        displayName = "AntiBot Frontend & UI/UX Expert",
        shortName = "Frontend UI",
        tag = "Compose · Web · Tailwind",
        description = "Specialized for Jetpack Compose, React, Modern CSS/Tailwind, Vue, responsive layouts, animations & design systems",
        placeholderPrompt = "Ask Frontend & UI/UX Expert (Compose, Web, React)...",
        iconEmoji = "🎨",
        sizeText = "0MB Built-in"
    ),
    SECURITY(
        modelId = "local-security",
        displayName = "AntiBot Cybersecurity Specialist",
        shortName = "Cybersecurity",
        tag = "OWASP · PenTest · Hardening",
        description = "Specialized for vulnerability scans, OWASP Top 10 auditing, static code security, safe auth, TLS & pen-testing scripts",
        placeholderPrompt = "Ask Cybersecurity Auditor (PenTest, OWASP, Auth)...",
        iconEmoji = "🛡️",
        sizeText = "0MB Built-in"
    ),
    FULLSTACK(
        modelId = "local-model-only",
        displayName = "AntiBot Fullstack Engine",
        shortName = "Fullstack",
        tag = "General Code · Workspace",
        description = "100% on-device coding, workspace debugging, Git workflows, project analysis and terminal execution",
        placeholderPrompt = "Ask Fullstack Engine (Code, Git, Shell)...",
        iconEmoji = "⚡",
        sizeText = "0MB Built-in"
    ),
    QWEN_CODER(
        modelId = "local-qwen-coder-0.5b",
        displayName = "Qwen 2.5 Coder 0.5B",
        shortName = "Qwen Coder",
        tag = "350MB Neural LLM",
        description = "Ultra-compact 0.5B parameter quantized coding model for deep on-device offline reasoning and autocomplete",
        placeholderPrompt = "Ask Qwen 2.5 Coder (Offline Neural LLM)...",
        iconEmoji = "🧠",
        sizeText = "350MB Q4_K_M"
    );

    companion object {
        fun fromModelId(id: String?): LocalCodingDomain {
            if (id.isNullOrBlank()) return FULLSTACK
            return entries.find { it.modelId.equals(id, ignoreCase = true) } ?: FULLSTACK
        }

        fun isLocalModel(id: String?): Boolean {
            if (id.isNullOrBlank()) return false
            return id == "local-model-only" || id.startsWith("local-", ignoreCase = true)
        }

        val ALL_LOCAL_MODELS: List<AiModel> = entries.map { domain ->
            AiModel(
                id = domain.modelId,
                displayName = domain.displayName,
                family = ModelFamily.LOCAL,
                description = domain.description,
                baseName = domain.displayName
            )
        }
    }
}
