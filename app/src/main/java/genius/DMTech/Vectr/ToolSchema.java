package genius.DMTech.Vectr;

import org.json.JSONArray;
import org.json.JSONObject;

public class ToolSchema {

    public static JSONArray build() {
        JSONArray tools = new JSONArray();
        try {
            tools.put(makeTool("read_file",
                    "ОБЯЗАТЕЛЬНО используй для чтения любого файла проекта. " +
                            "Прочитать содержимое файла по относительному пути от корня проекта.",
                    new String[]{"path"},
                    new String[]{"Относительный путь к файлу, например java/AiConfig.java"}));

            tools.put(makeTool("write_file",
                    "Создать или ПОЛНОСТЬЮ перезаписать файл. Для правок существующих файлов " +
                            "предпочитай search_replace или apply_patch — меньше шансов стереть код.",
                    new String[]{"path", "content"},
                    new String[]{"Относительный путь к файлу от корня проекта", "Полное новое содержимое файла"}));

            tools.put(makeTool("search_replace",
                    "Точечная правка файла: заменить old_string на new_string. " +
                            "old_string должен быть уникален в файле (или поставь replace_all=true). " +
                            "Предпочтительный инструмент для правок существующего кода.",
                    new String[]{"path", "old_string", "new_string"},
                    new String[]{
                            "Относительный путь к файлу",
                            "Точный фрагмент текста, который нужно заменить (включая отступы)",
                            "На что заменить"
                    }));
            // optional replace_all flag via extra property
            JSONObject sr = tools.getJSONObject(tools.length() - 1);
            JSONObject srFn = sr.getJSONObject("function");
            JSONObject srParams = srFn.getJSONObject("parameters");
            JSONObject srProps = srParams.getJSONObject("properties");
            JSONObject replaceAll = new JSONObject();
            replaceAll.put("type", "string");
            replaceAll.put("description", "true — заменить все вхождения; иначе только одно уникальное");
            srProps.put("replace_all", replaceAll);

            tools.put(makeTool("apply_patch",
                    "Применить один или несколько блоков SEARCH/REPLACE к файлу. Формат патча:\n" +
                            "<<<<<<< SEARCH\nстарый фрагмент\n=======\nновый фрагмент\n>>>>>>> REPLACE\n" +
                            "Можно несколько блоков подряд. Предпочтительно для связанных правок в одном файле.",
                    new String[]{"path", "patch"},
                    new String[]{
                            "Относительный путь к файлу",
                            "Патч в формате SEARCH/REPLACE блоков"
                    }));

            tools.put(makeToolOptional("list_files",
                    "ОБЯЗАТЕЛЬНО используй первым шагом, чтобы увидеть структуру проекта. " +
                            "Список файлов и папок в директории.",
                    new String[]{"path"},
                    new String[]{"Относительный путь к папке, '.' для корня проекта"}));

            if (AiConfig.isWebSearchEnabled(null)) {
                tools.put(makeTool("web_search",
                        "Поиск в интернете (актуальные факты, docs, ошибки API, новости, Stack Overflow). " +
                                "Вызывай, когда нужно уточнить что-то ВНЕ проекта: документацию библиотеки, " +
                                "версию API, решение ошибки, внешний пример. НЕ для чтения локальных файлов.",
                        new String[]{"query"},
                        new String[]{"Поисковый запрос на языке пользователя или английском"}));
                JSONObject ws = tools.getJSONObject(tools.length() - 1);
                JSONObject wsProps = ws.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties");
                JSONObject maxR = new JSONObject();
                maxR.put("type", "string");
                maxR.put("description", "Сколько результатов вернуть (1–10), по умолчанию 5");
                wsProps.put("max_results", maxR);

                tools.put(makeTool("fetch_url",
                        "Скачать и прочитать текст веб-страницы по URL (после web_search). " +
                                "Используй для деталей по конкретной ссылке из результатов поиска.",
                        new String[]{"url"},
                        new String[]{"Полный URL https://…"}));
            }

            tools.put(makeTool("run_command",
                    "Выполнить shell-команду. Юзер увидит команду и выберет способ выполнения: " +
                            "Termux (полноценное Linux-окружение: python, gcc, git и т.д.) " +
                            "или встроенный Shell (только базовые команды Android). " +
                            "ИСПОЛЬЗУЙ для: запуска python-скриптов, компиляции, установки пакетов, " +
                            "работы с git, curl-запросов, и любых других операций в командной строке. " +
                            "НЕ используй для чтения/записи файлов проекта — для этого есть read_file/write_file.",
                    new String[]{"command", "description"},
                    new String[]{
                            "Команда для выполнения (bash-синтаксис)",
                            "Краткое описание что команда делает — показывается юзеру перед запуском"
                    }));
        } catch (Exception e) {
            // если json собрался криво - лучше пустой массив, чем краш на ровном месте
        }
        return tools;
    }

    private static JSONObject makeTool(String name, String description, String[] paramNames, String[] paramDescriptions) throws Exception {
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();

        for (int i = 0; i < paramNames.length; i++) {
            JSONObject prop = new JSONObject();
            prop.put("type", "string");
            prop.put("description", paramDescriptions[i]);
            properties.put(paramNames[i], prop);
            required.put(paramNames[i]);
        }

        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);

        JSONObject function = new JSONObject();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    // версия без required — все параметры опциональные
    private static JSONObject makeToolOptional(String name, String description, String[] paramNames, String[] paramDescriptions) throws Exception {
        JSONObject properties = new JSONObject();

        for (int i = 0; i < paramNames.length; i++) {
            JSONObject prop = new JSONObject();
            prop.put("type", "string");
            prop.put("description", paramDescriptions[i]);
            properties.put(paramNames[i], prop);
        }

        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        // не добавляем required — все параметры опциональны

        JSONObject function = new JSONObject();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }
}
