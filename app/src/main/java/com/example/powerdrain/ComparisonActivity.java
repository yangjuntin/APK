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
        String text;
        Rect box;
        boolean on;          // 检测到的开/关
        double blueRatio;    // 蓝色像素占比(用于展示与校准)
        int matchedRefIndex = -1;
    }

    private final List<RefItem> reference = new ArrayList<>();

    private ImageView ivPreview;
    private TextView tvResult;
    private TextView tvSensitivity;
    private SeekBar sbSensitivity;
    private Button btnPick;

    private Bitmap currentBitmap;

    // 蓝色高亮判定灵敏度：blueRatio 阈值(百分比)。值越小越容易判为“开”。
    private int sensitivityPercent = 12;

    private ActivityResultLauncher<PickVisualMediaRequest> picker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comparison);

        ivPreview = findViewById(R.id.ivPreview);
        tvResult = findViewById(R.id.tvResult);
        tvSensitivity = findViewById(R.id.tvSensitivity);
        sbSensitivity = findViewById(R.id.sbSensitivity);
        btnPick = findViewById(R.id.btnPick);

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

        btnPick.setOnClickListener(v -> picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
    }

    // ====================== 参考清单 ======================

    private void loadReference() {
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

    /** 收集识别行，匹配参考名称，并检测开关状态，按阅读顺序排列。 */
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
                d.matchedRefIndex = matchReference(txt);
                raw.add(d);
            }
        }

        // 只保留能匹配到参考清单的行（控件标签）
        List<Detected> matched = new ArrayList<>();
        for (Detected d : raw) {
            if (d.matchedRefIndex >= 0) {
                matched.add(d);
            }
        }

        // 阅读顺序：先按行(Y)聚类，再行内按 X 排序
        sortReadingOrder(matched);

        // 去重：同一参考项可能被多次识别，保留第一个
        List<Detected> deduped = new ArrayList<>();
        boolean[] used = new boolean[reference.size()];
        for (Detected d : matched) {
            if (!used[d.matchedRefIndex]) {
                used[d.matchedRefIndex] = true;
                deduped.add(d);
            }
        }

        // 检测每个控件的开关状态（采样图标高亮区域）
        for (Detected d : deduped) {
            detectState(d, bmp);
        }
        return deduped;
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

    /** 采样控件图标区域，统计蓝色像素占比判断是否高亮(开)。 */
    private void detectState(Detected d, Bitmap bmp) {
        Rect b = d.box;
        int h = b.height();
        int w = b.width();
        // 图标通常在文字标签上方，采样区域：以文字水平中心为中心、宽约文字宽、
        // 垂直方向取文字上方约 0.3h ~ 2.4h 的范围。
        int cx = b.centerX();
        int left = Math.max(0, cx - (int) (w * 0.7));
        int right = Math.min(bmp.getWidth() - 1, cx + (int) (w * 0.7));
        int top = Math.max(0, b.top - (int) (h * 2.4));
        int bottom = Math.max(0, b.top - (int) (h * 0.3));
        if (bottom <= top || right <= left) {
            d.on = false;
            d.blueRatio = 0;
            return;
        }
        int stepX = Math.max(1, (right - left) / 24);
        int stepY = Math.max(1, (bottom - top) / 24);
        int sampled = 0;
        int blue = 0;
        for (int y = top; y < bottom; y += stepY) {
            for (int x = left; x < right; x += stepX) {
                int c = bmp.getPixel(x, y);
                int r = Color.red(c);
                int g = Color.green(c);
                int bl = Color.blue(c);
                sampled++;
                // 蓝色高亮判定：蓝通道明显高于红/绿，且不太暗
                if (bl > 110 && bl > r + 30 && bl > g + 15) {
                    blue++;
                }
            }
        }
        d.blueRatio = sampled == 0 ? 0 : (double) blue / sampled;
        d.on = d.blueRatio * 100.0 >= sensitivityPercent;
    }

    // ====================== 名称匹配 ======================

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s\\u3000]+", "")
                .replace("（", "(").replace("）", ")")
                .trim();
    }

    /** 返回匹配的参考项下标，未匹配返回 -1。 */
    private int matchReference(String ocrText) {
        String t = normalize(ocrText);
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
        return -1;
    }

    // ====================== 报告生成 ======================

    private String buildReport(List<Detected> detections) {
        StringBuilder sb = new StringBuilder();
        int problems = 0;

        if (detections.isEmpty()) {
            return getString(R.string.cmp_none_recognized);
        }

        // ---- 顺序检查 ----
        // detections 已是截图阅读顺序，取其 matchedRefIndex 序列，检查是否相对参考递增
        List<Integer> seq = new ArrayList<>();
        for (Detected d : detections) {
            seq.add(d.matchedRefIndex);
        }
        List<Integer> orderProblems = new ArrayList<>();
        int prev = -1;
        for (int i = 0; i < seq.size(); i++) {
            if (seq.get(i) < prev) {
                orderProblems.add(i);
            } else {
                prev = seq.get(i);
            }
        }

        sb.append("<b>识别到 ").append(detections.size()).append(" 个控件</b><br>");
        sb.append("<br><b>【顺序检查】</b><br>");
        if (orderProblems.isEmpty()) {
            sb.append("<font color='#2e7d32'>✔ 顺序与参考清单一致</font><br>");
        } else {
            problems += orderProblems.size();
            sb.append("<font color='#c62828'>✗ 顺序不符，以下控件相对参考顺序错位：</font><br>");
            for (int idx : orderProblems) {
                Detected d = detections.get(idx);
                sb.append("&nbsp;&nbsp;• <b>").append(reference.get(d.matchedRefIndex).name)
                        .append("</b>（出现在第 ").append(idx + 1).append(" 位）<br>");
            }
        }

        // 实际顺序 vs 参考顺序对照
        sb.append("<br><b>【截图顺序 → 参考序号】</b><br>");
        for (int i = 0; i < detections.size(); i++) {
            Detected d = detections.get(i);
            RefItem r = reference.get(d.matchedRefIndex);
            sb.append(i + 1).append(". ").append(r.name)
                    .append(" <font color='#888'>(参考#").append(r.order).append(")</font><br>");
        }

        // ---- 开关状态检查 ----
        sb.append("<br><b>【开关状态检查】</b>（蓝色高亮=开）<br>");
        for (Detected d : detections) {
            RefItem r = reference.get(d.matchedRefIndex);
            String detectedTxt = d.on ? "开" : "关";
            String pct = String.format(java.util.Locale.US, "%.0f%%", d.blueRatio * 100);
            if (!r.toggle) {
                // 动作型：无固定开关，仅当检测到高亮时提示
                if (d.on) {
                    problems++;
                    sb.append("<font color='#ef6c00'>⚠ ").append(r.name)
                            .append("：动作型(参考为 -)，却检测到高亮(").append(pct).append(")</font><br>");
                } else {
                    sb.append("<font color='#888'>○ ").append(r.name)
                            .append("：动作型，未高亮(").append(pct).append(")</font><br>");
                }
                continue;
            }
            boolean ok = (d.on == r.expectedOn);
            String expectTxt = r.expectedOn ? "开" : "关";
            if (ok) {
                sb.append("<font color='#2e7d32'>✔ ").append(r.name)
                        .append("：实际=").append(detectedTxt)
                        .append("，参考=").append(expectTxt)
                        .append(" <font color='#888'>(").append(pct).append(")</font></font><br>");
            } else {
                problems++;
                sb.append("<font color='#c62828'>✗ ").append(r.name)
                        .append("：实际=").append(detectedTxt)
                        .append("，参考=").append(expectTxt)
                        .append(" <font color='#888'>(").append(pct).append(")</font></font><br>");
            }
        }

        // ---- 参考清单中未在截图识别到的开关型项 ----
        boolean[] seen = new boolean[reference.size()];
        for (Detected d : detections) {
            seen[d.matchedRefIndex] = true;
        }
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < reference.size(); i++) {
            if (!seen[i]) {
                missing.append("&nbsp;&nbsp;• ").append(reference.get(i).name).append("<br>");
            }
        }
        if (missing.length() > 0) {
            sb.append("<br><b>【参考中未识别到的项】</b><br>")
                    .append("<font color='#888'>(可能被截图裁掉、滑块无文字标签或 OCR 未识别)</font><br>")
                    .append(missing);
        }

        // ---- 总结 ----
        String summary;
        if (problems == 0) {
            summary = "<br><font color='#2e7d32'><b>✔ 全部一致，无异常</b></font><br>";
        } else {
            summary = "<br><font color='#c62828'><b>✗ 共发现 " + problems + " 处异常，请见上方红色/橙色标记</b></font><br>";
        }
        return summary + sb;
    }
}
