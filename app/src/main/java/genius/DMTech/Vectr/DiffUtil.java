package genius.DMTech.Vectr;

import java.util.ArrayList;
import java.util.List;

public class DiffUtil {

    public enum OpType { EQUAL, ADD, DEL }

    public static class DiffOp {
        public OpType type;
        public String text;
        public DiffOp(OpType type, String text) { this.type = type; this.text = text; }
    }

    public static class DiffResult {
        public List<DiffOp> ops = new ArrayList<>();
        public int added;
        public int removed;
    }

    // защита от O(n*m) взрыва на здоровенных файлах - помним про ANR из прошлого
    private static final int MAX_LINES_FOR_DETAILED_DIFF = 1500;

    public static DiffResult diffLines(String oldText, String newText) {
        String[] oldLines = oldText == null ? new String[0] : oldText.split("\n", -1);
        String[] newLines = newText == null ? new String[0] : newText.split("\n", -1);

        DiffResult result = new DiffResult();

        if (oldLines.length > MAX_LINES_FOR_DETAILED_DIFF || newLines.length > MAX_LINES_FOR_DETAILED_DIFF) {
            // файл слишком здоровый для построчного LCS - грубая сводка без красивого diff
            result.removed = oldLines.length;
            result.added = newLines.length;
            result.ops.add(new DiffOp(OpType.DEL, oldText));
            result.ops.add(new DiffOp(OpType.ADD, newText));
            return result;
        }

        int n = oldLines.length, m = newLines.length;
        int[][] dp = new int[n + 1][m + 1];

        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        int i = 0, j = 0;
        while (i < n && j < m) {
            if (oldLines[i].equals(newLines[j])) {
                result.ops.add(new DiffOp(OpType.EQUAL, oldLines[i]));
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                result.ops.add(new DiffOp(OpType.DEL, oldLines[i]));
                result.removed++;
                i++;
            } else {
                result.ops.add(new DiffOp(OpType.ADD, newLines[j]));
                result.added++;
                j++;
            }
        }
        while (i < n) { result.ops.add(new DiffOp(OpType.DEL, oldLines[i])); result.removed++; i++; }
        while (j < m) { result.ops.add(new DiffOp(OpType.ADD, newLines[j])); result.added++; j++; }

        return result;
    }
}