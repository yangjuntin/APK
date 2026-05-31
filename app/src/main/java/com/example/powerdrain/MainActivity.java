package com.example.powerdrain;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 安卓平板耗电测试主界面。
 * 一键开启多种耗电负载（CPU 满载 / 屏幕最高亮度 / 闪光灯 / 持续震动）加速耗电，
 * 并实时监控电量，当电量降到用户设定的阈值时自动暂停。
 */
public class MainActivity extends AppCompatActivity {

    // 运行状态
    private volatile boolean draining = false;

    // CPU 满载线程
    private final List<Thread> cpuThreads = new ArrayList<>();

    // 其它耗电设备
    private CameraManager cameraManager;
    private String flashCameraId;
    private boolean torchOn = false;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;

    // 电量监控
    private int stopThreshold = 20;   // 低于该电量百分比自动停止
    private int startBatteryLevel = -1;
    private long startTimeMs = 0;
    private BroadcastReceiver batteryReceiver;

    // 界面控件
    private TextView tvBattery;
    private TextView tvStatus;
    private TextView tvThreshold;
    private TextView tvCpu;
    private SeekBar sbThreshold;
    private SeekBar sbCpu;
    private CheckBox cbCpu;
    private CheckBox cbScreen;
    private CheckBox cbFlash;
    private CheckBox cbVibrate;
    private Button btnStart;
    private Button btnStop;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 保持屏幕常亮（测试期间屏幕本身也是耗电大户）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerDrainTest:wl");

        bindViews();
        setupListeners();
        detectFlash();
        registerBatteryReceiver();
        updateButtons();
    }

    private void bindViews() {
        tvBattery = findViewById(R.id.tvBattery);
        tvStatus = findViewById(R.id.tvStatus);
        tvThreshold = findViewById(R.id.tvThreshold);
        tvCpu = findViewById(R.id.tvCpu);
        sbThreshold = findViewById(R.id.sbThreshold);
        sbCpu = findViewById(R.id.sbCpu);
        cbCpu = findViewById(R.id.cbCpu);
        cbScreen = findViewById(R.id.cbScreen);
        cbFlash = findViewById(R.id.cbFlash);
        cbVibrate = findViewById(R.id.cbVibrate);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        sbThreshold.setProgress(stopThreshold);
        tvThreshold.setText(getString(R.string.threshold_label, stopThreshold));

        int cores = Runtime.getRuntime().availableProcessors();
        sbCpu.setMax(cores * 2);
        sbCpu.setProgress(cores);
        tvCpu.setText(getString(R.string.cpu_label, cores));
    }

    private void setupListeners() {
        sbThreshold.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                stopThreshold = progress;
                tvThreshold.setText(getString(R.string.threshold_label, progress));
            }
        });

        sbCpu.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvCpu.setText(getString(R.string.cpu_label, progress));
            }
        });

        btnStart.setOnClickListener(v -> startDrain());
        btnStop.setOnClickListener(v -> stopDrain(true));
    }

    private void detectFlash() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean hasFlash = cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    flashCameraId = id;
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (flashCameraId == null) {
            cbFlash.setEnabled(false);
            cbFlash.setChecked(false);
            cbFlash.setText(R.string.flash_unavailable);
        }
    }

    // ====================== 耗电控制 ======================

    private void startDrain() {
        if (draining) {
            return;
        }
        if (!cbCpu.isChecked() && !cbScreen.isChecked()
                && !cbFlash.isChecked() && !cbVibrate.isChecked()) {
            Toast.makeText(this, R.string.no_load_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        // 已经低于阈值则不启动
        int level = readBatteryLevel();
        if (level >= 0 && level <= stopThreshold) {
            Toast.makeText(this, R.string.already_below, Toast.LENGTH_LONG).show();
            return;
        }

        draining = true;
        startBatteryLevel = level;
        startTimeMs = System.currentTimeMillis();

        if (!wakeLock.isHeld()) {
            wakeLock.acquire(6 * 60 * 60 * 1000L /* 最长 6 小时 */);
        }

        if (cbCpu.isChecked()) {
            startCpuLoad(Math.max(1, sbCpu.getProgress()));
        }
        if (cbScreen.isChecked()) {
            setScreenBrightness(1.0f);
        }
        if (cbFlash.isChecked() && flashCameraId != null) {
            setTorch(true);
        }
        if (cbVibrate.isChecked()) {
            startVibration();
        }

        updateButtons();
        tvStatus.setText(R.string.status_running);
    }

    private void stopDrain(boolean userTriggered) {
        if (!draining) {
            return;
        }
        draining = false;

        stopCpuLoad();
        setScreenBrightness(-1f); // 恢复系统亮度
        setTorch(false);
        stopVibration();

        if (wakeLock.isHeld()) {
            wakeLock.release();
        }

        updateButtons();
        tvStatus.setText(userTriggered ? R.string.status_stopped_user : R.string.status_stopped_auto);
    }

    private void startCpuLoad(int threadCount) {
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                double x = 1.0001;
                while (draining) {
                    x = Math.sqrt(x * Math.PI) + Math.tan(x) + Math.log(Math.abs(x) + 1.0);
                    if (Double.isNaN(x) || Double.isInfinite(x) || x > 1e12) {
                        x = 1.0001;
                    }
                }
            });
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
            cpuThreads.add(t);
        }
    }

    private void stopCpuLoad() {
        // draining 置 false 后线程会自行退出
        for (Thread t : cpuThreads) {
            t.interrupt();
        }
        cpuThreads.clear();
    }

    private void setScreenBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value; // 1.0=最亮, -1=跟随系统
        getWindow().setAttributes(lp);
    }

    private void setTorch(boolean on) {
        if (flashCameraId == null) {
            return;
        }
        try {
            cameraManager.setTorchMode(flashCameraId, on);
            torchOn = on;
        } catch (Exception ignored) {
        }
    }

    private void startVibration() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        long[] pattern = {0, 1000, 200}; // 震 1s 停 0.2s
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    private void stopVibration() {
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    // ====================== 电量监控 ======================

    private void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onBatteryChanged(intent);
            }
        };
        Intent sticky = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            onBatteryChanged(sticky);
        }
    }

    private int readBatteryLevel() {
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) {
            return -1;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) {
            return -1;
        }
        return Math.round(level * 100f / scale);
    }

    private void onBatteryChanged(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;

        int tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        float temp = tempRaw / 10f; // 单位 0.1°C
        int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        String drainInfo = "";
        if (draining && startBatteryLevel >= 0 && pct >= 0) {
            int dropped = startBatteryLevel - pct;
            long minutes = (System.currentTimeMillis() - startTimeMs) / 60000;
            drainInfo = "\n" + getString(R.string.drain_info, dropped, minutes);
        }

        tvBattery.setText(getString(R.string.battery_info,
                pct, temp, voltage / 1000f, charging ? getString(R.string.charging) : getString(R.string.discharging))
                + drainInfo);

        // 达到阈值自动暂停
        if (draining && pct >= 0 && pct <= stopThreshold) {
            uiHandler.post(() -> {
                stopDrain(false);
                Toast.makeText(MainActivity.this,
                        getString(R.string.auto_stopped, stopThreshold), Toast.LENGTH_LONG).show();
            });
        }
    }

    // ====================== 界面状态 ======================

    private void updateButtons() {
        btnStart.setEnabled(!draining);
        btnStop.setEnabled(draining);
        cbCpu.setEnabled(!draining);
        cbScreen.setEnabled(!draining);
        cbFlash.setEnabled(!draining && flashCameraId != null);
        cbVibrate.setEnabled(!draining);
        sbThreshold.setEnabled(!draining);
        sbCpu.setEnabled(!draining);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDrain(true);
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {
            }
        }
    }

    /** 只关心进度变化的简化监听器 */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
