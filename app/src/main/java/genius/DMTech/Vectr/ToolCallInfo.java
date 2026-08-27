package genius.DMTech.Vectr;

public class ToolCallInfo {
    public String id;
    public String name;
    public String argumentsJson;
    public String result;
    public boolean done;

    // новое - только для write_file
    public int diffAdded = -1;
    public int diffRemoved = -1;
    public String targetFile;
    public String oldContent;  // не сохраняется в базу, только для текущей сессии
    public String newContent;
}