package com.example.powerdrain;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 截图对比页面：
 * 上传通控中心截图 -> ML Kit 中文 OCR 识别控件名称 -> 按从上到下(网格按行)阅读顺序排列
 * -> 对每个控件采样图标区域颜色判断蓝色高亮(开/关) -> 与内置参考清单对比顺序与开关状态。
 */
public class ComparisonActivity extends AppCompatActivity {

    /** 参考清单项 */
    private static class RefItem {
        int order;
        String name;
        boolean expectedOn;  // 期望默认状态
        boolean toggle;      // 是否为开关型(false=动作型，无开关状态)
    }

    /** 截图识别出的一个控件 */
    private static class Detected {
        String text;            // OCR 原始文字
        Rect box;
        boolean on;             // 检测到的开/关
        double highlightRatio;  // 高亮(饱和)像素占比，用于展示与校准
        int matchedRefIndex = -1;
    }

    private final List<RefItem> reference = new ArrayList<>();

    private ImageView ivPreview;
    private TextView tvResult;
    private TextView tvSensitivity;
    private TextView tvRefStatus;
    private SeekBar sbSensitivity;
    private Button btnPick;
    private Button btnPickDoc;

    private Bitmap currentBitmap;

    // 持久化保存上传文档的文件名(内部存储)
    private static final String SAVED_DOC = "reference_doc.bin";
    private static final String SAVED_DOC_NAME = "reference_doc_name.txt";

    // 高亮判定灵敏度：饱和高亮像素占比阈值(百分比)。值越小越容易判为“开”。
    private int sensitivityPercent = 8;

    private ActivityResultLauncher<PickVisualMediaRequest> picker;
    private ActivityResultLauncher<String[]> docPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comparison);

        ivPreview = findViewById(R.id.ivPreview);
        tvResult = findViewById(R.id.tvResult);
        tvSensitivity = findViewById(R.id.tvSensitivity);
        tvRefStatus = findViewById(R.id.tvRefStatus);
        sbSensitivity = findViewById(R.id.sbSensitivity);
        btnPick = findViewById(R.id.btnPick);
        btnPickDoc = findViewById(R.id.btnPickDoc);

        loadReference();

        sbSensitivity.setMax(40);
        sbSensitivity.setProgress(sensitivityPercent);
        tvSensitivity.setText(getString(R.string.cmp_sensitivity, sensitivityPercent));
        sbSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                sensitivityPercent = Math.max(1, p);
                tvSensitivity.setText(getString(R.string.cmp_sensitivity, sensitivityPercent));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (currentBitmap != null) {
                    analyze(currentBitmap); // 重新用新灵敏度分析
                }
            }
        });

        picker = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) {
                        loadAndAnalyze(uri);
                    }
                });

        docPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        importReferenceDoc(uri);
                    }
                });

        btnPick.setOnClickListener(v -> picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));

        btnPickDoc.setOnClickListener(v -> docPicker.launch(new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "text/csv",
                "text/comma-separated-values",
                "text/plain",
                "*/*"
        }));
    }

    // ====================== 参考清单 ======================

    /** 优先加载用户上传并持久化的文档，否则回退到内置 assets 清单。 */
    private void loadReference() {
        java.io.File saved = new java.io.File(getFilesDir(), SAVED_DOC);
        if (saved.exists()) {
            String name = readSavedDocName();
            try (InputStream is = new java.io.FileInputStream(saved)) {
                List<ReferenceParser.Row> rows = ReferenceParser.parse(name, is);
                if (!rows.isEmpty()) {
                    applyRows(rows);
                    setRefStatus(getString(R.string.cmp_ref_loaded_doc, name, reference.size()), false);
                    return;
                }
            } catch (Exception e) {
                // 解析失败则回退到内置清单
            }
        }
        loadBuiltinReference();
        setRefStatus(getString(R.string.cmp_ref_builtin, reference.size()), false);
    }

    private void loadBuiltinReference() {
        reference.clear();
        try (InputStream is = getAssets().open("control_center_reference.json")) {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.getJSONArray("items");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                RefItem r = new RefItem();
                r.order = o.getInt("order");
                r.name = o.getString("name");
                r.expectedOn = "on".equalsIgnoreCase(o.optString("expected", "off"));
                r.toggle = o.optBoolean("toggle", true);
                reference.add(r);
            }
        } catch (Exception e) {
            Toast.makeText(this, "参考清单加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyRows(List<ReferenceParser.Row> rows) {
        reference.clear();
        for (ReferenceParser.Row row : rows) {
            RefItem r = new RefItem();
            r.order = row.order;
            r.name = row.name;
            r.expectedOn = row.expectedOn;
            r.toggle = row.toggle;
            reference.add(r);
        }
    }

    /** 导入用户选择的参考文档：解析 -> 校验 -> 持久化 -> 应用。 */
    private void importReferenceDoc(Uri uri) {
        String name = queryDisplayName(uri);
        try {
            // 先读到内存（同时用于解析与保存）
            byte[] data;
            try (InputStream is = getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while (is != null && (n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                data = bos.toByteArray();
            }
            List<ReferenceParser.Row> rows = ReferenceParser.parse(name,
                    new java.io.ByteArrayInputStream(data));
            if (rows.isEmpty()) {
                Toast.makeText(this, R.string.cmp_doc_empty, Toast.LENGTH_LONG).show();
                return;
            }
            // 持久化
            try (java.io.FileOutputStream fos =
                         new java.io.FileOutputStream(new java.io.File(getFilesDir(), SAVED_DOC))) {
                fos.write(data);
            }
            saveDocName(name);
            applyRows(rows);
            setRefStatus(getString(R.string.cmp_ref_loaded_doc, name, reference.size()), true);
            Toast.makeText(this, getString(R.string.cmp_doc_imported, reference.size()),
                    Toast.LENGTH_LONG).show();
            // 若已加载截图，自动用新文档重新比对
            if (currentBitmap != null) {
                analyze(currentBitmap);
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.cmp_doc_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void setRefStatus(String text, boolean highlight) {
        tvRefStatus.setText(text);
        tvRefStatus.setTextColor(highlight ? 0xFF2E7D32 : 0xFF666666);
    }

    private String queryDisplayName(Uri uri) {
        String name = null;
        try (android.database.Cursor c =
                     getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return name == null ? "reference.xlsx" : name;
    }

    private void saveDocName(String name) {
        try (java.io.FileOutputStream fos =
                     new java.io.FileOutputStream(new java.io.File(getFilesDir(), SAVED_DOC_NAME))) {
            fos.write(name.getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private String readSavedDocName() {
        java.io.File f = new java.io.File(getFilesDir(), SAVED_DOC_NAME);
        if (!f.exists()) {
            return "reference.xlsx";
        }
        try (InputStream is = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "reference.xlsx";
        }
    }

    // ====================== 图片加载 ======================

    private void loadAndAnalyze(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, bounds);
            int maxDim = 2200; // 限制最大边，兼顾清晰度与内存
            int sample = 1;
            int longer = Math.max(bounds.outWidth, bounds.outHeight);
            while (longer / sample > maxDim) {
                sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                Bitmap bmp = BitmapFactory.decodeStream(is2, null, opt);
                if (bmp == null) {
                    Toast.makeText(this, "图片解码失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                currentBitmap = bmp;
                ivPreview.setImageBitmap(bmp);
                analyze(bmp);
            }
        } catch (Exception e) {
            Toast.makeText(this, "读取图片失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ====================== OCR + 分析 ======================

    private void analyze(Bitmap bmp) {
        tvResult.setText(R.string.cmp_recognizing);
        InputImage image = InputImage.fromBitmap(bmp, 0);
        TextRecognizer recognizer =
                TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    List<Detected> detections = collectDetections(result, bmp);
                    String report = buildReport(detections);
                    tvResult.setText(Html.fromHtml(report));
                })
                .addOnFailureListener(e ->
                        tvResult.setText(getString(R.string.cmp_ocr_failed, e.getMessage())));
    }

    /**
     * 收集识别行 -> 过滤掉顶部区域(WLAN/蓝牙/调节条) -> 只保留圆形图标网格 ->
     * 按从上到下、行内从左到右排序 -> 检测每个图标开关状态。
     * 返回的列表包含网格内的全部图标(含未匹配文档的)，供后续序列对齐。
     */
    private List<Detected> collectDetections(Text result, Bitmap bmp) {
        List<Detected> raw = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line lineObj : block.getLines()) {
                Rect box = lineObj.getBoundingBox();
                if (box == null) {
                    continue;
                }
                String txt = normalize(lineObj.getText());
                if (txt.isEmpty()) {
                    continue;
                }
                Detected d = new Detected();
                d.text = txt;
                d.box = box;
                raw.add(d);
            }
        }
        if (raw.isEmpty()) {
            return new ArrayList<>();
        }

        // 1) 按 Y 聚类成行
        List<List<Detected>> rows = clusterRows(raw);

        // 2) 定位圆形图标网格的起始行：
        //    第一行满足 (a)>=3 列 且 (b)至少有一项能匹配上文档名 的行。
        //    - 状态栏(19:09/日期)：不含文档名 -> 排除
        //    - WLAN/蓝牙大方块/调节条：每行 <3 列 -> 排除
        //    - “视频通话助手”折行出的单独“手”等碎片不会影响起点判断
        //    从起始行一直取到底部（不依赖连续段，避免折行碎片截断网格）。
        int gridStart = -1;
        for (int i = 0; i < rows.size(); i++) {
            List<Detected> row = rows.get(i);
            if (row.size() < 3) {
                continue;
            }
            boolean hasDocMatch = false;
            for (Detected d : row) {
                if (matchReference(d.text) >= 0) {
                    hasDocMatch = true;
                    break;
                }
            }
            if (hasDocMatch) {
                gridStart = i;
                break;
            }
        }

        List<Detected> grid = new ArrayList<>();
        if (gridStart < 0) {
            // 没有明显网格则全保留，避免误杀
            for (List<Detected> r : rows) {
                grid.addAll(r);
            }
        } else {
            for (int i = gridStart; i < rows.size(); i++) {
                grid.addAll(rows.get(i));
            }
        }

        // 4) 阅读顺序排序
        sortReadingOrder(grid);

        // 5) 匹配文档名称（用于对齐与开关比对，未匹配的保留 matchedRefIndex=-1）
        for (Detected d : grid) {
            d.matchedRefIndex = matchReference(d.text);
        }

        // 5b) 过滤：去掉 OCR 折行碎片与重复匹配，避免误判“多出/缺失”
        List<Detected> cleaned = new ArrayList<>();
        boolean[] usedRef = new boolean[reference.size()];
        for (Detected d : grid) {
            if (d.matchedRefIndex >= 0) {
                if (usedRef[d.matchedRefIndex]) {
                    continue; // 同一文档项重复识别，保留第一个
                }
                usedRef[d.matchedRefIndex] = true;
                cleaned.add(d);
            } else if (!isWrapFragment(d.text)) {
                cleaned.add(d); // 真正不在文档中的多出项
            }
            // 折行碎片(某文档名的子串)直接丢弃
        }
        grid = cleaned;

        // 6) 检测每个图标开关状态
        for (Detected d : grid) {
            detectState(d, bmp);
        }
        return grid;
    }

    /**
     * 判断未匹配文本是否应作为碎片丢弃：
     * - 长度 <3 的未匹配短词（多为顶部状态栏残留，如“已日”“已开启”被误读）
     * - 某文档名的子串（OCR 折行碎片，如“字幕”“手”）
     */
    private boolean isWrapFragment(String text) {
        String t = normalize(text);
        if (t.length() < 3) {
            return true;
        }
        for (RefItem r : reference) {
            String core = normalize(r.name).replace("Bar", "").replace("bar", "");
            if (!core.equals(t) && core.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 将识别行按 Y 聚类成行（每行内未排序）。 */
    private List<List<Detected>> clusterRows(List<Detected> list) {
        List<Detected> sorted = new ArrayList<>(list);
        List<Integer> heights = new ArrayList<>();
        for (Detected d : sorted) {
            heights.add(d.box.height());
        }
        Collections.sort(heights);
        int medianH = heights.isEmpty() ? 24 : heights.get(heights.size() / 2);
        int rowTol = Math.max(medianH, 20) * 2;

        Collections.sort(sorted, Comparator.comparingInt(a -> a.box.centerY()));
        List<List<Detected>> rows = new ArrayList<>();
        List<Detected> cur = new ArrayList<>();
        int rowBaseY = sorted.get(0).box.centerY();
        for (Detected d : sorted) {
            if (Math.abs(d.box.centerY() - rowBaseY) > rowTol && !cur.isEmpty()) {
                rows.add(cur);
                cur = new ArrayList<>();
            }
            if (cur.isEmpty()) {
                rowBaseY = d.box.centerY();
            }
            cur.add(d);
        }
        if (!cur.isEmpty()) {
            rows.add(cur);
        }
        return rows;
    }

    /** 按网格阅读顺序排序：行聚类(Y) + 行内 X 升序。 */
    private void sortReadingOrder(List<Detected> list) {
        if (list.isEmpty()) {
            return;
        }
        // 估算行高：取所有 box 高度的中位数
        List<Integer> heights = new ArrayList<>();
        for (Detected d : list) {
            heights.add(d.box.height());
        }
        Collections.sort(heights);
        int medianH = heights.get(heights.size() / 2);
        int rowTol = Math.max(medianH, 20) * 2; // 同一行的 Y 容差

        // 先按 Y 排
        Collections.sort(list, Comparator.comparingInt(a -> a.box.centerY()));
        // 行内按 X 排：遍历，遇到 Y 跨度超过容差则视为新行
        List<List<Detected>> rows = new ArrayList<>();
        List<Detected> cur = new ArrayList<>();
        int rowBaseY = list.get(0).box.centerY();
        for (Detected d : list) {
            if (Math.abs(d.box.centerY() - rowBaseY) > rowTol && !cur.isEmpty()) {
                rows.add(cur);
                cur = new ArrayList<>();
                rowBaseY = d.box.centerY();
            }
            cur.add(d);
        }
        if (!cur.isEmpty()) {
            rows.add(cur);
        }
        list.clear();
        for (List<Detected> row : rows) {
            Collections.sort(row, Comparator.comparingInt(a -> a.box.left));
            list.addAll(row);
        }
    }

    /**
     * 采样圆形图标区域，统计“饱和高亮”像素占比判断是否点亮(开)。
     * 点亮态图标通常为蓝色/黄色等高饱和色；关闭态为灰白描边(低饱和)。
     */
    private void detectState(Detected d, Bitmap bmp) {
        Rect b = d.box;
        int h = b.height();
        // 图标在文字标签正上方。用标签高度估算图标圆心与半径。
        int cx = b.centerX();
        int radius = (int) (h * 1.15);              // 采样圆半径
        int cy = b.top - (int) (h * 1.35);          // 图标圆心(标签上方)
        int left = Math.max(0, cx - radius);
        int right = Math.min(bmp.getWidth() - 1, cx + radius);
        int top = Math.max(0, cy - radius);
        int bottom = Math.min(bmp.getHeight() - 1, cy + radius);
        if (bottom <= top || right <= left) {
            d.on = false;
            d.highlightRatio = 0;
            return;
        }
        int stepX = Math.max(1, (right - left) / 28);
        int stepY = Math.max(1, (bottom - top) / 28);
        int sampled = 0;
        int highlight = 0;
        float[] hsv = new float[3];
        for (int y = top; y <= bottom; y += stepY) {
            for (int x = left; x <= right; x += stepX) {
                // 圆形掩膜，避开图标外的面板背景
                int dx = x - cx;
                int dy = y - cy;
                if (dx * dx + dy * dy > radius * radius) {
                    continue;
                }
                int c = bmp.getPixel(x, y);
                Color.colorToHSV(c, hsv);
                float s = hsv[1];
                float v = hsv[2];
                sampled++;
                // 高亮(非默认色)：饱和度足够高且不太暗。
                // 灰/白描边 S 很低 -> 视为关；蓝/黄/绿等彩色 S 高 -> 视为开。
                if (s >= 0.30f && v >= 0.35f) {
                    highlight++;
                }
            }
        }
        d.highlightRatio = sampled == 0 ? 0 : (double) highlight / sampled;
        d.on = d.highlightRatio * 100.0 >= sensitivityPercent;
    }

    // ====================== 名称匹配 ======================

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        // 全角括号转半角，再只保留 中文/字母/数字/括号，
        // 借此清除空格、三角(◢▲▼)、箭头、斜杠等 UI 标记符号(修复“阅读模式◢”等)。
        String t = s.replace("（", "(").replace("）", ")");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= '\u4e00' && c <= '\u9fa5')
                    || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '(' || c == ')') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 返回匹配的参考项下标，未匹配返回 -1。 */
    private int matchReference(String ocrText) {
        String t = normalize(ocrText);
        if (t.isEmpty()) {
            return -1;
        }
        // 1) 完全相等
        for (int i = 0; i < reference.size(); i++) {
            if (normalize(reference.get(i).name).equalsIgnoreCase(t)) {
                return i;
            }
        }
        // 2) 互相包含（处理“声音调节Bar”识别成“声音调节”等情况）
        for (int i = 0; i < reference.size(); i++) {
            String ref = normalize(reference.get(i).name);
            String refCore = ref.replace("Bar", "").replace("bar", "");
            if (t.length() >= 2 && (ref.contains(t) || t.contains(refCore) || refCore.contains(t))) {
                return i;
            }
        }
        // 3) 模糊匹配：容忍 OCR 单字误读（如“超级互联”被读成“起级互联”）。
        //    仅对长度>=3 的词做，按编辑距离相似度，长度差<=1 且相似度>=0.7。
        if (t.length() >= 3) {
            int best = -1;
            double bestSim = 0;
            for (int i = 0; i < reference.size(); i++) {
                String ref = normalize(reference.get(i).name).replace("Bar", "").replace("bar", "");
                if (Math.abs(ref.length() - t.length()) > 1) {
                    continue;
                }
                int dist = levenshtein(t, ref);
                double sim = 1.0 - (double) dist / Math.max(t.length(), ref.length());
                if (sim >= 0.7 && sim > bestSim) {
                    bestSim = sim;
                    best = i;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }

    /** 编辑距离(Levenshtein)。 */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    // ====================== 报告生成 ======================

    private String buildReport(List<Detected> detections) {
        if (detections.isEmpty()) {
            return getString(R.string.cmp_none_recognized);
        }

        // 截图侧名称序列（匹配到文档的用文档标准名，未匹配用 OCR 原文）
        List<String> screenNames = new ArrayList<>();
        for (Detected d : detections) {
            String nm = d.matchedRefIndex >= 0
                    ? normalize(reference.get(d.matchedRefIndex).name)
                    : normalize(d.text);
            screenNames.add(nm);
        }

        // 锚点：截图中第一个能匹配文档的图标，确定文档对比起点
        int refStart = -1;
        for (Detected d : detections) {
            if (d.matchedRefIndex >= 0) {
                refStart = d.matchedRefIndex;
                break;
            }
        }
        if (refStart < 0) {
            return "<font color='#c62828'><b>✗ 未能将截图中任何圆形图标与文档对应上。</b></font><br>"
                    + "请确认上传的截图是通控中心面板，且文字清晰。";
        }

        // 文档侧名称序列（从锚点行往下）
        List<String> refNames = new ArrayList<>();
        List<Integer> refRealIdx = new ArrayList<>();
        for (int i = refStart; i < reference.size(); i++) {
            refNames.add(normalize(reference.get(i).name));
            refRealIdx.add(i);
        }

        // LCS 序列对齐
        int n = screenNames.size();
        int m = refNames.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (screenNames.get(i).equals(refNames.get(j))) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        // 0=匹配, 1=截图多出, 2=文档缺失
        List<int[]> ops = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (screenNames.get(i).equals(refNames.get(j))) {
                ops.add(new int[]{0, i, j});
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new int[]{1, i, -1});
                i++;
            } else {
                ops.add(new int[]{2, -1, j});
                j++;
            }
        }
        while (i < n) {
            ops.add(new int[]{1, i++, -1});
        }
        while (j < m) {
            ops.add(new int[]{2, -1, j++});
        }

        // 归类：截图多出 / 文档缺失；两者交集视为“顺序错位”
        java.util.LinkedHashSet<String> screenOnly = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> refOnly = new java.util.LinkedHashSet<>();
        for (int[] op : ops) {
            if (op[0] == 1) {
                screenOnly.add(screenNames.get(op[1]));
            } else if (op[0] == 2) {
                refOnly.add(refNames.get(op[2]));
            }
        }
        java.util.LinkedHashSet<String> orderMismatch = new java.util.LinkedHashSet<>(screenOnly);
        orderMismatch.retainAll(refOnly);
        screenOnly.removeAll(orderMismatch);
        refOnly.removeAll(orderMismatch);

        int problems = 0;
        StringBuilder sb = new StringBuilder();

        sb.append("<b>识别到 ").append(detections.size()).append(" 个圆形图标</b>")
                .append("，对比起点：<b>").append(reference.get(refStart).name)
                .append("</b>（文档 No.").append(reference.get(refStart).order).append("）<br>");

        // ---- 5.1 表格有、测试机没有（缺失）----
        sb.append("<br><b>【1. 表格有、测试机没有】</b><br>");
        if (refOnly.isEmpty()) {
            sb.append("<font color='#2e7d32'>✔ 无</font><br>");
        } else {
            problems += refOnly.size();
            for (String nm : refOnly) {
                sb.append("<font color='#c62828'>&nbsp;&nbsp;✗ ").append(nm).append("</font><br>");
            }
        }

        // ---- 5.2 测试机有、表格没有（多出）----
        sb.append("<br><b>【2. 测试机有、表格没有】</b><br>");
        if (screenOnly.isEmpty()) {
            sb.append("<font color='#2e7d32'>✔ 无</font><br>");
        } else {
            problems += screenOnly.size();
            for (String nm : screenOnly) {
                sb.append("<font color='#ef6c00'>&nbsp;&nbsp;⚠ ").append(nm).append("</font><br>");
            }
        }

        // ---- 5.3 顺序不匹配 ----
        sb.append("<br><b>【3. 顺序不匹配】</b><br>");
        if (orderMismatch.isEmpty()) {
            sb.append("<font color='#2e7d32'>✔ 无</font><br>");
        } else {
            problems += orderMismatch.size();
            for (String nm : orderMismatch) {
                sb.append("<font color='#c62828'>&nbsp;&nbsp;✗ ").append(nm)
                        .append("（位置与表格不符）</font><br>");
            }
        }

        // ---- 5.4 开关状态不符（仅对已对应上的项）----
        sb.append("<br><b>【4. 开关状态不符】</b>（彩色高亮=开，灰色=关）<br>");
        int swProblems = 0;
        for (int[] op : ops) {
            if (op[0] != 0) {
                continue;
            }
            Detected d = detections.get(op[1]);
            RefItem r = reference.get(refRealIdx.get(op[2]));
            if (d.on != r.expectedOn) {
                swProblems++;
                problems++;
                String detectedTxt = d.on ? "开" : "关";
                String expectTxt = r.expectedOn ? "开" : "关";
                String pct = String.format(java.util.Locale.US, "%.0f%%", d.highlightRatio * 100);
                sb.append("<font color='#c62828'>&nbsp;&nbsp;✗ ").append(r.name)
                        .append("：测试机=").append(detectedTxt)
                        .append("，表格=").append(expectTxt)
                        .append(" <font color='#888'>(").append(pct).append(")</font></font><br>");
            }
        }
        if (swProblems == 0) {
            sb.append("<font color='#2e7d32'>✔ 无</font><br>");
        }

        // ---- 总结 ----
        String summary;
        if (problems == 0) {
            summary = "<font color='#2e7d32'><b>✔ 全部一致，无异常</b></font><br><br>";
        } else {
            summary = "<font color='#c62828'><b>✗ 共发现 " + problems
                    + " 处异常</b></font><br><br>";
        }
        return summary + sb;
    }
}
