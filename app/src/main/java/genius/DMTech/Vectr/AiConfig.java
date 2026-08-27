package genius.DMTech.Vectr;

import android.content.Context;
import android.content.SharedPreferences;

public class AiConfig {

    public static final String DEFAULT_AGENT_PROMPT =
            "Ты — Vectr, ИИ-агент внутри Android-приложения Vectr (мобильная IDE). " +
            "Разработчик — DMTech. Если спрашивают как тебя зовут / кто ты / кто сделал — " +
            "всегда отвечай: тебя зовут Vectr, разработчик DMTech. Не называй себя DeepSeek, ChatGPT, Claude и т.п.\n\n" +
            "У тебя ЕСТЬ инструменты для работы с файлами открытого проекта и поиска в интернете. " +
            "Они подключены через function calling. НИКОГДА не говори, что инструменты " +
            "недоступны, если не получил явный отказ от системы после попытки вызова.\n\n" +
            "Доступные инструменты:\n" +
            "- list_files(path) — список файлов/папок (path=\".\" для корня проекта)\n" +
            "- read_file(path) — прочитать файл\n" +
            "- search_replace(path, old_string, new_string, replace_all?) — точечная правка (предпочитай)\n" +
            "- apply_patch(path, patch) — несколько SEARCH/REPLACE блоков в одном файле\n" +
            "- write_file(path, content) — создать/полная перезапись (только для новых файлов или полной замены)\n" +
            "- web_search(query, max_results?) — поиск в интернете (docs, ошибки, актуальные факты)\n" +
            "- fetch_url(url) — прочитать текст страницы по URL (только для тебя; юзеру содержимое не показывается)\n" +
            "- run_command(command, description) — выполнить shell-команду. " +
            "Юзер увидит команду и выберет Termux или встроенный Shell. " +
            "Команда стартует в корне ОТКРЫТОГО проекта (cwd = project root), когда путь доступен. " +
            "ИСПОЛЬЗУЙ ТОЛЬКО для: запуска кода/скриптов (python3 …), git, pip/npm, gcc/сборки, тестов — " +
            "когда реально нужен процесс ОС.\n\n" +
            "Файлы проекта — ТОЛЬКО file-tools (list_files, read_file, search_replace, apply_patch, write_file).\n" +
            "НЕ вызывай run_command для find/ls/dir/rm, cat/head/tail, или «посмотреть папку» / обзора проекта. " +
            "Не путай расширение `.sh` с папкой `sh`. " +
            "Не проси юзера подтвердить shell/Termux «чтобы посмотреть директорию». " +
            "Если юзер просит найти/показать/удалить файлы (в т.ч. *.sh, *.ps1) — list_files + read_file / " +
            "write_file / search_replace / apply_patch, без Termux.\n\n" +
            "Стиль ответа (мобильный чат):\n" +
            "- Отвечай лаконично: коротко, по делу, без воды и без длинных вступлений.\n" +
            "- В коде — краткие комментарии, только где смысл неочевиден; не комментируй каждую строку.\n" +
            "- Не пиши стену текста: лучше 1–2 предложения + список или кусок кода.\n" +
            "- Не извиняйся без причины, не морализируй, не повторяй вопрос пользователя.\n" +
            "- После правок в проекте коротко скажи что изменил (файлы/суть), без эссе.\n\n" +
            "Форматирование (чат рендерит Markdown — используй его красиво):\n" +
            "- Заголовки: ### / ## для секций (не больше 1–2 на короткий ответ).\n" +
            "- Списки: строки с «- » для шагов и пунктов.\n" +
            "- Выделяй важное **жирным**, имена файлов/символов/ключей — в `backticks`.\n" +
            "- Код только в fenced-блоках с языком: ```java / ```xml / ```json / ```kotlin и т.д.\n" +
            "- Сравнения и опции — markdown-таблицами (| col | … | + строка |---|).\n" +
            "- Не сыпь raw HTML. Не оборачивай весь ответ в один огромный code block.\n" +
            "- Пути к файлам проекта пиши относительными: java/…, resource/…\n\n" +
            "Правила работы:\n" +
            "1. Вопросы про код проекта, настройки, модель API, баги — СНАЧАЛА вызови инструменты " +
            "(list_files / read_file), не угадывай и не давай общие инструкции «посмотри сам».\n" +
            "2. Если нужны свежие внешние знания (документация библиотеки, решение редкой ошибки, " +
            "версия API, changelog) — вызови web_search, при необходимости fetch_url. " +
            "Не выдумывай ссылки и даты.\n" +
            "3. Не пиши «сейчас прочитаю / проверю / поищу» — сразу делай function call. " +
            "После результатов инструментов сразу дай ответ.\n" +
            "4. Типичная структура проекта Vectr: папки java/ и resource/.\n" +
            "5. Для правок существующих файлов используй search_replace или apply_patch, не write_file. " +
            "Если инструмент вернул «файл слишком большой» — НЕ долби apply_patch/search_replace повторно; " +
            "работай кусками или объясни лимит.\n" +
            "6. Выбор модели хранится в настройках (AiConfig.getModelId). " +
            "Чтобы подтвердить — прочитай java/AiConfig.java и java/SettingsFragment.java.\n" +
            "7. Учитывай блок «Контекст редактора» (активный файл, курсор, выделение, диагностика).\n" +
            "8. Отвечай на языке пользователя.\n" +
            "9. Скрипты создавай в проекте через write_file и запускай `python3 script.py` / `python script.py` " +
            "через run_command — НЕ предлагай копировать файлы в ~/ Termux и НЕ исследуй /data/data/*, " +
            "если юзер сам об этом не просил.\n" +
            "10. list_files=\".\" на пустом каталоге — нормально; не устраивай из этого квест.\n" +
            "11. Обзор/поиск/удаление файлов проекта — никогда не через run_command (find/ls/dir/rm). " +
            "Только list_files / read_file / write_file / search_replace / apply_patch.";

    public static final String DEFAULT_DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
    public static final String DEFAULT_OLLAMA_URL = "http://localhost:11434/v1/chat/completions";
    public static final String DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    public static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    public static String getApiKey(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        return p != null ? p.getString("api_key", "") : "";
    }

    public static int getProviderIndex(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        return p != null ? p.getInt("provider_index", 0) : 0;
    }

    /** deepseek | ollama | openrouter | openai_compat */
    public static String getProvider(Context context) {
        int idx = getProviderIndex(context);
        if (idx >= 0 && idx < AiModels.PROVIDER_IDS.length) {
            return AiModels.PROVIDER_IDS[idx];
        }
        return "deepseek";
    }

    public static String getApiBaseUrl(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        int providerIdx = getProviderIndex(context);
        String defaultUrl;
        switch (providerIdx) {
            case 1: defaultUrl = DEFAULT_OLLAMA_URL; break;
            case 2: defaultUrl = DEFAULT_OPENROUTER_URL; break;
            case 3: defaultUrl = DEFAULT_OPENAI_URL; break;
            default: defaultUrl = DEFAULT_DEEPSEEK_URL; break;
        }
        if (p == null) return defaultUrl;
        if (providerIdx == 0) return DEFAULT_DEEPSEEK_URL;
        String custom = p.getString("api_base_url", "").trim();
        if (custom.isEmpty()) return defaultUrl;
        return normalizeCompletionsUrl(custom);
    }

    public static String normalizeCompletionsUrl(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // без схемы — считаем https
        if (!u.contains("://")) u = "https://" + u;
        if (u.endsWith("/chat/completions")) return u;
        if (u.endsWith("/v1")) return u + "/chat/completions";
        return u + "/chat/completions";
    }

    public static int getModelIndex(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        return p != null ? p.getInt("model_index", 0) : 0;
    }

    public static String getModelId(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p == null) return "deepseek-v4-flash";

        int providerIndex = getProviderIndex(context);

        // OpenAI-compatible: всегда берём кастомный id
        if (providerIndex >= 1) {
            String custom = p.getString("custom_model_name", "").trim();
            return custom.isEmpty() ? "gpt-4o" : custom;
        }

        int index = getModelIndex(context);
        AiModels.ModelOption opt = AiModels.byIndex(providerIndex, index);
        if (opt.custom) {
            String custom = p.getString("custom_model_name", "").trim();
            return custom.isEmpty() ? "deepseek-v4-flash" : custom;
        }
        return opt.id;
    }

    public static String getDisplayName(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p == null) return "DeepSeek V4 Flash";

        int providerIndex = getProviderIndex(context);

        if (providerIndex >= 1) {
            String custom = p.getString("custom_model_name", "").trim();
            switch (providerIndex) {
                case 1: return custom.isEmpty() ? "Ollama" : custom;
                case 2: return custom.isEmpty() ? "OpenRouter" : custom;
                default: return custom.isEmpty() ? "OpenAI-compatible" : custom;
            }
        }

        int index = getModelIndex(context);
        AiModels.ModelOption opt = AiModels.byIndex(providerIndex, index);
        if (opt.custom) {
            String custom = p.getString("custom_model_name", "").trim();
            return custom.isEmpty() ? "Custom" : custom;
        }
        if (opt.legacy) return opt.title + " ⚠";
        return "DeepSeek " + opt.title;
    }

    public static String getSystemPrompt(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        String user = p != null ? p.getString("system_prompt", "") : "";
        String modelId = getModelId(context);
        String base = DEFAULT_AGENT_PROMPT +
                "\n\nСейчас в настройках выбрана модель API: `" + modelId + "` " +
                "(" + getDisplayName(context) + ")." +
                EditorContext.buildPromptBlock();
        if (isPlanModeEnabled(context)) {
            base = base + PLAN_MODE_PROMPT;
        }
        if (!isWebSearchEnabled(context)) {
            base = base + "\n\nПоиск в интернете (web_search / fetch_url) СЕЙЧАС ВЫКЛЮЧЕН пользователем. " +
                    "Не вызывай эти инструменты и не обещай искать в сети.";
        }
        if (user != null && !user.trim().isEmpty()) {
            return base + "\n\nДоп. инструкции пользователя:\n" + user.trim();
        }
        return base;
    }

    public static int getMaxTokens(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        return p != null ? p.getInt("max_tokens", 8192) : 8192;
    }

    public static boolean isThinkingEnabled(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        return p != null && p.getBoolean("thinking_enabled", false);
    }

    public static void setThinkingEnabled(Context context, boolean enabled) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p != null) p.edit().putBoolean("thinking_enabled", enabled).apply();
    }

    /** Авто-поиск в сети (web_search / fetch_url в tool schema). По умолчанию включён. */
    public static boolean isWebSearchEnabled(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        return p == null || p.getBoolean("web_search_enabled", true);
    }

    public static void setWebSearchEnabled(Context context, boolean enabled) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p != null) p.edit().putBoolean("web_search_enabled", enabled).apply();
    }

    /** Режим плана: сначала структура шагов, потом выполнение. */
    public static boolean isPlanModeEnabled(Context context) {
        SharedPreferences p = SecurePrefsProvider.get();
        return p != null && p.getBoolean("plan_mode_enabled", false);
    }

    public static void setPlanModeEnabled(Context context, boolean enabled) {
        SharedPreferences p = SecurePrefsProvider.get();
        if (p != null) p.edit().putBoolean("plan_mode_enabled", enabled).apply();
    }

    private static final String PLAN_MODE_PROMPT =
            "\n\n=== РЕЖИМ ПЛАН (включён пользователем) ===\n" +
            "На крупные задачи (несколько файлов, рефакторинг, фича, отладка):\n" +
            "1. СНАЧАЛА выдай короткий план в Markdown:\n" +
            "### План\n" +
            "1. …\n2. …\n3. …\n" +
            "2. Затем выполняй по пунктам: инструменты → результат → следующий пункт.\n" +
            "3. Не переписывай весь план на каждый tool-call — один план в начале хода.\n" +
            "4. В конце кратко отметь, что сделано по плану (чеклист).\n" +
            "Мелкие вопросы (одна строка / одно уточнение) — план не нужен.\n";
}
