package com.ayra.ai.app.ai.providers

internal object ExternalAiCatalog {
    private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)

    val curatedModels: List<AiModelInfo> = listOf(
        AiModelInfo(ModelCatalog.GEMINI_FLASH_MODEL, "Gemini 3.6 Flash ⚡", AiProviderType.GEMINI, "Ultra-fast GA multimodal flagship with web grounding", "1M tokens", true, false, true),
        AiModelInfo(ModelCatalog.GEMINI_PRO_MODEL, "Gemini 3.1 Pro 🧠", AiProviderType.GEMINI, "Frontier deep reasoning, code architecture & STEM synthesis (Blaze)", "2M tokens", true, true, false),
        AiModelInfo(ModelCatalog.GEMINI_FAST_LITE_MODEL, "Gemini 3.5 Flash-Lite 🚀", AiProviderType.GEMINI, "High-throughput low-latency conversational engine", "1M tokens", true, false, false),
        AiModelInfo(ModelCatalog.GEMINI_FLASH_35_MODEL, "Gemini 3.5 Flash ⚡", AiProviderType.GEMINI, "Reliable fast multimodal & coding fallback", "1M tokens", true, false, false),
        AiModelInfo(ModelCatalog.GEMINI_GEMMA4_MODEL, "Gemma 4 31B IT 🌐", AiProviderType.GEMINI, "Google Gemma 4 open-weights multilingual reasoning", "128k tokens", true, true, false),
        AiModelInfo(ModelCatalog.GROQ_QWEN_MODEL, "Groq Qwen 3.8 27B 🧠", AiProviderType.GROQ, "High-speed multimodal STEM, coding & multilingual reasoning on Groq LPU", "131k tokens", true, true, true),
        AiModelInfo(ModelCatalog.GROQ_GPT_OSS_MODEL, "Groq GPT-OSS 120B ⚡", AiProviderType.GROQ, "Open-weights 120B flagship on Groq LPU free developer tier", "131k tokens", false, true, false),
        AiModelInfo(ModelCatalog.GROQ_LLAMA_MODEL, "Groq Llama 3.1 8B Instant 🚀", AiProviderType.GROQ, "Ultra-low-latency fast conversational engine on Groq LPU", "128k tokens", false, false, false),
        AiModelInfo(ModelCatalog.CEREBRAS_LLAMA_MODEL, "Cerebras GPT-OSS 120B 🚀", AiProviderType.CEREBRAS, "Wafer-scale high-speed 120B inference on free developer tier", "128k tokens", false, true, true),
        AiModelInfo(ModelCatalog.CEREBRAS_QWEN_MODEL, "Cerebras Qwen 3.8 27B ⚡", AiProviderType.CEREBRAS, "Wafer-scale STEM, math & coding reasoning", "128k tokens", false, true, false),
        AiModelInfo(ModelCatalog.SAMBANOVA_DEEPSEEK_MODEL, "SambaNova DeepSeek V3.1 🔬", AiProviderType.SAMBANOVA, "Fast RDU inference for coding, math & deep reasoning", "128k tokens", false, true, false),
        AiModelInfo(ModelCatalog.SAMBANOVA_QWEN_MODEL, "SambaNova Qwen3 32B 🌐", AiProviderType.SAMBANOVA, "Fast RDU multilingual & STEM reasoning", "64k tokens", false, true, false),
        AiModelInfo(ModelCatalog.HF_QWEN_MODEL, "HF Qwen 2.5 72B Instruct 🤗", AiProviderType.HUGGINGFACE, "Open-weights Hugging Face Inference Router", "128k tokens", false, true, false),
        AiModelInfo(ModelCatalog.NVIDIA_LLAMA_MODEL, "NVIDIA Llama 3.3 70B Instruct 🦙", AiProviderType.NVIDIA_NIM, "Verified general, coding & multilingual NIM baseline", "128k tokens", false, true, true),
        AiModelInfo(ModelCatalog.NVIDIA_MINIMAX_MODEL, "MiniMax M3.1 Flash ⚡", AiProviderType.NVIDIA_NIM, "High-speed multimodal vision & coding intelligence via NVIDIA NIM", "128k tokens", true, true, false),
        AiModelInfo(ModelCatalog.NVIDIA_GLM_MODEL, "Z-AI GLM 5.3 Flash 🧠", AiProviderType.NVIDIA_NIM, "Fast STEM, mathematical reasoning & multilingual synthesis", "128k tokens", false, true, false),
        AiModelInfo(ModelCatalog.NVIDIA_MUSE_MODEL, "Meta Muse Glimmer 30B ✨", AiProviderType.NVIDIA_NIM, "Fluent creative writing, Bengali/Banglish & deep reasoning", "128k tokens", false, true, false),
        AiModelInfo(ModelCatalog.NVIDIA_GEMMA_MODEL, "Gemma 4 31B IT (NIM) 🔬", AiProviderType.NVIDIA_NIM, "Multilingual science, coding & analytical verification", "128k tokens", true, true, false),
        AiModelInfo(ModelCatalog.OPENAI_DEFAULT_MODEL, "GPT-6.2 Sol 🧠", AiProviderType.OPENAI, "OpenAI frontier multimodal flagship intelligence", "256k tokens", true, true, true),
        AiModelInfo(ModelCatalog.OPENAI_REASONING_MODEL, "GPT-6.2 Astra 🔬", AiProviderType.OPENAI, "Deep STEM, formal verification & reasoning model", "256k tokens", false, true, false),
        AiModelInfo(ModelCatalog.OPENAI_MINI_MODEL, "GPT-6 Luna ⚡", AiProviderType.OPENAI, "Ultra-fast daily conversational & coding model", "128k tokens", true, false, false),
        AiModelInfo(ModelCatalog.ANTHROPIC_SONNET_MODEL, "Claude Sonnet 5.5 🎭", AiProviderType.ANTHROPIC, "Hybrid reasoning & software architecture flagship", "200k tokens", true, true, true),
        AiModelInfo(ModelCatalog.ANTHROPIC_OPUS_MODEL, "Claude Opus 5.5 👑", AiProviderType.ANTHROPIC, "Highest-capability Claude frontier synthesis & engineering", "200k tokens", true, true, false),
        AiModelInfo(ModelCatalog.ANTHROPIC_HAIKU_MODEL, "Claude Haiku 5.5 ⚡", AiProviderType.ANTHROPIC, "Fast coding, analysis & concise synthesis", "200k tokens", true, false, false),
        AiModelInfo(ModelCatalog.DEEPSEEK_CHAT_MODEL, "DeepSeek Flash 🚀", AiProviderType.DEEPSEEK, "Fast conversational & full-stack coding intelligence", "128k tokens", false, false, false),
        AiModelInfo(ModelCatalog.DEEPSEEK_REASONER_MODEL, "DeepSeek V4 Pro 🔬", AiProviderType.DEEPSEEK, "Deep mathematical proof, formal code & step-by-step CoT", "128k tokens", false, true, true),
        AiModelInfo(ModelCatalog.XAI_DEFAULT_MODEL, "Grok 4.7 🤖", AiProviderType.XAI, "Frontier reasoning & real-time analysis by xAI", "256k tokens", true, true, true),
        AiModelInfo(ModelCatalog.MISTRAL_LARGE_MODEL, "Mistral Large 🌐", AiProviderType.MISTRAL, "European multilingual intelligence", "128k tokens", false, false, false),
        AiModelInfo(ModelCatalog.MISTRAL_CODESTRAL_MODEL, "Codestral 💻", AiProviderType.MISTRAL, "Dedicated programming & refactoring", "256k tokens", false, false, false),
        AiModelInfo(ModelCatalog.PERPLEXITY_REASONING_MODEL, "Perplexity Sonar Pro 🔎", AiProviderType.PERPLEXITY, "Web-grounded reasoning research with citations", "128k tokens", false, true, true),
        AiModelInfo(ModelCatalog.OPENROUTER_DEFAULT_MODEL, "OpenRouter Free Router 🔀", AiProviderType.OPENROUTER, "Dynamic zero-cost open-weights router", "128k tokens", true, false, true),
        AiModelInfo(ModelCatalog.OPENROUTER_QWEN_FREE_MODEL, "Qwen 3.8 27B Free 🐉", AiProviderType.OPENROUTER, "Multilingual STEM & reasoning on OpenRouter free tier", "128k tokens", false, true, false),
        AiModelInfo(ModelCatalog.OPENROUTER_CODER_MODEL, "Qwen3 Coder Free 💻", AiProviderType.OPENROUTER, "Coding and bug-fixing specialist", "128k tokens", false, false, false),
        AiModelInfo(ModelCatalog.OPENROUTER_AUTO_MODEL, "OpenRouter Auto ⚡", AiProviderType.OPENROUTER, "Adaptive frontier & open-weights gateway", "128k tokens", true, false, false),
        AiModelInfo(ModelCatalog.OLLAMA_QWEN_MODEL, "Local Ollama Qwen 2.5 14B 🌐", AiProviderType.LOCAL_OLLAMA, "Local/LAN open-weights Qwen model", "32k tokens", false, true, false),
        AiModelInfo(ModelCatalog.OLLAMA_LLAMA_MODEL, "Local Ollama / LM Studio 🏠", AiProviderType.LOCAL_OLLAMA, "Zero-cloud local LLM on your LAN", "32k tokens", false, false, true),
        AiModelInfo(ModelCatalog.POLLINATIONS_OPENAI_MODEL, "Global Free AI Pool (GPT/OSS) 🌐", AiProviderType.POLLINATIONS_FREE, "Pollinations cloud AI fallback (optional API key at enter.pollinations.ai)", "32k tokens", false, false, false),
        AiModelInfo(ModelCatalog.POLLINATIONS_LARGE_MODEL, "Global Free Flagship (Large) ⚡", AiProviderType.POLLINATIONS_FREE, "High-reasoning Pollinations ensemble channel (optional API key at enter.pollinations.ai)", "64k tokens", false, true, false),
        AiModelInfo(ModelCatalog.POLLINATIONS_CODER_MODEL, "Global Free Qwen Coder 💻", AiProviderType.POLLINATIONS_FREE, "Pollinations coding & architecture specialist (optional API key at enter.pollinations.ai)", "32k tokens", false, false, false),
        AiModelInfo(ModelCatalog.POLLINATIONS_DEEPSEEK_MODEL, "Global Free DeepSeek Reasoning 🧠", AiProviderType.POLLINATIONS_FREE, "Pollinations STEM, math & logic channel (optional API key at enter.pollinations.ai)", "64k tokens", false, true, false)
    )

    fun resolveGeminiModel(model: String): String {
        val clean = model.trim().lowercase().removePrefix("models/")
        return when {
            clean.isBlank() -> ModelCatalog.GEMINI_FLASH_MODEL
            clean == ModelCatalog.GEMINI_FLASH_MODEL || clean == "gemini_flash" || clean == "gemini-flash" ||
                clean == "gemini-flash-2.0" || clean == "gemini-flash-latest" ||
                clean.startsWith("gemini-3.8") ||
                clean.matches(Regex("^gemini-(2\\.5|2\\.0)-flash$")) -> ModelCatalog.GEMINI_FLASH_MODEL
            clean == ModelCatalog.GEMINI_PRO_MODEL || clean == "gemini_pro" || clean == "gemini-pro" ||
                clean == "gemini-pro-1.5" || clean == "gemini-pro-latest" ||
                clean.startsWith("gemini-4-") ||
                clean.matches(Regex("^gemini-(2\\.5|1\\.5)-pro$")) -> ModelCatalog.GEMINI_PRO_MODEL
            clean == ModelCatalog.GEMINI_FAST_LITE_MODEL || clean == "gemini_flash_lite" || clean == "gemini-flash-lite" ||
                clean == "gemini_1_5_flash" ||
                clean.matches(Regex("^gemini-(2\\.5|2\\.0)-flash-lite$")) ||
                clean.matches(Regex("^gemini-1\\.5-flash$")) -> ModelCatalog.GEMINI_FAST_LITE_MODEL
            clean == ModelCatalog.GEMINI_FLASH_35_MODEL || clean == "gemini_3_5_flash" -> ModelCatalog.GEMINI_FLASH_35_MODEL
            else -> clean
        }
    }

    fun sanitizeModelOutput(raw: String): String {
        if (raw.isBlank()) return ""
        var cleaned = raw.replace(THINK_BLOCK_REGEX, "").trim()
        if (cleaned.isBlank()) {
            cleaned = raw.replace(Regex("</?think>", RegexOption.IGNORE_CASE), "").trim()
        }
        return cleaned
    }

    fun defaultModelForProvider(provider: AiProviderType): String = when (provider) {
        AiProviderType.GEMINI -> ModelCatalog.GEMINI_FLASH_MODEL
        AiProviderType.NVIDIA_NIM -> ModelCatalog.NVIDIA_LLAMA_MODEL
        AiProviderType.GROQ -> ModelCatalog.GROQ_QWEN_MODEL
        AiProviderType.CEREBRAS -> ModelCatalog.CEREBRAS_LLAMA_MODEL
        AiProviderType.SAMBANOVA -> ModelCatalog.SAMBANOVA_DEEPSEEK_MODEL
        AiProviderType.HUGGINGFACE -> ModelCatalog.HF_QWEN_MODEL
        AiProviderType.GITHUB_MODELS -> ModelCatalog.GITHUB_RETIRED_MODEL
        AiProviderType.OPENAI -> ModelCatalog.OPENAI_DEFAULT_MODEL
        AiProviderType.ANTHROPIC -> ModelCatalog.ANTHROPIC_SONNET_MODEL
        AiProviderType.DEEPSEEK -> ModelCatalog.DEEPSEEK_REASONER_MODEL
        AiProviderType.XAI -> ModelCatalog.XAI_DEFAULT_MODEL
        AiProviderType.MISTRAL -> ModelCatalog.MISTRAL_LARGE_MODEL
        AiProviderType.PERPLEXITY -> ModelCatalog.PERPLEXITY_REASONING_MODEL
        AiProviderType.OPENROUTER -> ModelCatalog.OPENROUTER_DEFAULT_MODEL
        AiProviderType.LOCAL_OLLAMA -> ModelCatalog.OLLAMA_LLAMA_MODEL
        AiProviderType.POLLINATIONS_FREE -> ModelCatalog.POLLINATIONS_OPENAI_MODEL
        AiProviderType.OMNI_ROUTE -> ModelCatalog.GEMINI_FLASH_MODEL
    }
}
