package com.gosgf.app;

import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.gosgf.app.util.RecognitionSettings;

/**
 * 识别参数设置页。
 *
 * 这里的每一项都对应 MokuRecognizer 里原本写死的常量,现已全部抽到
 * {@link RecognitionSettings},识别时按本页保存的值执行(无写死配置)。
 * 长按主界面"摆子"按钮可进入本页。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText edMaxDeviation;
    private EditText edOverflowMargin;
    private EditText edBoundaryMin;
    private EditText edBoundaryMax;
    private EditText edBoundaryBase;
    private EditText edBoundaryK;
    private EditText edStrongBoundaryFactor;
    private EditText edMinPlaced;
    private EditText edAbsBlackLum;
    private EditText edAbsWhiteLum;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final RecognitionSettings s = RecognitionSettings.load(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("识别参数设置");
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("所有识别均以下方设置为准(无写死配置)。\n"
                + "调整后立即生效,识别时自动读取。\n"
                + "不确定请保持默认,或点\"恢复默认\"。");
        tip.setTextSize(13);
        tip.setPadding(0, 0, 0, dp(12));
        root.addView(tip);

        edMaxDeviation = addField(root, "棋子偏离交叉点最大偏差 (maxDeviation)",
                String.valueOf(s.maxDeviation),
                "棋子偏离交叉点超过该值(单位:格)会被判为误检丢弃。\n"
                + "调大 → 救回因四角/单应矩阵不准而整盘偏移的真子(常表现为最底/最右行\"少一行\");\n"
                + "调小 → 更严格过滤误检。默认 " + RecognitionSettings.DEF_MAX_DEVIATION + "。");

        edOverflowMargin = addField(root, "整盘溢出判定余量 (overflowMargin)",
                String.valueOf(s.overflowMargin),
                "棋子归一化坐标超出 [0-余量, 1+余量] 即认为四角把棋盘画大/画偏,\n"
                + "触发降级到\"棋子 bbox 拟合网格\"。\n"
                + "调小 → 更易触发降级(四角不准时更早修正);调大 → 更宽容。默认 "
                + RecognitionSettings.DEF_OVERFLOW_MARGIN + "。");

        edBoundaryMin = addField(root, "像素判定阈值下限 (boundaryMin)",
                String.valueOf(s.boundaryMin),
                "黑/白判定的最小亮度差门限。\n"
                + "调大 → 更保守(宁漏不误);调小 → 更易判有子。默认 "
                + RecognitionSettings.DEF_BOUNDARY_MIN + "。");

        edBoundaryMax = addField(root, "像素判定阈值上限 (boundaryMax)",
                String.valueOf(s.boundaryMax),
                "黑/白判定的最大亮度差门限(防止低噪声场景过于敏感)。默认 "
                + RecognitionSettings.DEF_BOUNDARY_MAX + "。");

        edBoundaryBase = addField(root, "像素判定基准阈值 (boundaryBase)",
                String.valueOf(s.boundaryBase),
                "自适应阈值与 2.5×背景标准差 取较大者时的基准下限。默认 12。");

        edBoundaryK = addField(root, "背景标准差放大系数 (boundaryK)",
                String.valueOf(s.boundaryK),
                "阈值 = max(boundaryBase, boundaryK × 背景标准差)。\n"
                + "背景木纹噪声大时调大更稳。默认 2.5。");

        edStrongBoundaryFactor = addField(root, "强信号倍数 (strongBoundaryFactor)",
                String.valueOf(s.strongBoundaryFactor),
                "逐点复核/补盲时,\"确定有子\"需 |亮度差| ≥ 阈值 × 该倍数(或落入绝对兜底)。\n"
                + "调大 → 更保守、误补更少但可能漏;调小 → 更易判有子。默认 "
                + RecognitionSettings.DEF_STRONG_BOUNDARY_FACTOR + "。");

        edMinPlaced = addField(root, "确认真棋盘最少棋子数 (minPlaced)",
                String.valueOf(s.minPlaced),
                "已识别棋子数达到该值才启用\"逐点复核/孤立补盲\",\n"
                + "避免空盘/接近空盘误补。默认 " + RecognitionSettings.DEF_MIN_PLACED + "。");

        edAbsBlackLum = addField(root, "绝对黑兜底亮度 (absBlackLum)",
                String.valueOf(s.absBlackLum),
                "棋子亮度低于该值直接判黑(极端光照兜底)。默认 50。");

        edAbsWhiteLum = addField(root, "绝对白兜底亮度 (absWhiteLum)",
                String.valueOf(s.absWhiteLum),
                "棋子亮度高于该值直接判白(极端光照兜底)。默认 "
                + RecognitionSettings.DEF_ABS_WHITE_LUM + "。");

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(16), 0, 0);

        Button btnReset = new Button(this);
        btnReset.setText("恢复默认");
        btnReset.setOnClickListener(v -> {
            fillFrom(new RecognitionSettings());
            Toast.makeText(this, "已填入默认值(需点\"保存\"生效)", Toast.LENGTH_SHORT).show();
        });

        Button btnSave = new Button(this);
        btnSave.setText("保存");
        btnSave.setOnClickListener(v -> {
            RecognitionSettings ns = readFields();
            if (ns == null) return;
            ns.save(this);
            Toast.makeText(this, "已保存,识别将按新参数执行", Toast.LENGTH_SHORT).show();
            finish();
        });

        btnRow.addView(btnReset, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(8), 0, 0, 0);
        btnRow.addView(btnSave, lp);

        root.addView(btnRow);
        setContentView(scroll);
    }

    /** 添加一行"标签 + 说明 + 输入框"。 */
    private EditText addField(LinearLayout root, String label, String value, String reason) {
        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(15);
        lbl.setPadding(0, dp(10), 0, 0);
        root.addView(lbl);

        TextView desc = new TextView(this);
        desc.setText(reason);
        desc.setTextSize(12);
        desc.setTextColor(0xFF888888);
        desc.setPadding(0, dp(2), 0, dp(4));
        root.addView(desc);

        EditText ed = new EditText(this);
        ed.setText(value);
        ed.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        ed.setTextSize(16);
        root.addView(ed, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        return ed;
    }

    private void fillFrom(RecognitionSettings s) {
        edMaxDeviation.setText(String.valueOf(s.maxDeviation));
        edOverflowMargin.setText(String.valueOf(s.overflowMargin));
        edBoundaryMin.setText(String.valueOf(s.boundaryMin));
        edBoundaryMax.setText(String.valueOf(s.boundaryMax));
        edBoundaryBase.setText(String.valueOf(s.boundaryBase));
        edBoundaryK.setText(String.valueOf(s.boundaryK));
        edStrongBoundaryFactor.setText(String.valueOf(s.strongBoundaryFactor));
        edMinPlaced.setText(String.valueOf(s.minPlaced));
        edAbsBlackLum.setText(String.valueOf(s.absBlackLum));
        edAbsWhiteLum.setText(String.valueOf(s.absWhiteLum));
    }

    /** 读取输入框,校验合法性;非法返回 null 并提示。 */
    private RecognitionSettings readFields() {
        try {
            RecognitionSettings s = new RecognitionSettings();
            s.maxDeviation = parseFloat(edMaxDeviation, s.maxDeviation);
            s.overflowMargin = parseFloat(edOverflowMargin, s.overflowMargin);
            s.boundaryMin = parseFloat(edBoundaryMin, s.boundaryMin);
            s.boundaryMax = parseFloat(edBoundaryMax, s.boundaryMax);
            s.boundaryBase = parseFloat(edBoundaryBase, s.boundaryBase);
            s.boundaryK = parseFloat(edBoundaryK, s.boundaryK);
            s.strongBoundaryFactor = parseFloat(edStrongBoundaryFactor, s.strongBoundaryFactor);
            s.minPlaced = parseInt(edMinPlaced, s.minPlaced);
            s.absBlackLum = parseInt(edAbsBlackLum, s.absBlackLum);
            s.absWhiteLum = parseInt(edAbsWhiteLum, s.absWhiteLum);
            return s;
        } catch (NumberFormatException e) {
            Toast.makeText(this, "存在非法数值,请检查输入", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private float parseFloat(EditText ed, float def) {
        String t = ed.getText().toString().trim();
        if (t.isEmpty()) return def;
        return Float.parseFloat(t);
    }

    private int parseInt(EditText ed, int def) {
        String t = ed.getText().toString().trim();
        if (t.isEmpty()) return def;
        return Integer.parseInt(t);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
