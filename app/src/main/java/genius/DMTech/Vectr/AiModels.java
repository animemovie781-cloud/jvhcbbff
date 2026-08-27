package genius.DMTech.Vectr;

/**
 * Каталог моделей для всех провайдеров.
 * Индексы сохраняются в SharedPreferences (model_index).
 */
public final class AiModels {

    private AiModels() {}

    public static final class ModelOption {
        public final String id;
        public final String title;
        public final String subtitle;
        public final String badge;
        public final boolean large;
        public final boolean legacy;
        public final boolean custom;
        public final int suggestedMaxTokens;

        public ModelOption(String id, String title, String subtitle, String badge,
                           boolean large, boolean legacy, boolean custom, int suggestedMaxTokens) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.badge = badge;
            this.large = large;
            this.legacy = legacy;
            this.custom = custom;
            this.suggestedMaxTokens = suggestedMaxTokens;
        }
    }

    public static final ModelOption[] DEEPSEEK = {
            new ModelOption("deepseek-v4-flash", "V4 Flash",
                    "fast · 1M context · tool calls", "SPEED",
                    false, false, false, 8192),
            new ModelOption("deepseek-v4-pro", "V4 Pro",
                    "1.6T MoE · frontier coding · agents", "LARGE",
                    true, false, false, 32768),
            new ModelOption("deepseek-chat", "Chat (legacy)",
                    "alias → V4 Flash · retires 24.07.2026", "DEPRECATED",
                    false, true, false, 8192),
            new ModelOption("deepseek-reasoner", "Reasoner (legacy)",
                    "alias → V4 Flash thinking · retires 24.07.2026", "DEPRECATED",
                    false, true, false, 8192),
            new ModelOption("", "Custom model",
                    "any API model id", "CUSTOM",
                    false, false, true, 8192),
    };

    public static final ModelOption[] OLLAMA = {
            new ModelOption("llama3.2", "Llama 3.2",
                    "local · 128K context · tools", "LOCAL",
                    false, false, false, 8192),
            new ModelOption("llama3.1", "Llama 3.1",
                    "local · 128K context · tools", "LOCAL",
                    false, false, false, 8192),
            new ModelOption("qwen2.5-coder", "Qwen 2.5 Coder",
                    "local · coding specialist · 128K", "LOCAL",
                    false, false, false, 32768),
            new ModelOption("mistral", "Mistral",
                    "local · 32K context", "LOCAL",
                    false, false, false, 8192),
            new ModelOption("codellama", "Code Llama",
                    "local · code specialist · 16K", "LOCAL",
                    false, false, false, 8192),
            new ModelOption("", "Custom Ollama model",
                    "any model pulled in Ollama", "CUSTOM",
                    false, false, true, 8192),
    };

    public static final ModelOption[] OPENROUTER = {
            new ModelOption("openai/gpt-4o", "GPT-4o",
                    "OpenAI · 128K · vision · tools", "TOP",
                    true, false, false, 16384),
            new ModelOption("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet",
                    "Anthropic · 200K · best coding", "TOP",
                    true, false, false, 16384),
            new ModelOption("google/gemini-2.0-flash-001", "Gemini 2.0 Flash",
                    "Google · 1M · speed · tools", "SPEED",
                    false, false, false, 8192),
            new ModelOption("meta-llama/llama-3.1-405b-instruct", "Llama 3.1 405B",
                    "Meta · 128K · open weights", "LARGE",
                    true, false, false, 32768),
            new ModelOption("mistralai/mistral-large", "Mistral Large",
                    "Mistral · 128K · multilingual", "TOP",
                    true, false, false, 32768),
            new ModelOption("", "Custom OpenRouter model",
                    "any model from openrouter.ai/models", "CUSTOM",
                    false, false, true, 8192),
    };

    public static final String[] PROVIDERS = {
            "DeepSeek",
            "Ollama",
            "OpenRouter",
            "OpenAI-compatible"
    };

    public static final String[] PROVIDER_IDS = {
            "deepseek",
            "ollama",
            "openrouter",
            "openai_compat"
    };

    public static final String[] DEFAULT_BASE_URLS = {
            "https://api.deepseek.com",
            "http://localhost:11434/v1",
            "https://openrouter.ai/api/v1",
            "https://api.openai.com/v1"
    };

    public static final int[] TOKEN_PRESETS = {
            4096, 8192, 16384, 32768, 65536, 128000, 384000
    };

    public static ModelOption byIndex(int providerIndex, int modelIndex) {
        ModelOption[] models = getModelsForProvider(providerIndex);
        if (modelIndex < 0 || modelIndex >= models.length) return models[0];
        return models[modelIndex];
    }

    public static ModelOption[] getModelsForProvider(int providerIndex) {
        switch (providerIndex) {
            case 1: return OLLAMA;
            case 2: return OPENROUTER;
            default: return DEEPSEEK;
        }
    }

    public static String getDefaultBaseUrl(int providerIndex) {
        if (providerIndex >= 0 && providerIndex < DEFAULT_BASE_URLS.length) {
            return DEFAULT_BASE_URLS[providerIndex];
        }
        return DEFAULT_BASE_URLS[0];
    }

    public static String getProviderId(int providerIndex) {
        if (providerIndex >= 0 && providerIndex < PROVIDER_IDS.length) {
            return PROVIDER_IDS[providerIndex];
        }
        return PROVIDER_IDS[0];
    }

    public static String titleForSpinner(int providerIndex, int modelIndex) {
        ModelOption m = byIndex(providerIndex, modelIndex);
        if (m.custom) return m.title;
        String providerName = PROVIDERS[providerIndex];
        return providerName + " · " + m.title + " · " + m.id;
    }

    public static int getModelCount(int providerIndex) {
        return getModelsForProvider(providerIndex).length;
    }
}
