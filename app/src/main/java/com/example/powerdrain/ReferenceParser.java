package com.example.powerdrain;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 轻量参考文档解析器：无需 Apache POI，直接解析 .xlsx(zip+xml) 与 .csv。
 * 只取 B 列（名称）与 C 列（状态），跳过表头行。
 * 状态规则：On=默认开启；Off / - =默认关闭；其中 "-" 视为动作型(无开关)。
 */
public final class ReferenceParser {

    /** 解析出的一行 */
    public static class Row {
        public int order;
        public String name;
        public boolean expectedOn;
        public boolean toggle; // "-" -> false
        public String raw;
    }

    private ReferenceParser() {
    }

    /** 根据文件名后缀自动选择解析方式。 */
    public static List<Row> parse(String fileName, InputStream in) throws Exception {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
            return parseCsv(in);
        }
        return parseXlsx(in);
    }

    // ====================== CSV ======================

    private static List<Row> parseCsv(InputStream in) throws Exception {
        List<Row> out = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        boolean first = true;
        int order = 0;
        while ((line = br.readLine()) != null) {
            // 去 UTF-8 BOM
            if (first && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            List<String> cols = splitCsv(line);
            if (first) {
                first = false;
                // 跳过表头（含“设置项/名称”等）
                if (looksLikeHeader(cols)) {
                    continue;
                }
            }
            String name = colAt(cols, 1);   // B 列
            String state = colAt(cols, 2);  // C 列
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            out.add(makeRow(++order, name.trim(), state));
        }
        return out;
    }

    private static List<String> splitCsv(String line) {
        List<String> res = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if ((c == ',' || c == '\t') && !inQuote) {
                res.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        res.add(cur.toString());
        return res;
    }

    // ====================== XLSX ======================

    private static List<Row> parseXlsx(InputStream in) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if (name.equals("xl/sharedStrings.xml")
                    || name.startsWith("xl/worksheets/sheet")
                    || name.equals("xl/workbook.xml")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                entries.put(name, bos.toByteArray());
            }
            zis.closeEntry();
        }

        List<String> shared = new ArrayList<>();
        if (entries.containsKey("xl/sharedStrings.xml")) {
            shared = parseSharedStrings(entries.get("xl/sharedStrings.xml"));
        }

        // 选第一个工作表 xml
        byte[] sheetXml = null;
        if (entries.containsKey("xl/worksheets/sheet1.xml")) {
            sheetXml = entries.get("xl/worksheets/sheet1.xml");
        } else {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                if (en.getKey().startsWith("xl/worksheets/sheet")) {
                    sheetXml = en.getValue();
                    break;
                }
            }
        }
        if (sheetXml == null) {
            throw new IllegalStateException("xlsx 中找不到工作表");
        }
        return parseSheet(sheetXml, shared);
    }

    private static List<String> parseSharedStrings(byte[] data) throws Exception {
        List<String> list = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(data), "UTF-8");
        int ev = p.getEventType();
        StringBuilder cur = null;
        boolean inT = false;
        while (ev != XmlPullParser.END_DOCUMENT) {
            String tag = p.getName();
            if (ev == XmlPullParser.START_TAG) {
                if ("si".equals(tag)) {
                    cur = new StringBuilder();
                } else if ("t".equals(tag)) {
                    inT = true;
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (inT && cur != null) {
                    cur.append(p.getText());
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("t".equals(tag)) {
                    inT = false;
                } else if ("si".equals(tag)) {
                    list.add(cur == null ? "" : cur.toString());
                    cur = null;
                }
            }
            ev = p.next();
        }
        return list;
    }

    private static List<Row> parseSheet(byte[] data, List<String> shared) throws Exception {
        List<Row> out = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(data), "UTF-8");
        int ev = p.getEventType();

        int curRowNum = 0;
        Map<String, String> rowCells = new HashMap<>(); // 列字母 -> 文本值
        String cellRef = null;
        String cellType = null;
        boolean inV = false;
        boolean inIsT = false;
        StringBuilder val = null;
        boolean firstDataSeen = false;

        while (ev != XmlPullParser.END_DOCUMENT) {
            String tag = p.getName();
            if (ev == XmlPullParser.START_TAG) {
                if ("row".equals(tag)) {
                    rowCells.clear();
                    String r = p.getAttributeValue(null, "r");
                    curRowNum = r == null ? curRowNum + 1 : safeInt(r, curRowNum + 1);
                } else if ("c".equals(tag)) {
                    cellRef = p.getAttributeValue(null, "r");
                    cellType = p.getAttributeValue(null, "t");
                    val = new StringBuilder();
                } else if ("v".equals(tag)) {
                    inV = true;
                } else if ("t".equals(tag)) {
                    inIsT = true; // inlineStr 的 <is><t>
                }
            } else if (ev == XmlPullParser.TEXT) {
                if ((inV || inIsT) && val != null) {
                    val.append(p.getText());
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("v".equals(tag)) {
                    inV = false;
                } else if ("t".equals(tag)) {
                    inIsT = false;
                } else if ("c".equals(tag)) {
                    String text = val == null ? "" : val.toString();
                    if ("s".equals(cellType)) {
                        int idx = safeInt(text, -1);
                        text = (idx >= 0 && idx < shared.size()) ? shared.get(idx) : "";
                    }
                    if (cellRef != null) {
                        String col = colLetters(cellRef);
                        rowCells.put(col, text);
                    }
                    cellRef = null;
                    cellType = null;
                    val = null;
                } else if ("row".equals(tag)) {
                    String name = rowCells.get("B");
                    String state = rowCells.get("C");
                    if (name != null && !name.trim().isEmpty()) {
                        // 跳过表头行
                        if (!firstDataSeen && isHeader(name, state)) {
                            firstDataSeen = true;
                        } else {
                            firstDataSeen = true;
                            out.add(makeRow(out.size() + 1, name.trim(), state));
                        }
                    }
                }
            }
            ev = p.next();
        }
        return out;
    }

    // ====================== 公共辅助 ======================

    private static Row makeRow(int order, String name, String state) {
        Row row = new Row();
        row.order = order;
        row.name = name;
        row.raw = state == null ? "" : state.trim();
        String s = row.raw.toLowerCase();
        row.toggle = !(s.equals("-") || s.isEmpty());
        row.expectedOn = s.equals("on") || s.equals("开") || s.equals("开启") || s.equals("true") || s.equals("1");
        return row;
    }

    private static boolean isHeader(String name, String state) {
        String n = name == null ? "" : name.trim();
        return n.equals("设置项") || n.equals("名称") || n.equalsIgnoreCase("name")
                || n.equals("功能") || (state != null && (state.trim().equals("默认状态") || state.trim().equalsIgnoreCase("status")));
    }

    private static boolean looksLikeHeader(List<String> cols) {
        String b = colAt(cols, 1);
        String c = colAt(cols, 2);
        return isHeader(b, c);
    }

    private static String colAt(List<String> cols, int idx) {
        return idx < cols.size() ? cols.get(idx) : null;
    }

    /** 从单元格引用(如 "B12")取列字母部分。 */
    private static String colLetters(String ref) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(Character.toUpperCase(c));
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static int safeInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
