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
        displayName = "Qwen 3.5 Coder 0.5B",
        shortName = "Qwen Coder",
        tag = "Built-in Neural LLM",
        description = "Ultra-compact 0.5B parameter quantized coding model for deep on-device offline reasoning and autocomplete",
        placeholderPrompt = "Ask Qwen 3.5 Coder (Offline Neural LLM)...",
        iconEmoji = "🧠",
        sizeText = "Built-in"
    ),
    QWEN_CODER_1B(
        modelId = "local-qwen-coder-1.5b",
        displayName = "Qwen 3.5 Coder 1.5B",
        shortName = "Qwen 1.5B (1B)",
        tag = "1B Neural LLM",
        description = "High-accuracy 1.5B parameter neural code model for deep on-device multi-file synthesis & complex logic (Optional Download)",
        placeholderPrompt = "Ask Qwen 3.5 Coder 1.5B (Offline Neural LLM)...",
        iconEmoji = "🧠",
        sizeText = "1.1GB Q4_K_M"
    ),
    QWEN_CODER_2B(
        modelId = "local-qwen-coder-2b",
        displayName = "Qwen 3.5 Coder 2B",
        shortName = "Qwen 2B",
        tag = "2B Neural LLM",
        description = "High-efficiency 2B on-device neural code LLM for fast function synthesis and typing (Optional Download)",
        placeholderPrompt = "Ask Qwen 3.5 Coder 2B (Offline Neural LLM)...",
        iconEmoji = "🧠",
        sizeText = "1.3GB Q4_K_M"
    ),
    QWEN_CODER_3B(
        modelId = "local-qwen-coder-3b",
        displayName = "Qwen 3.5 Coder 3B",
        shortName = "Qwen 3B",
        tag = "3B Neural LLM",
        description = "Full-featured 3B code model for multi-file backend logic, database schemas and tests (Optional Download)",
        placeholderPrompt = "Ask Qwen 3.5 Coder 3B (Offline Neural LLM)...",
        iconEmoji = "🧠",
        sizeText = "2.1GB Q4_K_M"
    ),
    QWEN_CODER_4B(
        modelId = "local-qwen-coder-4b",
        displayName = "Qwen 3.5 Coder 4B",
        shortName = "Qwen 4B",
        tag = "4B Flagship LLM",
        description = "Flagship 4B on-device code LLM for fullstack application architecture & OWASP security audits (Optional Download)",
        placeholderPrompt = "Ask Qwen 3.5 Coder 4B (Offline Neural LLM)...",
        iconEmoji = "🧠",
        sizeText = "2.7GB Q4_K_M"
    );

    companion object {
        fun fromModelId(id: String?): LocalCodingDomain {
            if (id.isNullOrBlank()) return FULLSTACK
            return entries.find { it.modelId.equals(id, ignoreCase = true) } ?: FULLSTACK
        }

        fun isLocalModel(id: String?): Boolean {
            if (id.isNullOrBlank()) return false
            if (id.equals("auto", ignoreCase = true) || id.equals("smart-router", ignoreCase = true)) return false
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
