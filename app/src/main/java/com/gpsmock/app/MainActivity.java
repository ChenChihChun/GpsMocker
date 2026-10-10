package com.gpsmock.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import androidx.activity.result.ActivityResultLauncher;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final String TAG = "GpsMocker";
    private static final int PERMISSION_LOCATION = 1001;
    private static final String GITHUB_REPO = "ChenChihChun/GpsMocker";

    private GpsMockDbHelper dbHelper;
    private LocationManager locationManager;
    private Geocoder geocoder;

    private TextView currentLocText;
    private EditText startInput;
    private TextView startResultText;
    private EditText destInput;
    private CheckBox followRoadsCheck;
    private CheckBox fixedPointCheck;
    private Button startStopBtn;
    private LinearLayout presetsContainer;
    private TextView statusText;

    private Button tabSimBtn;
    private Button tabPotBtn;
    private View simTabView;
    private View potTabView;
    private LinearLayout potsContainer;
    private Button tabMushBtn;
    private View mushTabView;
    private LinearLayout mushContainer;
    private TextView homeText;

    private double currentLat = 0, currentLng = 0;
    private boolean hasLocation = false;

    private double startLat = 0, startLng = 0;
    private String startName = "";
    private boolean hasStartPoint = false;

    private LinearLayout waypointsContainer;
    private final java.util.List<Waypoint> waypoints = new java.util.ArrayList<>();

    private Handler uiHandler;
    private TextView mockStatusText;

    // 步數寫入
    private ActivityResultLauncher<Set<String>> healthPermLauncher;
    private double stepHours = 2.0;
    private int stepRate = 5500;
    private TextView stepEstimateText;
    private TextView stepStatusText;
    private Button stepWriteBtn;
    private final Button[] stepHourBtns = new Button[7];

    private final Runnable statusUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            String error = GpsMockService.getLastError();
            if (error != null) {
                GpsMockService.clearLastError();
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                showMockLocationSetupDialog();
            }

            if (GpsMockService.isRunning()) {
                double lat = GpsMockService.getCurrentLat();
                double lng = GpsMockService.getCurrentLng();
                int progress = (int) (GpsMockService.getProgress() * 100);
                if (GpsMockService.isFixedPoint()) {
                    mockStatusText.setText(String.format("定點模擬中: %.4f, %.4f", lat, lng));
                } else if (GpsMockService.isJumpMode()) {
                    int segCount = GpsMockService.getSegCount();
                    if (GpsMockService.hasArrived()) {
                        mockStatusText.setText(String.format("巡迴完成（%d 點）: %.4f, %.4f", segCount, lat, lng));
                    } else {
                        mockStatusText.setText(String.format("跳點巡迴 第%d/%d點: %.4f, %.4f%s",
                                GpsMockService.getSegIndex() + 1, segCount, lat, lng,
                                GpsMockService.isPaused() ? "（暫停中）" : ""));
                    }
                } else if (GpsMockService.hasArrived()) {
                    mockStatusText.setText(String.format("已到達: %.4f, %.4f", lat, lng));
                } else {
                    int segCount = GpsMockService.getSegCount();
                    if (segCount > 1) {
                        mockStatusText.setText(String.format("模擬中 第%d/%d段: %.4f, %.4f (%d%%)",
                                GpsMockService.getSegIndex() + 1, segCount, lat, lng, progress));
                    } else {
                        mockStatusText.setText(String.format("模擬位置: %.4f, %.4f (%d%%)", lat, lng, progress));
                    }
                }
                mockStatusText.setTextColor(UIHelper.ACCENT_GREEN);
                uiHandler.postDelayed(this, 1000);
            } else {
                mockStatusText.setText("");
            }
            updateButtonState();
        }
    };

    private static final long[] DURATION_VALUES = {
            10 * 60 * 1000,
            30 * 60 * 1000,
            60 * 60 * 1000,
            120 * 60 * 1000,
            -1,
    };
    private static final String[] DURATION_LABELS = {"10 分鐘", "30 分鐘", "1 小時", "2 小時", "自訂..."};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(UIHelper.BG_TOP_BAR);

        healthPermLauncher = registerForActivityResult(
                StepWriter.createPermissionContract(),
                granted -> {
                    if (granted.containsAll(StepWriter.REQUIRED_PERMISSIONS)) {
                        performWriteSteps();
                    } else {
                        Toast.makeText(this, "需要 Health Connect 步數寫入權限", Toast.LENGTH_LONG).show();
                    }
                }
        );

        dbHelper = new GpsMockDbHelper(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        geocoder = new Geocoder(this, Locale.getDefault());
        uiHandler = new Handler(Looper.getMainLooper());

        LinearLayout root = UIHelper.pageRoot(this);

        LinearLayout topBar = UIHelper.topBar(this, "GPS 模擬器");
        root.addView(topBar);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = UIHelper.dp(this, 16);
        content.setPadding(p, p, p, p);

        // Start point card
        content.addView(UIHelper.sectionHeader(this, "起點"));
        LinearLayout startCard = UIHelper.card(this);

        TextView gpsLabel = new TextView(this);
        gpsLabel.setText("目前 GPS 位置:");
        gpsLabel.setTextSize(14);
        gpsLabel.setTextColor(UIHelper.TEXT_SECONDARY);
        startCard.addView(gpsLabel);

        LinearLayout gpsRow = new LinearLayout(this);
        gpsRow.setOrientation(LinearLayout.HORIZONTAL);
        gpsRow.setGravity(Gravity.CENTER_VERTICAL);

        currentLocText = new TextView(this);
        currentLocText.setText("取得中...");
        currentLocText.setTextSize(14);
        currentLocText.setTextColor(UIHelper.TEXT_PRIMARY);
        currentLocText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        gpsRow.addView(currentLocText);

        Button useGpsBtn = UIHelper.smallButton(this, "使用", UIHelper.ACCENT_GREEN);
        useGpsBtn.setOnClickListener(v -> {
            if (hasLocation) {
                hasStartPoint = true;
                startLat = currentLat;
                startLng = currentLng;
                startName = "目前位置";
                startResultText.setText(String.format("使用目前位置 (%.4f, %.4f)", currentLat, currentLng));
                startResultText.setTextColor(UIHelper.ACCENT_GREEN);
                startInput.setText("");
            } else {
                Toast.makeText(this, "尚未取得 GPS 位置", Toast.LENGTH_SHORT).show();
            }
        });
        gpsRow.addView(useGpsBtn);

        Button refreshLocBtn = UIHelper.smallButton(this, "重新定位", UIHelper.ACCENT_BLUE);
        refreshLocBtn.setOnClickListener(v -> requestLocation());
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshLp.setMargins(UIHelper.dp(this, 4), 0, 0, 0);
        refreshLocBtn.setLayoutParams(refreshLp);
        gpsRow.addView(refreshLocBtn);

        startCard.addView(gpsRow);

        View divider = new View(this);
        divider.setBackgroundColor(UIHelper.DIVIDER);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UIHelper.dp(this, 1));
        divLp.setMargins(0, UIHelper.dp(this, 12), 0, UIHelper.dp(this, 12));
        divider.setLayoutParams(divLp);
        startCard.addView(divider);

        TextView customLabel = new TextView(this);
        customLabel.setText("或搜尋自訂起點:");
        customLabel.setTextSize(14);
        customLabel.setTextColor(UIHelper.TEXT_SECONDARY);
        startCard.addView(customLabel);

        LinearLayout startSearchRow = new LinearLayout(this);
        startSearchRow.setOrientation(LinearLayout.HORIZONTAL);
        startSearchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams startSearchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startSearchLp.setMargins(0, UIHelper.dp(this, 4), 0, 0);
        startSearchRow.setLayoutParams(startSearchLp);

        startInput = UIHelper.styledInput(this, "例: 台北車站");
        startInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        startSearchRow.addView(startInput);

        Button startSearchBtn = UIHelper.smallButton(this, "搜尋", UIHelper.ACCENT_BLUE);
        LinearLayout.LayoutParams startSearchBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UIHelper.dp(this, 44));
        startSearchBtnLp.setMargins(UIHelper.dp(this, 8), 0, 0, 0);
        startSearchBtn.setLayoutParams(startSearchBtnLp);
        startSearchBtn.setOnClickListener(v -> searchStartPoint());
        startSearchRow.addView(startSearchBtn);

        startCard.addView(startSearchRow);

        startResultText = new TextView(this);
        startResultText.setText("預設使用目前 GPS 位置");
        startResultText.setTextSize(13);
        startResultText.setTextColor(UIHelper.TEXT_HINT);
        startResultText.setPadding(0, UIHelper.dp(this, 8), 0, 0);
        startCard.addView(startResultText);

        Button saveStartBtn = UIHelper.smallButton(this, "儲存為預設", UIHelper.ACCENT_GREEN);
        saveStartBtn.setOnClickListener(v -> {
            if (!hasStartPoint) {
                Toast.makeText(this, "請先設定起點", Toast.LENGTH_SHORT).show();
                return;
            }
            showSaveLocationDialog(startLat, startLng, startName);
        });
        LinearLayout.LayoutParams saveStartLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UIHelper.dp(this, 36));
        saveStartLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        saveStartBtn.setLayoutParams(saveStartLp);
        startCard.addView(saveStartBtn);

        content.addView(startCard);

        // 路線卡片（多點站點）
        content.addView(UIHelper.sectionHeader(this, "路線"));
        LinearLayout destCard = UIHelper.card(this);

        fixedPointCheck = new CheckBox(this);
        fixedPointCheck.setText("定點模擬（固定於起點，不移動）");
        fixedPointCheck.setTextColor(UIHelper.TEXT_PRIMARY);
        fixedPointCheck.setTextSize(14);
        fixedPointCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(UIHelper.ACCENT_GREEN));
        LinearLayout.LayoutParams fixedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fixedLp.setMargins(0, 0, 0, UIHelper.dp(this, 8));
        fixedPointCheck.setLayoutParams(fixedLp);
        destCard.addView(fixedPointCheck);

        TextView routeLabel = new TextView(this);
        routeLabel.setText("路線站點（依序，最後一點為終點）:");
        routeLabel.setTextSize(14);
        routeLabel.setTextColor(UIHelper.TEXT_SECONDARY);
        destCard.addView(routeLabel);

        waypointsContainer = new LinearLayout(this);
        waypointsContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wpcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wpcLp.setMargins(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 4));
        waypointsContainer.setLayoutParams(wpcLp);
        destCard.addView(waypointsContainer);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addRowLp.setMargins(0, UIHelper.dp(this, 4), 0, 0);
        addRow.setLayoutParams(addRowLp);

        destInput = UIHelper.styledInput(this, "搜尋地點加入站點");
        destInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addRow.addView(destInput);

        Button addSearchBtn = UIHelper.smallButton(this, "加入", UIHelper.ACCENT_BLUE);
        LinearLayout.LayoutParams addSearchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UIHelper.dp(this, 44));
        addSearchLp.setMargins(UIHelper.dp(this, 8), 0, 0, 0);
        addSearchBtn.setLayoutParams(addSearchLp);
        addSearchBtn.setOnClickListener(v -> searchPlace());
        addRow.addView(addSearchBtn);

        destCard.addView(addRow);

        Button addCurrentBtn = UIHelper.smallButton(this, "加入目前位置為站點", UIHelper.ACCENT_GREEN);
        LinearLayout.LayoutParams addCurLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UIHelper.dp(this, 36));
        addCurLp.setMargins(0, UIHelper.dp(this, 6), 0, 0);
        addCurrentBtn.setLayoutParams(addCurLp);
        addCurrentBtn.setOnClickListener(v -> {
            if (hasLocation) {
                addWaypoint(currentLat, currentLng, "目前位置");
            } else {
                Toast.makeText(this, "尚未取得 GPS 位置", Toast.LENGTH_SHORT).show();
            }
        });
        destCard.addView(addCurrentBtn);

        followRoadsCheck = new CheckBox(this);
        followRoadsCheck.setText("沿道路移動（使用 OSRM 路線）");
        followRoadsCheck.setTextColor(UIHelper.TEXT_PRIMARY);
        followRoadsCheck.setTextSize(14);
        followRoadsCheck.setChecked(true);
        followRoadsCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(UIHelper.ACCENT_BLUE));
        LinearLayout.LayoutParams followLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        followLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        followRoadsCheck.setLayoutParams(followLp);
        destCard.addView(followRoadsCheck);

        // 定點模擬勾選時隱藏路線相關欄位（固定於起點）
        fixedPointCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            int vis = isChecked ? View.GONE : View.VISIBLE;
            routeLabel.setVisibility(vis);
            waypointsContainer.setVisibility(vis);
            addRow.setVisibility(vis);
            addCurrentBtn.setVisibility(vis);
            followRoadsCheck.setVisibility(vis);
        });

        content.addView(destCard);

        startStopBtn = UIHelper.primaryButton(this, "開始模擬");
        startStopBtn.setOnClickListener(v -> toggleMock());
        content.addView(startStopBtn);

        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setTextColor(UIHelper.TEXT_SECONDARY);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, UIHelper.dp(this, 8), 0, 0);
        content.addView(statusText);

        mockStatusText = new TextView(this);
        mockStatusText.setTextSize(13);
        mockStatusText.setTextColor(UIHelper.ACCENT_GREEN);
        mockStatusText.setGravity(Gravity.CENTER);
        mockStatusText.setTypeface(Typeface.MONOSPACE);
        mockStatusText.setPadding(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 8));
        content.addView(mockStatusText);

        content.addView(UIHelper.sectionHeader(this, "預設地點"));
        presetsContainer = new LinearLayout(this);
        presetsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(presetsContainer);

        // ==================== 步數寫入區塊 ====================
        content.addView(UIHelper.sectionHeader(this, "步數寫入（HEALTH CONNECT）"));

        LinearLayout stepCard = UIHelper.card(this);

        TextView stepInfo = new TextView(this);
        stepInfo.setText("將步數寫入 Health Connect，Pikmin Bloom 等遊戲會透過 Adventure Sync 讀取。");
        stepInfo.setTextSize(12);
        stepInfo.setTextColor(UIHelper.TEXT_HINT);
        stepInfo.setPadding(0, 0, 0, UIHelper.dp(this, 10));
        stepCard.addView(stepInfo);

        // 時數選擇
        TextView hoursLabel = new TextView(this);
        hoursLabel.setText("散步時數");
        hoursLabel.setTextSize(14);
        hoursLabel.setTextColor(UIHelper.TEXT_SECONDARY);
        stepCard.addView(hoursLabel);

        LinearLayout hoursRow = new LinearLayout(this);
        hoursRow.setOrientation(LinearLayout.HORIZONTAL);
        hoursRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hRowLp.setMargins(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 8));
        hoursRow.setLayoutParams(hRowLp);

        double[] hourOptions = {1, 2, 3, 4, 6, 8};
        for (int i = 0; i < hourOptions.length; i++) {
            final double h = hourOptions[i];
            String label = h >= 1 && h == (int) h ? (int) h + "h" : h + "h";
            Button hBtn = UIHelper.smallButton(this, label, UIHelper.ACCENT_BLUE);
            LinearLayout.LayoutParams hBtnLp = new LinearLayout.LayoutParams(
                    0, UIHelper.dp(this, 36), 1);
            hBtnLp.setMargins(UIHelper.dp(this, 2), 0, UIHelper.dp(this, 2), 0);
            hBtn.setLayoutParams(hBtnLp);
            hBtn.setOnClickListener(v -> {
                stepHours = h;
                updateStepHourButtons();
                updateStepEstimate();
            });
            stepHourBtns[i] = hBtn;
            hoursRow.addView(hBtn);
        }
        // 自訂按鈕
        Button customHBtn = UIHelper.smallButton(this, "自訂", UIHelper.TEXT_SECONDARY);
        LinearLayout.LayoutParams customLp = new LinearLayout.LayoutParams(
                0, UIHelper.dp(this, 36), 1);
        customLp.setMargins(UIHelper.dp(this, 2), 0, UIHelper.dp(this, 2), 0);
        customHBtn.setLayoutParams(customLp);
        customHBtn.setOnClickListener(v -> showCustomStepHoursDialog());
        stepHourBtns[6] = customHBtn;
        hoursRow.addView(customHBtn);

        stepCard.addView(hoursRow);

        // 每小時步數
        TextView rateLabel = new TextView(this);
        rateLabel.setText("每小時步數（建議 3,000～8,000）");
        rateLabel.setTextSize(14);
        rateLabel.setTextColor(UIHelper.TEXT_SECONDARY);
        stepCard.addView(rateLabel);

        LinearLayout rateRow = new LinearLayout(this);
        rateRow.setOrientation(LinearLayout.HORIZONTAL);
        rateRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rateRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rateRowLp.setMargins(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 4));
        rateRow.setLayoutParams(rateRowLp);

        EditText rateInput = UIHelper.styledInput(this, "5500");
        rateInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        rateInput.setText(String.valueOf(stepRate));
        rateInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rateInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try {
                    int v = Integer.parseInt(s.toString().trim());
                    if (v > 0) {
                        stepRate = v;
                        updateStepEstimate();
                    }
                } catch (NumberFormatException ignored) {}
            }
        });
        rateRow.addView(rateInput);

        TextView rateUnit = new TextView(this);
        rateUnit.setText(" 步/時（±15%波動）");
        rateUnit.setTextSize(13);
        rateUnit.setTextColor(UIHelper.TEXT_HINT);
        rateRow.addView(rateUnit);

        stepCard.addView(rateRow);

        // 預估結果
        View stepDivider = new View(this);
        stepDivider.setBackgroundColor(UIHelper.DIVIDER);
        LinearLayout.LayoutParams sdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UIHelper.dp(this, 1));
        sdLp.setMargins(0, UIHelper.dp(this, 8), 0, UIHelper.dp(this, 8));
        stepDivider.setLayoutParams(sdLp);
        stepCard.addView(stepDivider);

        stepEstimateText = new TextView(this);
        stepEstimateText.setTextSize(15);
        stepEstimateText.setTextColor(UIHelper.ACCENT_GREEN);
        stepEstimateText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        stepCard.addView(stepEstimateText);

        content.addView(stepCard);

        // 寫入按鈕
        stepWriteBtn = UIHelper.primaryButton(this, "寫入步數");
        stepWriteBtn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_BLUE, 14, this));
        stepWriteBtn.setOnClickListener(v -> onWriteStepsClick());
        content.addView(stepWriteBtn);

        stepStatusText = new TextView(this);
        stepStatusText.setTextSize(12);
        stepStatusText.setTextColor(UIHelper.TEXT_SECONDARY);
        stepStatusText.setGravity(Gravity.CENTER);
        stepStatusText.setPadding(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 12));
        content.addView(stepStatusText);

        updateStepHourButtons();
        updateStepEstimate();

        // ==================== 底部 ====================

        Button updateBtn = UIHelper.smallButton(this, "檢查更新", UIHelper.ACCENT_BLUE);
        LinearLayout.LayoutParams updLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UIHelper.dp(this, 36));
        updLp.setMargins(0, UIHelper.dp(this, 16), 0, 0);
        updLp.gravity = Gravity.CENTER_HORIZONTAL;
        updateBtn.setLayoutParams(updLp);
        updateBtn.setOnClickListener(v -> checkForUpdate(true));
        content.addView(updateBtn);

        TextView verText = new TextView(this);
        verText.setText("版本 v" + getCurrentVersionName());
        verText.setTextSize(11);
        verText.setTextColor(UIHelper.TEXT_HINT);
        verText.setGravity(Gravity.CENTER);
        verText.setPadding(0, UIHelper.dp(this, 6), 0, UIHelper.dp(this, 8));
        content.addView(verText);

        scrollView.addView(content);
        simTabView = scrollView;

        // 頂部分頁列（模擬 / 金色花盆 / 菇點）
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(UIHelper.BG_TOP_BAR);
        int tabPad = UIHelper.dp(this, 6);
        tabBar.setPadding(tabPad, tabPad, tabPad, tabPad);
        tabSimBtn = tabButton("模擬");
        tabPotBtn = tabButton("金色花盆");
        tabMushBtn = tabButton("菇點");
        tabSimBtn.setOnClickListener(v -> selectTab(0));
        tabPotBtn.setOnClickListener(v -> selectTab(1));
        tabMushBtn.setOnClickListener(v -> selectTab(2));
        tabBar.addView(tabSimBtn);
        tabBar.addView(tabPotBtn);
        tabBar.addView(tabMushBtn);
        root.addView(tabBar);

        // 內容容器：分頁疊在一起，切換 visibility
        FrameLayout contentFrame = new FrameLayout(this);
        potTabView = buildFlowerPotTab();
        mushTabView = buildMushroomTab();
        contentFrame.addView(simTabView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentFrame.addView(potTabView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentFrame.addView(mushTabView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        selectTab(0);
        loadPresets();
        loadFlowerPots();
        loadMushrooms();
        renderWaypoints();
        checkAndRequestPermission();

        if (savedInstanceState == null) {
            checkForUpdate(false);
        }
    }

    private Button tabButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(15);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setStateListAnimator(null);
        btn.setElevation(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, UIHelper.dp(this, 40), 1);
        lp.setMargins(UIHelper.dp(this, 4), 0, UIHelper.dp(this, 4), 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void selectTab(int index) {
        simTabView.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        potTabView.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        mushTabView.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        styleTab(tabSimBtn, index == 0);
        styleTab(tabPotBtn, index == 1);
        styleTab(tabMushBtn, index == 2);
        if (index == 2) {
            loadMushrooms(); // 刷新距離與剩餘時間
        }
    }

    private void styleTab(Button btn, boolean selected) {
        if (selected) {
            btn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_GREEN, 10, this));
            btn.setTextColor(Color.WHITE);
        } else {
            btn.setBackground(UIHelper.roundRectStroke(
                    UIHelper.BG_CARD, Color.parseColor("#2E4050"), 10, 1, this));
            btn.setTextColor(UIHelper.TEXT_SECONDARY);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateButtonState();
        requestLocation();
        uiHandler.post(statusUpdateRunnable);
        loadMushrooms(); // 通知上標記大菇/記錄菇點後回到 App 要刷新
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(statusUpdateRunnable);
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {}
    }

    private void checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestLocation();
            } else {
                currentLocText.setText("需要位置權限");
                currentLocText.setTextColor(UIHelper.ACCENT_RED);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            currentLocText.setText("需要位置權限");
            return;
        }

        currentLocText.setText("取得中...");
        currentLocText.setTextColor(UIHelper.TEXT_PRIMARY);

        try {
            Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnown == null) {
                lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (lastKnown != null) {
                updateCurrentLocation(lastKnown);
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, this);
            } else {
                currentLocText.setText("請開啟 GPS");
                currentLocText.setTextColor(UIHelper.ACCENT_RED);
            }
        } catch (Exception e) {
            currentLocText.setText("取得位置失敗: " + e.getMessage());
            currentLocText.setTextColor(UIHelper.ACCENT_RED);
        }
    }

    private void updateCurrentLocation(Location loc) {
        currentLat = loc.getLatitude();
        currentLng = loc.getLongitude();
        hasLocation = true;
        currentLocText.setText(String.format("%.6f, %.6f", currentLat, currentLng));
        currentLocText.setTextColor(UIHelper.ACCENT_GREEN);
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        updateCurrentLocation(location);
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {}
    }

    private void searchStartPoint() {
        String query = startInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "請輸入起點名稱", Toast.LENGTH_SHORT).show();
            return;
        }

        startResultText.setText("搜尋中...");
        startResultText.setTextColor(UIHelper.TEXT_SECONDARY);

        new Thread(() -> {
            try {
                List<Address> results = geocoder.getFromLocationName(query, 5);
                runOnUiThread(() -> {
                    if (results == null || results.isEmpty()) {
                        startResultText.setText("找不到「" + query + "」");
                        startResultText.setTextColor(UIHelper.ACCENT_RED);
                        hasStartPoint = false;
                    } else if (results.size() == 1) {
                        selectStartAddress(results.get(0));
                    } else {
                        showStartAddressPicker(results);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    startResultText.setText("搜尋失敗: " + e.getMessage());
                    startResultText.setTextColor(UIHelper.ACCENT_RED);
                    hasStartPoint = false;
                    Log.e(TAG, "Start point search failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showStartAddressPicker(List<Address> addresses) {
        String[] items = new String[addresses.size()];
        for (int i = 0; i < addresses.size(); i++) {
            items[i] = formatAddress(addresses.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle("選擇起點")
                .setItems(items, (dialog, which) -> selectStartAddress(addresses.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void selectStartAddress(Address address) {
        startLat = address.getLatitude();
        startLng = address.getLongitude();
        startName = formatAddress(address);
        hasStartPoint = true;

        startResultText.setText(String.format("%s\n(%.6f, %.6f)", startName, startLat, startLng));
        startResultText.setTextColor(UIHelper.ACCENT_GREEN);

        Log.i(TAG, "Selected start: " + startName);
    }

    private void searchPlace() {
        String query = destInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "請輸入地點名稱", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                List<Address> results = geocoder.getFromLocationName(query, 5);
                runOnUiThread(() -> {
                    if (results == null || results.isEmpty()) {
                        Toast.makeText(this, "找不到「" + query + "」", Toast.LENGTH_SHORT).show();
                    } else if (results.size() == 1) {
                        selectAddress(results.get(0));
                    } else {
                        showAddressPicker(results);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "搜尋失敗: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Place search failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showAddressPicker(List<Address> addresses) {
        String[] items = new String[addresses.size()];
        for (int i = 0; i < addresses.size(); i++) {
            Address addr = addresses.get(i);
            items[i] = formatAddress(addr);
        }

        new AlertDialog.Builder(this)
                .setTitle("選擇地點")
                .setItems(items, (dialog, which) -> selectAddress(addresses.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void selectAddress(Address address) {
        String nm = formatAddress(address);
        addWaypoint(address.getLatitude(), address.getLongitude(), nm);
        destInput.setText("");
        Log.i(TAG, "Added waypoint: " + nm);
    }

    private String formatAddress(Address address) {
        StringBuilder sb = new StringBuilder();

        if (address.getFeatureName() != null && !address.getFeatureName().matches("\\d+")) {
            sb.append(address.getFeatureName());
        }

        if (address.getLocality() != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(address.getLocality());
        }

        if (address.getAdminArea() != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(address.getAdminArea());
        }

        if (address.getCountryName() != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(address.getCountryName());
        }

        if (sb.length() == 0) {
            for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(address.getAddressLine(i));
            }
        }

        return sb.length() > 0 ? sb.toString() : String.format("%.4f, %.4f", address.getLatitude(), address.getLongitude());
    }

    private void setStartPoint(double lat, double lng, String name) {
        startLat = lat;
        startLng = lng;
        startName = name;
        hasStartPoint = true;
        startInput.setText(name);
        startResultText.setText(String.format("%s\n(%.6f, %.6f)", name, lat, lng));
        startResultText.setTextColor(UIHelper.ACCENT_GREEN);
    }

    private void addWaypoint(double lat, double lng, String name) {
        Waypoint wp = new Waypoint();
        wp.lat = lat;
        wp.lng = lng;
        wp.name = name;
        wp.durationMs = DURATION_VALUES[0]; // 預設 10 分鐘
        waypoints.add(wp);
        renderWaypoints();
    }

    private void renderWaypoints() {
        if (waypointsContainer == null) return;
        waypointsContainer.removeAllViews();
        if (waypoints.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚未新增站點（搜尋地點、加入目前位置，或從預設點按「終」）");
            empty.setTextColor(UIHelper.TEXT_HINT);
            empty.setTextSize(13);
            empty.setPadding(0, UIHelper.dp(this, 4), 0, 0);
            waypointsContainer.addView(empty);
            return;
        }
        for (int i = 0; i < waypoints.size(); i++) {
            waypointsContainer.addView(buildWaypointRow(i));
        }
    }

    private View buildWaypointRow(int index) {
        Waypoint wp = waypoints.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UIHelper.roundRect(UIHelper.BG_CARD, 12, this));
        int pad = UIHelper.dp(this, 10);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, UIHelper.dp(this, 3), 0, UIHelper.dp(this, 3));
        row.setLayoutParams(rowLp);

        TextView name = new TextView(this);
        name.setText((index + 1) + ". " + wp.name);
        name.setTextSize(14);
        name.setTextColor(UIHelper.TEXT_PRIMARY);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(name);

        int gap = UIHelper.dp(this, 4);

        Button timeBtn = UIHelper.smallButton(this, formatDurShort(wp.durationMs), UIHelper.ACCENT_BLUE);
        timeBtn.setLayoutParams(presetButtonParams(gap));
        timeBtn.setOnClickListener(v -> showWaypointDurationDialog(wp));
        row.addView(timeBtn);

        Button upBtn = UIHelper.smallButton(this, "↑", UIHelper.TEXT_SECONDARY);
        upBtn.setLayoutParams(presetButtonParams(gap));
        upBtn.setOnClickListener(v -> moveWaypoint(index, -1));
        row.addView(upBtn);

        Button downBtn = UIHelper.smallButton(this, "↓", UIHelper.TEXT_SECONDARY);
        downBtn.setLayoutParams(presetButtonParams(gap));
        downBtn.setOnClickListener(v -> moveWaypoint(index, 1));
        row.addView(downBtn);

        Button delBtn = UIHelper.smallButton(this, "✕", UIHelper.ACCENT_RED);
        delBtn.setLayoutParams(presetButtonParams(gap));
        delBtn.setOnClickListener(v -> {
            waypoints.remove(index);
            renderWaypoints();
        });
        row.addView(delBtn);

        return row;
    }

    private void moveWaypoint(int index, int delta) {
        int target = index + delta;
        if (target < 0 || target >= waypoints.size()) return;
        Waypoint tmp = waypoints.get(index);
        waypoints.set(index, waypoints.get(target));
        waypoints.set(target, tmp);
        renderWaypoints();
    }

    private void showWaypointDurationDialog(Waypoint wp) {
        new AlertDialog.Builder(this)
                .setTitle("這一段移動時間")
                .setItems(DURATION_LABELS, (d, w) -> {
                    if (DURATION_VALUES[w] == -1) {
                        showWaypointCustomDurationDialog(wp);
                    } else {
                        wp.durationMs = DURATION_VALUES[w];
                        renderWaypoints();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showWaypointCustomDurationDialog(Waypoint wp) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("分鐘數");
        input.setText(String.valueOf(wp.durationMs / 60000));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("自訂這一段時間")
                .setMessage("請輸入移動時間（分鐘）")
                .setView(input)
                .setPositiveButton("確定", (d, w) -> {
                    try {
                        int minutes = Integer.parseInt(input.getText().toString().trim());
                        if (minutes < 1) minutes = 1;
                        if (minutes > 1440) minutes = 1440;
                        wp.durationMs = minutes * 60 * 1000L;
                        renderWaypoints();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "請輸入有效數字", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String formatDurShort(long ms) {
        long minutes = ms / 60000;
        if (minutes >= 60 && minutes % 60 == 0) {
            return (minutes / 60) + " 小時";
        }
        return minutes + " 分";
    }

    private static class Waypoint {
        double lat;
        double lng;
        String name;
        long durationMs;
    }

    private void toggleMock() {
        if (GpsMockService.isRunning()) {
            Intent stopIntent = new Intent(this, GpsMockService.class);
            stopIntent.setAction(GpsMockService.ACTION_STOP);
            startService(stopIntent);
            uiHandler.removeCallbacks(statusUpdateRunnable);
            mockStatusText.setText("");
            updateButtonState();
            Toast.makeText(this, "已停止模擬", Toast.LENGTH_SHORT).show();
            return;
        }

        double actualStartLat, actualStartLng;
        if (hasStartPoint) {
            actualStartLat = startLat;
            actualStartLng = startLng;
        } else if (hasLocation) {
            actualStartLat = currentLat;
            actualStartLng = currentLng;
        } else {
            Toast.makeText(this, "請先設定起點（使用 GPS 或搜尋地點）", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean fixed = fixedPointCheck.isChecked();
        Intent intent = new Intent(this, GpsMockService.class);
        intent.putExtra(GpsMockService.EXTRA_FIXED_POINT, fixed);

        if (fixed) {
            intent.putExtra(GpsMockService.EXTRA_START_LAT, actualStartLat);
            intent.putExtra(GpsMockService.EXTRA_START_LNG, actualStartLng);
            intent.putExtra(GpsMockService.EXTRA_END_LAT, actualStartLat);
            intent.putExtra(GpsMockService.EXTRA_END_LNG, actualStartLng);
            intent.putExtra(GpsMockService.EXTRA_DURATION_MS, 600_000L);
            intent.putExtra(GpsMockService.EXTRA_FOLLOW_ROADS, false);
            Log.i(TAG, "Starting fixed-point mock at start");
        } else {
            if (waypoints.isEmpty()) {
                Toast.makeText(this, "請至少新增一個路線站點", Toast.LENGTH_SHORT).show();
                return;
            }
            int p = waypoints.size() + 1;
            double[] lats = new double[p];
            double[] lngs = new double[p];
            long[] segDur = new long[waypoints.size()];
            lats[0] = actualStartLat;
            lngs[0] = actualStartLng;
            for (int i = 0; i < waypoints.size(); i++) {
                Waypoint wp = waypoints.get(i);
                lats[i + 1] = wp.lat;
                lngs[i + 1] = wp.lng;
                segDur[i] = wp.durationMs;
            }
            intent.putExtra(GpsMockService.EXTRA_LATS, lats);
            intent.putExtra(GpsMockService.EXTRA_LNGS, lngs);
            intent.putExtra(GpsMockService.EXTRA_SEG_DURATIONS, segDur);
            intent.putExtra(GpsMockService.EXTRA_FOLLOW_ROADS, followRoadsCheck.isChecked());
            Log.i(TAG, "Starting route mock: " + waypoints.size() + " segments");
        }

        startForegroundService(intent);
        Toast.makeText(this, "開始模擬位置", Toast.LENGTH_SHORT).show();

        uiHandler.postDelayed(() -> {
            updateButtonState();
            if (GpsMockService.isRunning()) {
                uiHandler.post(statusUpdateRunnable);
            }
        }, 500);
    }

    private void updateButtonState() {
        boolean running = GpsMockService.isRunning();
        if (running) {
            startStopBtn.setText("停止模擬");
            startStopBtn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_RED, 14, this));
            statusText.setText("模擬中... 點擊上方按鈕或通知欄停止");
            statusText.setTextColor(UIHelper.ACCENT_GREEN);
        } else {
            startStopBtn.setText("開始模擬");
            startStopBtn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_GREEN, 14, this));
            statusText.setText("請在開發者選項中選擇此應用作為模擬位置應用");
            statusText.setTextColor(UIHelper.TEXT_SECONDARY);
            mockStatusText.setText("");
        }
    }

    private void showMockLocationSetupDialog() {
        new AlertDialog.Builder(this)
                .setTitle("設定模擬位置")
                .setMessage("請先在「設定 > 開發者選項 > 選取模擬位置應用程式」中選擇 GPS 模擬器。")
                .setPositiveButton("前往設定", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "無法開啟設定", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private LinearLayout.LayoutParams presetButtonParams(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(leftMargin, 0, 0, 0);
        return lp;
    }

    private void loadPresets() {
        presetsContainer.removeAllViews();
        List<GpsMockDbHelper.Preset> presets = dbHelper.getAllPresets();

        if (presets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚無預設地點");
            empty.setTextColor(UIHelper.TEXT_HINT);
            empty.setTextSize(13);
            empty.setPadding(0, UIHelper.dp(this, 8), 0, 0);
            presetsContainer.addView(empty);
            return;
        }

        for (GpsMockDbHelper.Preset preset : presets) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(UIHelper.roundRect(UIHelper.BG_CARD, 12, this));
            int pad = UIHelper.dp(this, 12);
            row.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 4));
            row.setLayoutParams(rowLp);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView name = new TextView(this);
            name.setText(preset.name);
            name.setTextSize(14);
            name.setTextColor(UIHelper.TEXT_PRIMARY);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

            TextView coords = new TextView(this);
            coords.setText(String.format("%.4f, %.4f", preset.lat, preset.lng));
            coords.setTextSize(12);
            coords.setTextColor(UIHelper.TEXT_SECONDARY);

            info.addView(name);
            info.addView(coords);

            int btnGap = UIHelper.dp(this, 6);

            Button startBtn = UIHelper.smallButton(this, "起", UIHelper.ACCENT_GREEN);
            startBtn.setLayoutParams(presetButtonParams(btnGap));
            startBtn.setOnClickListener(v -> setStartPoint(preset.lat, preset.lng, preset.name));

            Button endBtn = UIHelper.smallButton(this, "終", UIHelper.ACCENT_BLUE);
            endBtn.setLayoutParams(presetButtonParams(btnGap));
            endBtn.setOnClickListener(v -> addWaypoint(preset.lat, preset.lng, preset.name));

            Button delBtn = UIHelper.smallButton(this, "刪", UIHelper.ACCENT_RED);
            delBtn.setLayoutParams(presetButtonParams(btnGap));
            delBtn.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("刪除預設")
                        .setMessage("確定要刪除「" + preset.name + "」？")
                        .setPositiveButton("刪除", (d, w) -> {
                            dbHelper.deletePreset(preset.id);
                            Log.i(TAG, "Deleted preset: " + preset.name);
                            loadPresets();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });

            row.addView(info);
            row.addView(startBtn);
            row.addView(endBtn);
            row.addView(delBtn);

            presetsContainer.addView(row);
        }
    }

    private void showSaveLocationDialog(double lat, double lng, String defaultName) {
        EditText nameInput = new EditText(this);
        nameInput.setHint("預設名稱");
        nameInput.setText(defaultName);

        new AlertDialog.Builder(this)
                .setTitle("儲存預設")
                .setView(nameInput)
                .setPositiveButton("儲存", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = String.format("%.2f, %.2f", lat, lng);
                    }
                    dbHelper.insertPreset(name, lat, lng);
                    Log.i(TAG, "Saved preset: " + name);
                    loadPresets();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===================== 金色花盆分頁 =====================

    private ScrollView buildFlowerPotTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = UIHelper.dp(this, 16);
        content.setPadding(p, p, p, p);

        content.addView(UIHelper.sectionHeader(this, "金色花盆"));

        TextView hint = new TextView(this);
        hint.setText("點「定點」即固定到該花盆位置。座標不準時，用「修正」貼上 Google 正確座標。");
        hint.setTextSize(12);
        hint.setTextColor(UIHelper.TEXT_HINT);
        hint.setPadding(0, 0, 0, UIHelper.dp(this, 8));
        content.addView(hint);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button importBtn = UIHelper.smallButton(this, "匯入/貼上座標", UIHelper.ACCENT_BLUE);
        importBtn.setOnClickListener(v -> showImportFlowerPotsDialog());
        btnRow.addView(importBtn);
        Button addBtn = UIHelper.smallButton(this, "+ 新增", UIHelper.ACCENT_GREEN);
        addBtn.setLayoutParams(presetButtonParams(UIHelper.dp(this, 8)));
        addBtn.setOnClickListener(v -> showAddFlowerPotDialog());
        btnRow.addView(addBtn);
        Button delAllBtn = UIHelper.smallButton(this, "刪除全部", UIHelper.ACCENT_RED);
        delAllBtn.setLayoutParams(presetButtonParams(UIHelper.dp(this, 8)));
        delAllBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("刪除所有花盆")
                .setMessage("確定要刪除所有花盆點？此操作無法復原。")
                .setPositiveButton("全部刪除", (d, w) -> {
                    dbHelper.deleteAllFlowerPots();
                    loadFlowerPots();
                })
                .setNegativeButton("取消", null)
                .show());
        btnRow.addView(delAllBtn);
        content.addView(btnRow);

        // 巡迴花盆按鈕
        Button tourBtn = UIHelper.primaryButton(this, "巡迴所有花盆（跳點模式）");
        tourBtn.setBackground(UIHelper.roundRect(Color.parseColor("#FF6F00"), 14, this));
        LinearLayout.LayoutParams tourLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tourLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        tourBtn.setLayoutParams(tourLp);
        tourBtn.setOnClickListener(v -> showTourFlowerPotsDialog());
        content.addView(tourBtn);

        potsContainer = new LinearLayout(this);
        potsContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pcLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        potsContainer.setLayoutParams(pcLp);
        content.addView(potsContainer);

        scroll.addView(content);
        return scroll;
    }

    /** 花盆與大菇共用修正對話框，修改後兩邊都刷新。 */
    private void refreshPotLists() {
        loadFlowerPots();
        loadMushrooms();
    }

    private void loadFlowerPots() {
        potsContainer.removeAllViews();
        List<GpsMockDbHelper.FlowerPot> pots = dbHelper.getAllFlowerPots();
        if (pots.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚無金色花盆");
            empty.setTextColor(UIHelper.TEXT_HINT);
            empty.setTextSize(13);
            empty.setPadding(0, UIHelper.dp(this, 8), 0, 0);
            potsContainer.addView(empty);
            return;
        }

        for (GpsMockDbHelper.FlowerPot pot : pots) {
            potsContainer.addView(buildFlowerPotRow(pot));
        }
    }

    private View buildFlowerPotRow(GpsMockDbHelper.FlowerPot pot) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UIHelper.roundRect(UIHelper.BG_CARD, 12, this));
        int pad = UIHelper.dp(this, 12);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 4));
        row.setLayoutParams(rowLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView name = new TextView(this);
        name.setText(pot.corrected ? pot.name + "（已修正）" : pot.name);
        name.setTextSize(14);
        name.setTextColor(UIHelper.TEXT_PRIMARY);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        TextView coords = new TextView(this);
        coords.setText(String.format("%.5f, %.5f", pot.lat, pot.lng));
        coords.setTextSize(12);
        coords.setTextColor(UIHelper.TEXT_SECONDARY);

        info.addView(name);
        info.addView(coords);
        row.addView(info);

        int gap = UIHelper.dp(this, 6);

        Button fixBtn = UIHelper.smallButton(this, "定點", UIHelper.ACCENT_GREEN);
        fixBtn.setLayoutParams(presetButtonParams(gap));
        fixBtn.setOnClickListener(v -> startFixedPointSim(pot.lat, pot.lng, pot.name));
        row.addView(fixBtn);

        Button correctBtn = UIHelper.smallButton(this, "修正", UIHelper.ACCENT_BLUE);
        correctBtn.setLayoutParams(presetButtonParams(gap));
        correctBtn.setOnClickListener(v -> showCorrectFlowerPotDialog(pot));
        row.addView(correctBtn);

        Button delBtn = UIHelper.smallButton(this, "刪", UIHelper.ACCENT_RED);
        delBtn.setLayoutParams(presetButtonParams(gap));
        delBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("刪除花盆")
                .setMessage("確定要刪除「" + pot.name + "」？")
                .setPositiveButton("刪除", (d, w) -> {
                    dbHelper.deleteFlowerPot(pot.id);
                    loadFlowerPots();
                })
                .setNegativeButton("取消", null)
                .show());
        row.addView(delBtn);

        return row;
    }

    private void startFixedPointSim(double lat, double lng, String name) {
        Intent intent = new Intent(this, GpsMockService.class);
        intent.putExtra(GpsMockService.EXTRA_START_LAT, lat);
        intent.putExtra(GpsMockService.EXTRA_START_LNG, lng);
        intent.putExtra(GpsMockService.EXTRA_END_LAT, lat);
        intent.putExtra(GpsMockService.EXTRA_END_LNG, lng);
        intent.putExtra(GpsMockService.EXTRA_DURATION_MS, 600_000L);
        intent.putExtra(GpsMockService.EXTRA_FOLLOW_ROADS, false);
        intent.putExtra(GpsMockService.EXTRA_FIXED_POINT, true);
        startForegroundService(intent);
        Toast.makeText(this, "已定點到「" + name + "」", Toast.LENGTH_SHORT).show();
        selectTab(0);
        uiHandler.postDelayed(() -> {
            updateButtonState();
            if (GpsMockService.isRunning()) {
                uiHandler.post(statusUpdateRunnable);
            }
        }, 500);
    }

    /** 從文字解析第一組經緯度（接受 "lat, lng" 或含座標的 Google 地圖網址）。失敗回傳 null。 */
    private double[] parseLatLng(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d{1,3}\\.\\d+").matcher(text);
        Double lat = null;
        Double lng = null;
        while (m.find()) {
            double v = Double.parseDouble(m.group());
            if (lat == null) {
                lat = v;
            } else {
                lng = v;
                break;
            }
        }
        if (lat == null || lng == null) return null;
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
        return new double[]{lat, lng};
    }

    private void showImportFlowerPotsDialog() {
        EditText input = new EditText(this);
        input.setHint("每行一筆，含座標即可\n例: 台北車站 25.04852, 121.51419");
        input.setMinLines(5);
        input.setGravity(Gravity.TOP);

        new AlertDialog.Builder(this)
                .setTitle("匯入/貼上座標")
                .setView(input)
                .setPositiveButton("匯入", (d, w) -> {
                    String[] lines = input.getText().toString().split("\\n");
                    int count = 0;
                    for (String line : lines) {
                        if (line.trim().isEmpty()) continue;
                        double[] ll = parseLatLng(line);
                        if (ll == null) continue;
                        String nm = line.replaceAll("-?\\d{1,3}\\.\\d+", "").replaceAll("[,;|\\s]+", " ").trim();
                        if (nm.isEmpty()) nm = String.format("花盆 %.4f", ll[0]);
                        dbHelper.insertFlowerPot(nm, ll[0], ll[1], GpsMockDbHelper.CATEGORY_EVENT);
                        count++;
                    }
                    Toast.makeText(this, "已匯入 " + count + " 筆", Toast.LENGTH_SHORT).show();
                    loadFlowerPots();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddFlowerPotDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = UIHelper.dp(this, 16);
        col.setPadding(p, 0, p, 0);

        EditText nameInput = new EditText(this);
        nameInput.setHint("名稱");
        col.addView(nameInput);

        EditText coordInput = new EditText(this);
        coordInput.setHint("lat, lng 或 Google 地圖網址");
        col.addView(coordInput);

        new AlertDialog.Builder(this)
                .setTitle("新增花盆")
                .setView(col)
                .setPositiveButton("新增", (d, w) -> {
                    double[] ll = parseLatLng(coordInput.getText().toString());
                    if (ll == null) {
                        Toast.makeText(this, "座標格式無法解析", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String nm = nameInput.getText().toString().trim();
                    if (nm.isEmpty()) nm = String.format("%.4f, %.4f", ll[0], ll[1]);
                    dbHelper.insertFlowerPot(nm, ll[0], ll[1], GpsMockDbHelper.CATEGORY_EVENT);
                    loadFlowerPots();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCorrectFlowerPotDialog(GpsMockDbHelper.FlowerPot pot) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = UIHelper.dp(this, 16);
        col.setPadding(p, 0, p, 0);

        TextView origText = new TextView(this);
        origText.setText(String.format("原始座標: %.5f, %.5f", pot.origLat, pot.origLng));
        origText.setTextSize(12);
        origText.setTextColor(UIHelper.TEXT_SECONDARY);
        origText.setPadding(0, 0, 0, UIHelper.dp(this, 8));
        col.addView(origText);

        EditText coordInput = new EditText(this);
        coordInput.setHint("貼上正確 lat,lng 或 Google 地圖網址");
        coordInput.setText(String.format("%.5f, %.5f", pot.lat, pot.lng));
        col.addView(coordInput);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("修正「" + pot.name + "」")
                .setView(col)
                .setPositiveButton("儲存修正", (d, w) -> {
                    double[] ll = parseLatLng(coordInput.getText().toString());
                    if (ll == null) {
                        Toast.makeText(this, "座標格式無法解析", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dbHelper.correctFlowerPot(pot.id, ll[0], ll[1]);
                    Toast.makeText(this, "已修正", Toast.LENGTH_SHORT).show();
                    refreshPotLists();
                })
                .setNegativeButton("取消", null);

        if (pot.corrected) {
            b.setNeutralButton("復原", (d, w) -> {
                dbHelper.revertFlowerPot(pot.id);
                Toast.makeText(this, "已復原", Toast.LENGTH_SHORT).show();
                refreshPotLists();
            });
        }
        b.show();
    }

    // ===================== 巡迴花盆（跳點模式） =====================

    private void showTourFlowerPotsDialog() {
        List<GpsMockDbHelper.FlowerPot> pots = dbHelper.getAllFlowerPots();
        if (pots.isEmpty()) {
            Toast.makeText(this, "尚無花盆可巡迴", Toast.LENGTH_SHORT).show();
            return;
        }
        if (GpsMockService.isRunning()) {
            Toast.makeText(this, "請先停止目前的模擬", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] intervalLabels = {"每點停留 3 秒", "每點停留 5 秒", "每點停留 10 秒", "自訂秒數..."};
        int[] intervalValues = {3, 5, 10, -1};

        new AlertDialog.Builder(this)
                .setTitle("巡迴 " + pots.size() + " 個花盆")
                .setItems(intervalLabels, (d, w) -> {
                    if (intervalValues[w] == -1) {
                        showCustomTourIntervalDialog(pots);
                    } else {
                        confirmTourStart(pots, intervalValues[w]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomTourIntervalDialog(List<GpsMockDbHelper.FlowerPot> pots) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("秒數");
        input.setText("5");
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("自訂每點停留秒數")
                .setView(input)
                .setPositiveButton("確定", (d, w) -> {
                    try {
                        int sec = Integer.parseInt(input.getText().toString().trim());
                        if (sec < 1) sec = 1;
                        if (sec > 300) sec = 300;
                        confirmTourStart(pots, sec);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "請輸入有效數字", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmTourStart(List<GpsMockDbHelper.FlowerPot> pots, int intervalSec) {
        long totalSec = (long) pots.size() * intervalSec;
        String timeStr = totalSec >= 60
                ? String.format("%d 分 %d 秒", totalSec / 60, totalSec % 60)
                : totalSec + " 秒";

        CheckBox stepCheck = new CheckBox(this);
        stepCheck.setText("同時寫入步數（2 小時 / 5500 步/時）");
        stepCheck.setTextColor(UIHelper.TEXT_PRIMARY);
        stepCheck.setTextSize(14);
        stepCheck.setChecked(true);
        stepCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(UIHelper.ACCENT_GREEN));
        int pad = UIHelper.dp(this, 16);
        stepCheck.setPadding(pad, pad, pad, 0);

        new AlertDialog.Builder(this)
                .setTitle("確認巡迴")
                .setMessage(String.format("共 %d 個花盆，每點停留 %d 秒\n預計總時間：%s",
                        pots.size(), intervalSec, timeStr))
                .setView(stepCheck)
                .setPositiveButton("開始巡迴", (d, w) -> {
                    boolean writeSteps = stepCheck.isChecked();
                    startFlowerPotTour(pots, intervalSec);
                    if (writeSteps) {
                        // 延遲一秒後寫步數，避免與模擬啟動衝突
                        uiHandler.postDelayed(this::onWriteStepsClick, 1000);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startFlowerPotTour(List<GpsMockDbHelper.FlowerPot> pots, int intervalSec) {
        double[] lats = new double[pots.size()];
        double[] lngs = new double[pots.size()];
        long[] stayMs = new long[pots.size()];
        for (int i = 0; i < pots.size(); i++) {
            lats[i] = pots.get(i).lat;
            lngs[i] = pots.get(i).lng;
            stayMs[i] = intervalSec * 1000L;
        }
        startJumpTour(lats, lngs, stayMs, null);

        Toast.makeText(this, String.format("開始巡迴 %d 個花盆（每點 %d 秒）", pots.size(), intervalSec),
                Toast.LENGTH_SHORT).show();
    }

    /** 跳點模式：依序定位到每個點，各停留 stayMs[i]。最後一點會持續停留直到停止。 */
    private void startJumpTour(double[] lats, double[] lngs, long[] stayMs, long[] stopIds) {
        Intent intent = new Intent(this, GpsMockService.class);
        intent.putExtra(GpsMockService.EXTRA_LATS, lats);
        intent.putExtra(GpsMockService.EXTRA_LNGS, lngs);
        intent.putExtra(GpsMockService.EXTRA_SEG_DURATIONS, stayMs);
        intent.putExtra(GpsMockService.EXTRA_FOLLOW_ROADS, false);
        intent.putExtra(GpsMockService.EXTRA_FIXED_POINT, false);
        intent.putExtra(GpsMockService.EXTRA_JUMP_MODE, true);
        if (stopIds != null) {
            intent.putExtra(GpsMockService.EXTRA_STOP_IDS, stopIds);
        }
        startForegroundService(intent);

        selectTab(0); // 切回模擬分頁看狀態
        uiHandler.postDelayed(() -> {
            updateButtonState();
            if (GpsMockService.isRunning()) {
                uiHandler.post(statusUpdateRunnable);
            }
        }, 500);
    }

    // ===================== 皮克敏菇點分頁 =====================
    // 菇點位置固定，大菇會隨機長在菇點上：先累積菇點，再巡邏找出目前有大菇的點。

    private ScrollView buildMushroomTab() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = UIHelper.dp(this, 16);
        content.setPadding(p, p, p, p);

        content.addView(UIHelper.sectionHeader(this, "皮克敏菇點"));

        TextView hint = new TextView(this);
        hint.setText("最快：往下到「線上大菇地圖」載入後直接按「去打」；或從其他網站複製座標，切回本 App 會自動跳出「去打」。\n"
                + "菇點是固定的，大菇隨機長在菇點上。\n"
                + "1. 走到或模擬到菇點附近，按「記錄目前位置」（模擬中也可按通知上的「記錄菇點」）\n"
                + "2. 按「巡邏菇點」逐一查看，看到大菇時拉下通知按「標記大菇」，巡邏會暫停讓你加入，再按「繼續」\n"
                + "3. 點菇點名稱可手動標記/取消大菇");
        hint.setTextSize(12);
        hint.setTextColor(UIHelper.TEXT_HINT);
        hint.setPadding(0, 0, 0, UIHelper.dp(this, 8));
        content.addView(hint);

        // 家（巡邏/打完回到這裡）
        LinearLayout homeRow = new LinearLayout(this);
        homeRow.setOrientation(LinearLayout.HORIZONTAL);
        homeRow.setGravity(Gravity.CENTER_VERTICAL);
        homeRow.setBackground(UIHelper.roundRect(UIHelper.BG_CARD, 12, this));
        int hp = UIHelper.dp(this, 12);
        homeRow.setPadding(hp, hp, hp, hp);
        homeText = new TextView(this);
        homeText.setTextSize(13);
        homeText.setTextColor(UIHelper.TEXT_PRIMARY);
        homeText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        homeRow.addView(homeText);
        Button homeBtn = UIHelper.smallButton(this, "設定家", UIHelper.ACCENT_BLUE);
        homeBtn.setOnClickListener(v -> showSetHomeDialog());
        homeRow.addView(homeBtn);
        content.addView(homeRow);
        updateHomeText();

        Button recordBtn = UIHelper.primaryButton(this, "記錄目前位置為菇點");
        recordBtn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_GREEN, 14, this));
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        recordBtn.setLayoutParams(recLp);
        recordBtn.setOnClickListener(v -> recordCurrentSpot());
        content.addView(recordBtn);

        Button clipBtn = UIHelper.primaryButton(this, "貼上剪貼簿座標去打");
        clipBtn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_BLUE, 14, this));
        LinearLayout.LayoutParams clipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clipLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        clipBtn.setLayoutParams(clipLp);
        clipBtn.setOnClickListener(v -> checkClipboardForCoords(true));
        content.addView(clipBtn);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, UIHelper.dp(this, 8), 0, 0);
        Button importBtn = UIHelper.smallButton(this, "匯入/貼上", UIHelper.ACCENT_BLUE);
        importBtn.setOnClickListener(v -> showImportMushroomsDialog());
        btnRow.addView(importBtn);
        Button addBtn = UIHelper.smallButton(this, "+ 新增", UIHelper.ACCENT_GREEN);
        addBtn.setLayoutParams(presetButtonParams(UIHelper.dp(this, 8)));
        addBtn.setOnClickListener(v -> showAddMushroomDialog());
        btnRow.addView(addBtn);
        Button delAllBtn = UIHelper.smallButton(this, "刪除全部", UIHelper.ACCENT_RED);
        delAllBtn.setLayoutParams(presetButtonParams(UIHelper.dp(this, 8)));
        delAllBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("刪除所有菇點")
                .setMessage("確定要刪除所有菇點？此操作無法復原。")
                .setPositiveButton("全部刪除", (d, w) -> {
                    dbHelper.deleteAllMushrooms();
                    loadMushrooms();
                })
                .setNegativeButton("取消", null)
                .show());
        btnRow.addView(delAllBtn);
        content.addView(btnRow);

        Button patrolBtn = UIHelper.primaryButton(this, "巡邏菇點找大菇（依距離排序）");
        patrolBtn.setBackground(UIHelper.roundRect(Color.parseColor("#8E24AA"), 14, this));
        LinearLayout.LayoutParams patrolLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        patrolLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        patrolBtn.setLayoutParams(patrolLp);
        patrolBtn.setOnClickListener(v -> showPatrolDialog());
        content.addView(patrolBtn);

        Button bigTourBtn = UIHelper.primaryButton(this, "去打所有已標記的大菇");
        bigTourBtn.setBackground(UIHelper.roundRect(Color.parseColor("#FF6F00"), 14, this));
        LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bigLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        bigTourBtn.setLayoutParams(bigLp);
        bigTourBtn.setOnClickListener(v -> showBigMushroomTourDialog());
        content.addView(bigTourBtn);

        buildOnlineSection(content);

        View listGap = new View(this);
        listGap.setLayoutParams(new LinearLayout.LayoutParams(1, UIHelper.dp(this, 12)));
        content.addView(listGap);
        content.addView(UIHelper.sectionHeader(this, "我的菇點"));

        mushContainer = new LinearLayout(this);
        mushContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mcLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        mushContainer.setLayoutParams(mcLp);
        content.addView(mushContainer);

        scroll.addView(content);
        return scroll;
    }

    private void loadMushrooms() {
        if (mushContainer == null) return;
        mushContainer.removeAllViews();
        List<GpsMockDbHelper.FlowerPot> list = dbHelper.getAllMushrooms();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚無菇點");
            empty.setTextColor(UIHelper.TEXT_HINT);
            empty.setTextSize(13);
            empty.setPadding(0, UIHelper.dp(this, 8), 0, 0);
            mushContainer.addView(empty);
            return;
        }
        // 有大菇的排前面
        List<GpsMockDbHelper.FlowerPot> sorted = new java.util.ArrayList<>(list);
        Collections.sort(sorted, (a, b) -> Boolean.compare(b.hasBigMushroom(), a.hasBigMushroom()));

        TextView count = new TextView(this);
        int big = 0;
        for (GpsMockDbHelper.FlowerPot m : sorted) if (m.hasBigMushroom()) big++;
        count.setText(String.format("共 %d 個菇點，%d 個有大菇", sorted.size(), big));
        count.setTextSize(12);
        count.setTextColor(UIHelper.TEXT_SECONDARY);
        mushContainer.addView(count);

        double[] ref = getReferencePosition();
        for (GpsMockDbHelper.FlowerPot m : sorted) {
            mushContainer.addView(buildMushroomRow(m, ref));
        }
    }

    private View buildMushroomRow(GpsMockDbHelper.FlowerPot m, double[] ref) {
        boolean big = m.hasBigMushroom();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(big
                ? UIHelper.roundRectStroke(UIHelper.BG_CARD, Color.parseColor("#FF6F00"), 12, 2, this)
                : UIHelper.roundRect(UIHelper.BG_CARD, 12, this));
        int pad = UIHelper.dp(this, 12);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 4));
        row.setLayoutParams(rowLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        info.setOnClickListener(v -> showBigMushroomDialog(m));

        TextView name = new TextView(this);
        name.setText(m.corrected ? m.name + "（已修正）" : m.name);
        name.setTextSize(14);
        name.setTextColor(UIHelper.TEXT_PRIMARY);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        StringBuilder sub = new StringBuilder(String.format("%.5f, %.5f", m.lat, m.lng));
        if (ref != null) {
            sub.append(" · ").append(formatDistance(distanceMeters(ref[0], ref[1], m.lat, m.lng)));
        }
        TextView coords = new TextView(this);
        coords.setText(sub.toString());
        coords.setTextSize(12);
        coords.setTextColor(UIHelper.TEXT_SECONDARY);

        info.addView(name);
        info.addView(coords);
        if (big) {
            TextView bigText = new TextView(this);
            bigText.setText("大菇 · 剩 " + formatRemaining(m.expiresAt - System.currentTimeMillis()));
            bigText.setTextSize(12);
            bigText.setTextColor(Color.parseColor("#FF9800"));
            bigText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            info.addView(bigText);
        }
        row.addView(info);

        int gap = UIHelper.dp(this, 6);

        Button goBtn = UIHelper.smallButton(this, "去打", UIHelper.ACCENT_GREEN);
        goBtn.setLayoutParams(presetButtonParams(gap));
        goBtn.setOnClickListener(v -> showGoFightDialog(m));
        row.addView(goBtn);

        Button correctBtn = UIHelper.smallButton(this, "修正", UIHelper.ACCENT_BLUE);
        correctBtn.setLayoutParams(presetButtonParams(gap));
        correctBtn.setOnClickListener(v -> showCorrectFlowerPotDialog(m));
        row.addView(correctBtn);

        Button delBtn = UIHelper.smallButton(this, "刪", UIHelper.ACCENT_RED);
        delBtn.setLayoutParams(presetButtonParams(gap));
        delBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("刪除菇點")
                .setMessage("確定要刪除「" + m.name + "」？")
                .setPositiveButton("刪除", (d, w) -> {
                    dbHelper.deleteFlowerPot(m.id);
                    loadMushrooms();
                })
                .setNegativeButton("取消", null)
                .show());
        row.addView(delBtn);

        return row;
    }

    /** 計算距離用的參考位置：模擬中用模擬位置，否則用實際 GPS；都沒有回傳 null。 */
    private double[] getReferencePosition() {
        if (GpsMockService.isRunning()) {
            return new double[]{GpsMockService.getCurrentLat(), GpsMockService.getCurrentLng()};
        }
        if (hasLocation) {
            return new double[]{currentLat, currentLng};
        }
        return null;
    }

    private static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        float[] r = new float[1];
        Location.distanceBetween(lat1, lng1, lat2, lng2, r);
        return r[0];
    }

    private static String formatDistance(double meters) {
        if (meters >= 1000) {
            return String.format("%.1f km", meters / 1000);
        }
        return String.format("%d m", Math.round(meters));
    }

    private static String formatRemaining(long ms) {
        long minutes = Math.max(0, ms) / 60000;
        if (minutes >= 60) {
            return String.format("%d 小時 %d 分", minutes / 60, minutes % 60);
        }
        return minutes + " 分";
    }

    // ---- 家 ----

    private double[] getHome() {
        String lat = dbHelper.getSetting(GpsMockDbHelper.KEY_HOME_LAT, null);
        String lng = dbHelper.getSetting(GpsMockDbHelper.KEY_HOME_LNG, null);
        if (lat == null || lng == null) return null;
        try {
            return new double[]{Double.parseDouble(lat), Double.parseDouble(lng)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void updateHomeText() {
        double[] home = getHome();
        homeText.setText(home == null
                ? "家：未設定（打完不會自動跳回）"
                : String.format("家：%.5f, %.5f", home[0], home[1]));
    }

    private void showSetHomeDialog() {
        EditText input = new EditText(this);
        input.setHint("lat, lng 或 Google 地圖網址");
        double[] home = getHome();
        if (home != null) {
            input.setText(String.format("%.6f, %.6f", home[0], home[1]));
        } else if (hasLocation) {
            input.setText(String.format("%.6f, %.6f", currentLat, currentLng));
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("設定家（巡邏/打完回到這裡）")
                .setView(input)
                .setPositiveButton("儲存", (d, w) -> {
                    double[] ll = parseLatLng(input.getText().toString());
                    if (ll == null) {
                        Toast.makeText(this, "座標格式無法解析", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dbHelper.setSetting(GpsMockDbHelper.KEY_HOME_LAT, String.valueOf(ll[0]));
                    dbHelper.setSetting(GpsMockDbHelper.KEY_HOME_LNG, String.valueOf(ll[1]));
                    updateHomeText();
                })
                .setNegativeButton("取消", null);
        if (hasLocation) {
            b.setNeutralButton("用目前 GPS", (d, w) -> {
                dbHelper.setSetting(GpsMockDbHelper.KEY_HOME_LAT, String.valueOf(currentLat));
                dbHelper.setSetting(GpsMockDbHelper.KEY_HOME_LNG, String.valueOf(currentLng));
                updateHomeText();
            });
        }
        b.show();
    }

    // ---- 記錄 / 新增 / 匯入 / 標記大菇 ----

    private void recordCurrentSpot() {
        double[] ref = getReferencePosition();
        if (ref == null) {
            Toast.makeText(this, "尚未取得位置", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText nameInput = new EditText(this);
        nameInput.setHint("名稱（可留空，例：公園入口）");
        new AlertDialog.Builder(this)
                .setTitle(String.format("記錄菇點 %.5f, %.5f", ref[0], ref[1]))
                .setView(nameInput)
                .setPositiveButton("記錄", (d, w) -> {
                    String nm = nameInput.getText().toString().trim();
                    if (nm.isEmpty()) {
                        nm = "菇點 " + new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                                .format(new java.util.Date());
                    }
                    dbHelper.insertFlowerPot(nm, ref[0], ref[1], GpsMockDbHelper.CATEGORY_MUSHROOM);
                    Toast.makeText(this, "已記錄菇點", Toast.LENGTH_SHORT).show();
                    loadMushrooms();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddMushroomDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = UIHelper.dp(this, 16);
        col.setPadding(p, 0, p, 0);

        EditText nameInput = new EditText(this);
        nameInput.setHint("名稱（例：公園入口）");
        col.addView(nameInput);

        EditText coordInput = new EditText(this);
        coordInput.setHint("lat, lng 或 Google 地圖網址");
        col.addView(coordInput);

        new AlertDialog.Builder(this)
                .setTitle("新增菇點")
                .setView(col)
                .setPositiveButton("新增", (d, w) -> {
                    double[] ll = parseLatLng(coordInput.getText().toString());
                    if (ll == null) {
                        Toast.makeText(this, "座標格式無法解析", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String nm = nameInput.getText().toString().trim();
                    if (nm.isEmpty()) nm = String.format("菇點 %.4f, %.4f", ll[0], ll[1]);
                    dbHelper.insertFlowerPot(nm, ll[0], ll[1], GpsMockDbHelper.CATEGORY_MUSHROOM);
                    loadMushrooms();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showImportMushroomsDialog() {
        EditText input = new EditText(this);
        input.setHint("每行一筆，含座標即可（道館/補給站座標也可）\n例: 公園入口 25.04852, 121.51419");
        input.setMinLines(5);
        input.setGravity(Gravity.TOP);

        new AlertDialog.Builder(this)
                .setTitle("匯入菇點座標")
                .setView(input)
                .setPositiveButton("匯入", (d, w) -> {
                    int count = 0;
                    for (String line : input.getText().toString().split("\\n")) {
                        if (line.trim().isEmpty()) continue;
                        double[] ll = parseLatLng(line);
                        if (ll == null) continue;
                        String nm = line.replaceAll("-?\\d{1,3}\\.\\d+", "").replaceAll("[,;|\\s]+", " ").trim();
                        if (nm.isEmpty()) nm = String.format("菇點 %.4f, %.4f", ll[0], ll[1]);
                        dbHelper.insertFlowerPot(nm, ll[0], ll[1], GpsMockDbHelper.CATEGORY_MUSHROOM);
                        count++;
                    }
                    Toast.makeText(this, "已匯入 " + count + " 個菇點", Toast.LENGTH_SHORT).show();
                    loadMushrooms();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private double getBigHours() {
        try {
            return Double.parseDouble(dbHelper.getSetting(GpsMockDbHelper.KEY_BIG_HOURS, "2"));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    /** 手動標記/取消大菇；輸入的時數會記住，作為通知「標記大菇」的預設時數。 */
    private void showBigMushroomDialog(GpsMockDbHelper.FlowerPot m) {
        EditText hours = new EditText(this);
        hours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        hours.setHint("大菇還剩幾小時");
        double h0 = getBigHours();
        hours.setText(h0 == Math.floor(h0) ? String.valueOf((long) h0) : String.valueOf(h0));
        hours.setSelectAllOnFocus(true);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("「" + m.name + "」有大菇")
                .setView(hours)
                .setPositiveButton("標記大菇", (d, w) -> {
                    try {
                        double h = Double.parseDouble(hours.getText().toString().trim());
                        if (h <= 0) throw new NumberFormatException();
                        dbHelper.setSetting(GpsMockDbHelper.KEY_BIG_HOURS, String.valueOf(h));
                        dbHelper.setExpiresAt(m.id, System.currentTimeMillis() + Math.round(h * 3600_000));
                        loadMushrooms();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "請輸入有效時數", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null);
        if (m.hasBigMushroom()) {
            b.setNeutralButton("沒有大菇了", (d, w) -> {
                dbHelper.setExpiresAt(m.id, 0);
                loadMushrooms();
            });
        }
        b.show();
    }

    // ---- 剪貼簿座標（從菇點地圖網站複製座標後切回 App） ----

    private String lastClipText; // 已處理過的剪貼簿內容，避免重複詢問

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Android 10+ 只有取得焦點的 App 能讀剪貼簿，所以在這裡而非 onResume 讀取
        if (hasFocus) {
            checkClipboardForCoords(false);
        }
    }

    /** 讀取剪貼簿；含座標時詢問要去打或儲存。manual=true 時即使處理過也再問一次。 */
    private void checkClipboardForCoords(boolean manual) {
        String text = null;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (cs != null) text = cs.toString().trim();
            }
        } catch (Exception e) {
            Log.w(TAG, "Read clipboard failed: " + e.getMessage());
        }
        if (text == null || text.isEmpty() || text.length() > 500) {
            if (manual) Toast.makeText(this, "剪貼簿沒有座標", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!manual && text.equals(lastClipText)) return;
        double[] ll = parseLatLng(text);
        if (ll == null) {
            if (manual) Toast.makeText(this, "剪貼簿沒有座標", Toast.LENGTH_SHORT).show();
            return;
        }
        lastClipText = text;
        String label = text.replaceAll("-?\\d{1,3}\\.\\d+", "").replaceAll("[,;|\\s]+", " ").trim();
        showClipCoordsDialog(ll, label);
    }

    private void showClipCoordsDialog(double[] ll, String label) {
        selectTab(2);
        GpsMockDbHelper.FlowerPot existing = findNearbySpot(ll[0], ll[1], 30);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = UIHelper.dp(this, 16);
        col.setPadding(p, UIHelper.dp(this, 8), p, 0);

        TextView info = new TextView(this);
        double[] ref = getReferencePosition();
        String dist = ref != null ? "，距離 " + formatDistance(distanceMeters(ref[0], ref[1], ll[0], ll[1])) : "";
        info.setText(String.format("%.6f, %.6f%s", ll[0], ll[1], dist)
                + (existing != null ? "\n（已存在菇點「" + existing.name + "」）" : ""));
        info.setTextColor(UIHelper.TEXT_SECONDARY);
        col.addView(info);

        EditText nameInput = new EditText(this);
        nameInput.setHint("名稱（可留空）");
        if (existing != null) {
            nameInput.setText(existing.name);
        } else if (!label.isEmpty()) {
            nameInput.setText(label);
        }
        col.addView(nameInput);

        new AlertDialog.Builder(this)
                .setTitle("偵測到剪貼簿座標")
                .setView(col)
                .setPositiveButton("去打", (d, w) -> {
                    GpsMockDbHelper.FlowerPot spot = saveClipSpot(ll, nameInput.getText().toString(), existing);
                    showGoFightDialog(spot);
                })
                .setNeutralButton("只存成大菇", (d, w) -> {
                    saveClipSpot(ll, nameInput.getText().toString(), existing);
                    Toast.makeText(this, "已存成大菇", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 儲存（或沿用附近既有的）菇點並標記為大菇，回傳該菇點。 */
    private GpsMockDbHelper.FlowerPot saveClipSpot(double[] ll, String name,
                                                    GpsMockDbHelper.FlowerPot existing) {
        long expiresAt = System.currentTimeMillis() + Math.round(getBigHours() * 3600_000);
        GpsMockDbHelper.FlowerPot spot;
        if (existing != null) {
            spot = existing;
        } else {
            String nm = name.trim();
            if (nm.isEmpty()) nm = String.format("大菇 %.4f, %.4f", ll[0], ll[1]);
            spot = new GpsMockDbHelper.FlowerPot();
            spot.id = dbHelper.insertFlowerPot(nm, ll[0], ll[1], GpsMockDbHelper.CATEGORY_MUSHROOM);
            spot.name = nm;
            spot.lat = ll[0];
            spot.lng = ll[1];
        }
        dbHelper.setExpiresAt(spot.id, expiresAt);
        spot.expiresAt = expiresAt;
        loadMushrooms();
        return spot;
    }

    /** 找出 radiusM 公尺內最近的菇點，沒有回傳 null。 */
    private GpsMockDbHelper.FlowerPot findNearbySpot(double lat, double lng, double radiusM) {
        GpsMockDbHelper.FlowerPot best = null;
        double bestDist = radiusM;
        for (GpsMockDbHelper.FlowerPot m : dbHelper.getAllMushrooms()) {
            double dist = distanceMeters(lat, lng, m.lat, m.lng);
            if (dist <= bestDist) {
                bestDist = dist;
                best = m;
            }
        }
        return best;
    }

    // ---- 線上大菇（資料：樹懶生活 treelazy.com/pikmushroom） ----
    // 資料是對方公開的靜態檔，依其條款註明出處；只在使用者按下時抓取，5 分鐘內不重抓，避免干擾對方網站。

    private static final String ONLINE_MUSHROOM_URL = "https://treelazy.com/pikmushroom/data/mushrooms.json";
    private static final String ONLINE_MUSHROOM_PAGE = "https://treelazy.com/pikmushroom";
    private static final long ONLINE_MIN_REFRESH_MS = 5 * 60 * 1000;
    private static final int ONLINE_MAX_ROWS = 60;

    private static final String[] REGION_KEYS = {"", "auckland", "melaka", "suva", "apia", "nukualofa"};
    private static final String[] REGION_LABELS = {"全部地區", "奧克蘭（紐西蘭）", "麻六甲（馬來西亞）",
            "蘇瓦（斐濟）", "阿皮亞（薩摩亞）", "努瓜婁發（東加）"};

    private static class OnlineMushroom {
        double lat, lng;
        String type, size, poi, region;
        long verifiedAt;   // epoch ms，0 表示未知
        long nextResetAt;  // 該地區下次重置（epoch ms），作為大菇到期時間
        boolean fresh;     // 本輪（上次重置後）掃到的
        boolean battle;    // 掃到當下有人在打
    }

    private final List<OnlineMushroom> onlineMushrooms = new java.util.ArrayList<>();
    private long onlineFetchedAt = 0;
    private boolean onlineBigOnly = true;
    private boolean onlineFreshOnly = true;
    private int onlineRegionIdx = 0;
    private LinearLayout onlineContainer;
    private TextView onlineStatusText;
    private Button onlineSizeBtn, onlineFreshBtn, onlineRegionBtn;

    private void buildOnlineSection(LinearLayout content) {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(1, UIHelper.dp(this, 12)));
        content.addView(gap);
        content.addView(UIHelper.sectionHeader(this, "線上大菇地圖"));

        TextView src = new TextView(this);
        src.setText("資料來源：樹懶生活 treelazy.com/pikmushroom（非官方、定期掃描、非即時）。"
                + "目前只涵蓋紐西蘭、馬來西亞麻六甲、斐濟、薩摩亞、東加。點此開啟原網站。");
        src.setTextSize(12);
        src.setTextColor(UIHelper.TEXT_HINT);
        src.setPadding(0, 0, 0, UIHelper.dp(this, 8));
        src.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ONLINE_MUSHROOM_PAGE))));
        content.addView(src);

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        onlineSizeBtn = UIHelper.smallButton(this, "", UIHelper.ACCENT_BLUE);
        onlineSizeBtn.setOnClickListener(v -> {
            onlineBigOnly = !onlineBigOnly;
            renderOnlineMushrooms();
        });
        filterRow.addView(onlineSizeBtn);
        onlineFreshBtn = UIHelper.smallButton(this, "", UIHelper.ACCENT_BLUE);
        onlineFreshBtn.setLayoutParams(presetButtonParams(UIHelper.dp(this, 8)));
        onlineFreshBtn.setOnClickListener(v -> {
            onlineFreshOnly = !onlineFreshOnly;
            renderOnlineMushrooms();
        });
        filterRow.addView(onlineFreshBtn);
        onlineRegionBtn = UIHelper.smallButton(this, "", UIHelper.ACCENT_BLUE);
        onlineRegionBtn.setLayoutParams(presetButtonParams(UIHelper.dp(this, 8)));
        onlineRegionBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("選擇地區")
                .setItems(REGION_LABELS, (d, w) -> {
                    onlineRegionIdx = w;
                    renderOnlineMushrooms();
                })
                .show());
        filterRow.addView(onlineRegionBtn);
        content.addView(filterRow);

        Button loadBtn = UIHelper.primaryButton(this, "載入 / 重新整理線上大菇");
        loadBtn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_BLUE, 14, this));
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loadLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        loadBtn.setLayoutParams(loadLp);
        loadBtn.setOnClickListener(v -> fetchOnlineMushrooms());
        content.addView(loadBtn);

        onlineStatusText = new TextView(this);
        onlineStatusText.setTextSize(12);
        onlineStatusText.setTextColor(UIHelper.TEXT_SECONDARY);
        onlineStatusText.setPadding(0, UIHelper.dp(this, 6), 0, 0);
        content.addView(onlineStatusText);

        onlineContainer = new LinearLayout(this);
        onlineContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(onlineContainer);

        renderOnlineMushrooms();
    }

    private void fetchOnlineMushrooms() {
        long now = System.currentTimeMillis();
        if (!onlineMushrooms.isEmpty() && now - onlineFetchedAt < ONLINE_MIN_REFRESH_MS) {
            Toast.makeText(this, "資料 5 分鐘內剛載入過，先用目前的", Toast.LENGTH_SHORT).show();
            renderOnlineMushrooms();
            return;
        }
        onlineStatusText.setText("載入中...");
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(ONLINE_MUSHROOM_URL).openConnection();
                conn.setRequestProperty("User-Agent", "GpsMocker/" + getCurrentVersionName() + " Android");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                if (conn.getResponseCode() != 200) {
                    throw new Exception("HTTP " + conn.getResponseCode());
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                List<OnlineMushroom> parsed = parseOnlineMushrooms(new JSONObject(sb.toString()));
                runOnUiThread(() -> {
                    onlineMushrooms.clear();
                    onlineMushrooms.addAll(parsed);
                    onlineFetchedAt = System.currentTimeMillis();
                    renderOnlineMushrooms();
                });
            } catch (Exception e) {
                Log.e(TAG, "Fetch online mushrooms failed: " + e.getMessage());
                runOnUiThread(() -> onlineStatusText.setText("載入失敗：" + e.getMessage()));
            }
        }).start();
    }

    private static long parseIsoTime(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return 0;
        try {
            return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 依 region_resets 時刻表（或 region_reset_hour 後備）算出該地區上次與下次重置時刻。 */
    private static long[] resetWindow(JSONObject data, String region, long now) {
        JSONObject resets = data.optJSONObject("region_resets");
        JSONArray ts = resets != null ? resets.optJSONArray(region) : null;
        if (ts == null && resets != null) ts = resets.optJSONArray(data.optString("reset_region", "auckland"));
        long last = 0, next = 0;
        if (ts != null) {
            for (int i = 0; i < ts.length(); i++) {
                long t = parseIsoTime(ts.optString(i));
                if (t <= now) last = Math.max(last, t);
                else if (next == 0 || t < next) next = t;
            }
        }
        if (last == 0 || next == 0) {
            // 時刻表過期時退回「每天 UTC 固定小時」推算
            JSONObject hours = data.optJSONObject("region_reset_hour");
            int hh = hours != null && hours.has(region) ? hours.optInt(region) : 14;
            java.time.ZonedDateTime n = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC);
            java.time.ZonedDateTime r = n.toLocalDate().atTime(hh, 0).atZone(java.time.ZoneOffset.UTC);
            if (n.isBefore(r)) r = r.minusDays(1);
            last = r.toInstant().toEpochMilli();
            next = r.plusDays(1).toInstant().toEpochMilli();
        }
        return new long[]{last, next};
    }

    private static List<OnlineMushroom> parseOnlineMushrooms(JSONObject data) throws Exception {
        long now = System.currentTimeMillis();
        java.util.Map<String, long[]> windows = new java.util.HashMap<>();
        JSONArray arr = data.getJSONArray("mushrooms");
        List<OnlineMushroom> list = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (!o.has("lat") || !o.has("lon")) continue;
            OnlineMushroom m = new OnlineMushroom();
            m.lat = o.getDouble("lat");
            m.lng = o.getDouble("lon");
            m.type = o.optString("type", "蘑菇");
            m.size = o.optString("size", "");
            m.poi = o.optString("poi", "");
            m.region = o.optString("region", "auckland");
            m.verifiedAt = parseIsoTime(o.optString("verified_at", ""));
            m.battle = !o.isNull("battle_ends_at") && !o.optString("battle_ends_at", "").isEmpty();
            long[] w = windows.get(m.region);
            if (w == null) {
                w = resetWindow(data, m.region, now);
                windows.put(m.region, w);
            }
            m.fresh = m.verifiedAt > 0 && m.verifiedAt >= w[0];
            m.nextResetAt = w[1];
            list.add(m);
        }
        // 本輪在前、空閒在前、最新發現在前
        Collections.sort(list, (a, b) -> {
            if (a.fresh != b.fresh) return a.fresh ? -1 : 1;
            if (a.battle != b.battle) return a.battle ? 1 : -1;
            return Long.compare(b.verifiedAt, a.verifiedAt);
        });
        return list;
    }

    private void renderOnlineMushrooms() {
        if (onlineContainer == null) return;
        onlineSizeBtn.setText(onlineBigOnly ? "只看大/巨大" : "全部大小");
        onlineFreshBtn.setText(onlineFreshOnly ? "只看本輪" : "含上一輪");
        onlineRegionBtn.setText(REGION_LABELS[onlineRegionIdx]);
        onlineContainer.removeAllViews();
        if (onlineMushrooms.isEmpty()) {
            if (onlineFetchedAt == 0) onlineStatusText.setText("尚未載入，按上方按鈕載入");
            return;
        }
        String region = REGION_KEYS[onlineRegionIdx];
        List<OnlineMushroom> shown = new java.util.ArrayList<>();
        for (OnlineMushroom m : onlineMushrooms) {
            if (onlineBigOnly && !("大".equals(m.size) || "巨大".equals(m.size))) continue;
            if (onlineFreshOnly && !m.fresh) continue;
            if (!region.isEmpty() && !region.equals(m.region)) continue;
            shown.add(m);
        }
        String time = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(onlineFetchedAt));
        onlineStatusText.setText(String.format("%s 載入，共 %d 朵，符合條件 %d 朵%s", time, onlineMushrooms.size(),
                shown.size(), shown.size() > ONLINE_MAX_ROWS ? "（顯示前 " + ONLINE_MAX_ROWS + " 朵）" : ""));
        for (int i = 0; i < shown.size() && i < ONLINE_MAX_ROWS; i++) {
            onlineContainer.addView(buildOnlineRow(shown.get(i)));
        }
    }

    private String regionLabel(String key) {
        for (int i = 1; i < REGION_KEYS.length; i++) {
            if (REGION_KEYS[i].equals(key)) return REGION_LABELS[i];
        }
        return key;
    }

    private View buildOnlineRow(OnlineMushroom m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UIHelper.roundRect(UIHelper.BG_CARD, 12, this));
        int pad = UIHelper.dp(this, 12);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 4));
        row.setLayoutParams(rowLp);
        if (!m.fresh) row.setAlpha(0.6f);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView title = new TextView(this);
        title.setText(m.type + (m.size.isEmpty() ? "" : " · " + m.size));
        title.setTextSize(14);
        title.setTextColor("巨大".equals(m.size) || "大".equals(m.size)
                ? Color.parseColor("#FF9800") : UIHelper.TEXT_PRIMARY);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        info.addView(title);

        TextView poi = new TextView(this);
        poi.setText((m.poi.isEmpty() ? "" : m.poi + "\n") + regionLabel(m.region));
        poi.setTextSize(12);
        poi.setTextColor(UIHelper.TEXT_SECONDARY);
        info.addView(poi);

        TextView state = new TextView(this);
        String found = m.verifiedAt > 0
                ? new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new java.util.Date(m.verifiedAt))
                : "未知";
        state.setText(String.format("%s · 發現 %s · %s", m.fresh ? "本輪" : "上一輪", found,
                m.battle ? "掃到時戰鬥中" : "掃到時空閒"));
        state.setTextSize(12);
        state.setTextColor(m.battle ? UIHelper.ACCENT_RED : UIHelper.ACCENT_GREEN);
        info.addView(state);
        row.addView(info);

        int gap = UIHelper.dp(this, 6);
        Button goBtn = UIHelper.smallButton(this, "去打", UIHelper.ACCENT_GREEN);
        goBtn.setLayoutParams(presetButtonParams(gap));
        goBtn.setOnClickListener(v -> showGoFightDialog(saveOnlineSpot(m)));
        row.addView(goBtn);

        Button saveBtn = UIHelper.smallButton(this, "存", UIHelper.ACCENT_BLUE);
        saveBtn.setLayoutParams(presetButtonParams(gap));
        saveBtn.setOnClickListener(v -> {
            saveOnlineSpot(m);
            Toast.makeText(this, "已存到我的菇點（標記大菇到該地區重置）", Toast.LENGTH_SHORT).show();
        });
        row.addView(saveBtn);
        return row;
    }

    /** 存成（或沿用附近既有的）菇點，標記大菇到該地區下次重置，回傳該菇點。 */
    private GpsMockDbHelper.FlowerPot saveOnlineSpot(OnlineMushroom m) {
        GpsMockDbHelper.FlowerPot spot = findNearbySpot(m.lat, m.lng, 30);
        if (spot == null) {
            String nm = m.type + (m.size.isEmpty() ? "" : "·" + m.size) + (m.poi.isEmpty() ? "" : " " + m.poi);
            spot = new GpsMockDbHelper.FlowerPot();
            spot.id = dbHelper.insertFlowerPot(nm, m.lat, m.lng, GpsMockDbHelper.CATEGORY_MUSHROOM);
            spot.name = nm;
            spot.lat = m.lat;
            spot.lng = m.lng;
        }
        long expiresAt = m.nextResetAt > System.currentTimeMillis()
                ? m.nextResetAt
                : System.currentTimeMillis() + Math.round(getBigHours() * 3600_000);
        dbHelper.setExpiresAt(spot.id, expiresAt);
        spot.expiresAt = expiresAt;
        loadMushrooms();
        return spot;
    }

    // ---- 巡邏 / 去打 ----

    /** 停留秒數 + 回家 的共用對話框內容。 */
    private static class StayOptions {
        EditText staySec;
        CheckBox returnHome;
    }

    private int getIntSetting(String key, int def) {
        try {
            return Integer.parseInt(dbHelper.getSetting(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private LinearLayout buildStayOptionsView(StayOptions opts, String label, int defSec, String homeLabel) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = UIHelper.dp(this, 16);
        col.setPadding(p, UIHelper.dp(this, 8), p, 0);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(UIHelper.TEXT_SECONDARY);
        col.addView(labelView);

        opts.staySec = new EditText(this);
        opts.staySec.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        opts.staySec.setText(String.valueOf(defSec));
        opts.staySec.setSelectAllOnFocus(true);
        col.addView(opts.staySec);

        opts.returnHome = new CheckBox(this);
        opts.returnHome.setButtonTintList(android.content.res.ColorStateList.valueOf(UIHelper.ACCENT_GREEN));
        boolean hasHome = getHome() != null;
        opts.returnHome.setText(hasHome ? homeLabel : homeLabel + "（請先設定家）");
        opts.returnHome.setChecked(hasHome);
        opts.returnHome.setEnabled(hasHome);
        col.addView(opts.returnHome);
        return col;
    }

    /** 讀取停留秒數（1～600）並記住到 key；格式錯誤回傳 -1。 */
    private int readStaySec(StayOptions opts, String key) {
        try {
            int sec = Integer.parseInt(opts.staySec.getText().toString().trim());
            sec = Math.max(1, Math.min(600, sec));
            dbHelper.setSetting(key, String.valueOf(sec));
            return sec;
        } catch (NumberFormatException e) {
            Toast.makeText(this, "請輸入有效秒數", Toast.LENGTH_SHORT).show();
            return -1;
        }
    }

    private void showPatrolDialog() {
        List<GpsMockDbHelper.FlowerPot> spots = dbHelper.getAllMushrooms();
        if (spots.isEmpty()) {
            Toast.makeText(this, "尚無菇點，先記錄或匯入菇點", Toast.LENGTH_SHORT).show();
            return;
        }
        if (GpsMockService.isRunning()) {
            Toast.makeText(this, "請先停止目前的模擬", Toast.LENGTH_SHORT).show();
            return;
        }
        double[] home = getHome();
        List<GpsMockDbHelper.FlowerPot> ordered = sortByNearest(spots, home != null ? home : getReferencePosition());

        StayOptions opts = new StayOptions();
        LinearLayout view = buildStayOptionsView(opts, "每個菇點停留秒數（看有沒有大菇）",
                getIntSetting(GpsMockDbHelper.KEY_PATROL_STAY_SEC, 15), "巡邏完跳回家");
        TextView tip = new TextView(this);
        tip.setText("看到大菇時拉下通知按「標記大菇」，巡邏會暫停；加入後按「繼續」。");
        tip.setTextSize(12);
        tip.setTextColor(UIHelper.TEXT_HINT);
        tip.setPadding(0, UIHelper.dp(this, 8), 0, 0);
        view.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle("巡邏 " + ordered.size() + " 個菇點")
                .setView(view)
                .setPositiveButton("開始巡邏", (d, w) -> {
                    int sec = readStaySec(opts, GpsMockDbHelper.KEY_PATROL_STAY_SEC);
                    if (sec < 0) return;
                    startSpotTour(ordered, sec, opts.returnHome.isChecked());
                    Toast.makeText(this, String.format("開始巡邏 %d 個菇點（每點 %d 秒）", ordered.size(), sec),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showGoFightDialog(GpsMockDbHelper.FlowerPot m) {
        StayOptions opts = new StayOptions();
        new AlertDialog.Builder(this)
                .setTitle("去打「" + m.name + "」")
                .setView(buildStayOptionsView(opts, "停留秒數（加入戰鬥用）",
                        getIntSetting(GpsMockDbHelper.KEY_MUSHROOM_STAY_SEC, 60), "打完自動跳回家"))
                .setPositiveButton("出發", (d, w) -> {
                    int sec = readStaySec(opts, GpsMockDbHelper.KEY_MUSHROOM_STAY_SEC);
                    if (sec < 0) return;
                    List<GpsMockDbHelper.FlowerPot> one = new java.util.ArrayList<>();
                    one.add(m);
                    boolean home = opts.returnHome.isChecked();
                    startSpotTour(one, sec, home);
                    Toast.makeText(this, String.format("前往「%s」停留 %d 秒%s", m.name, sec, home ? "後回家" : ""),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showBigMushroomTourDialog() {
        List<GpsMockDbHelper.FlowerPot> bigs = new java.util.ArrayList<>();
        for (GpsMockDbHelper.FlowerPot m : dbHelper.getAllMushrooms()) {
            if (m.hasBigMushroom()) bigs.add(m);
        }
        if (bigs.isEmpty()) {
            Toast.makeText(this, "目前沒有標記大菇，先巡邏菇點", Toast.LENGTH_SHORT).show();
            return;
        }
        if (GpsMockService.isRunning()) {
            Toast.makeText(this, "請先停止目前的模擬", Toast.LENGTH_SHORT).show();
            return;
        }
        double[] home = getHome();
        List<GpsMockDbHelper.FlowerPot> ordered = sortByNearest(bigs, home != null ? home : getReferencePosition());

        StayOptions opts = new StayOptions();
        new AlertDialog.Builder(this)
                .setTitle("去打 " + ordered.size() + " 個大菇")
                .setView(buildStayOptionsView(opts, "每朵停留秒數（加入戰鬥用）",
                        getIntSetting(GpsMockDbHelper.KEY_MUSHROOM_STAY_SEC, 60), "打完自動跳回家"))
                .setPositiveButton("出發", (d, w) -> {
                    int sec = readStaySec(opts, GpsMockDbHelper.KEY_MUSHROOM_STAY_SEC);
                    if (sec < 0) return;
                    startSpotTour(ordered, sec, opts.returnHome.isChecked());
                    Toast.makeText(this, String.format("開始打 %d 個大菇（每朵 %d 秒）", ordered.size(), sec),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 由起點開始，每次挑最近的下一點（最近鄰排序），減少大跨度跳躍。start 為 null 時維持原順序。 */
    private static List<GpsMockDbHelper.FlowerPot> sortByNearest(
            List<GpsMockDbHelper.FlowerPot> src, double[] start) {
        List<GpsMockDbHelper.FlowerPot> remaining = new java.util.ArrayList<>(src);
        if (start == null) return remaining;
        List<GpsMockDbHelper.FlowerPot> result = new java.util.ArrayList<>();
        double lat = start[0], lng = start[1];
        while (!remaining.isEmpty()) {
            int best = 0;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                GpsMockDbHelper.FlowerPot m = remaining.get(i);
                double dist = distanceMeters(lat, lng, m.lat, m.lng);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
            GpsMockDbHelper.FlowerPot next = remaining.remove(best);
            result.add(next);
            lat = next.lat;
            lng = next.lng;
        }
        return result;
    }

    /** 依序跳到每個菇點停留 staySec 秒；returnHome 時最後跳回家並持續停留。通知會帶有菇點 id 供「標記大菇」使用。 */
    private void startSpotTour(List<GpsMockDbHelper.FlowerPot> list, int staySec, boolean returnHome) {
        double[] home = returnHome ? getHome() : null;
        int n = list.size() + (home != null ? 1 : 0);
        double[] lats = new double[n];
        double[] lngs = new double[n];
        long[] stayMs = new long[n];
        long[] ids = new long[n];
        for (int i = 0; i < list.size(); i++) {
            lats[i] = list.get(i).lat;
            lngs[i] = list.get(i).lng;
            stayMs[i] = staySec * 1000L;
            ids[i] = list.get(i).id;
        }
        if (home != null) {
            lats[n - 1] = home[0];
            lngs[n - 1] = home[1];
            stayMs[n - 1] = 1000L;
            ids[n - 1] = -1;
        }
        startJumpTour(lats, lngs, stayMs, ids);
    }

    // ===================== 步數寫入（Health Connect） =====================

    private void updateStepHourButtons() {
        double[] options = {1, 2, 3, 4, 6, 8};
        boolean matched = false;
        for (int i = 0; i < options.length; i++) {
            boolean sel = stepHours == options[i];
            if (sel) matched = true;
            styleStepHourBtn(stepHourBtns[i], sel);
        }
        // 自訂按鈕：沒有匹配預設值時高亮
        styleStepHourBtn(stepHourBtns[6], !matched);
        if (!matched) {
            String label = stepHours == (int) stepHours
                    ? (int) stepHours + "h" : String.format("%.1fh", stepHours);
            stepHourBtns[6].setText(label);
        } else {
            stepHourBtns[6].setText("自訂");
        }
    }

    private void styleStepHourBtn(Button btn, boolean selected) {
        if (selected) {
            btn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_BLUE, 10, this));
            btn.setTextColor(Color.WHITE);
        } else {
            btn.setBackground(UIHelper.roundRectStroke(
                    Color.TRANSPARENT, UIHelper.ACCENT_BLUE, 10, 1, this));
            btn.setTextColor(UIHelper.ACCENT_BLUE);
        }
    }

    private void updateStepEstimate() {
        long total = Math.round(stepHours * stepRate);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime start = now.minusMinutes(Math.round(stepHours * 60));
        String timeRange = String.format("%02d:%02d ~ %02d:%02d",
                start.getHour(), start.getMinute(), now.getHour(), now.getMinute());
        stepEstimateText.setText(String.format("預估總步數：%,d 步\n時間範圍：%s", total, timeRange));
    }

    private void showCustomStepHoursDialog() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("小時數（例如 1.5）");
        input.setText(String.valueOf(stepHours));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("自訂散步時數")
                .setView(input)
                .setPositiveButton("確定", (d, w) -> {
                    try {
                        double h = Double.parseDouble(input.getText().toString().trim());
                        if (h < 0.5) h = 0.5;
                        if (h > 24) h = 24;
                        stepHours = h;
                        updateStepHourButtons();
                        updateStepEstimate();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "請輸入有效數字", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void onWriteStepsClick() {
        if (!StepWriter.isAvailable(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要 Health Connect")
                    .setMessage("請先安裝 Health Connect（Google 健康數據）應用程式。")
                    .setPositiveButton("前往安裝", (d, w) -> {
                        try {
                            startActivity(StepWriter.getInstallIntent());
                        } catch (Exception e) {
                            Toast.makeText(this, "無法開啟商店", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        stepWriteBtn.setEnabled(false);
        stepStatusText.setText("檢查權限中...");
        stepStatusText.setTextColor(UIHelper.TEXT_SECONDARY);

        StepWriter.checkPermissions(this, granted -> {
            if (granted) {
                performWriteSteps();
            } else {
                stepStatusText.setText("請授予步數寫入權限");
                stepWriteBtn.setEnabled(true);
                healthPermLauncher.launch(StepWriter.REQUIRED_PERMISSIONS);
            }
        });
    }

    private void performWriteSteps() {
        long totalSteps = Math.round(stepHours * stepRate);
        if (totalSteps <= 0) {
            Toast.makeText(this, "步數必須大於 0", Toast.LENGTH_SHORT).show();
            stepWriteBtn.setEnabled(true);
            return;
        }

        stepWriteBtn.setEnabled(false);
        stepStatusText.setText("寫入中...");
        stepStatusText.setTextColor(UIHelper.ACCENT_BLUE);

        StepWriter.writeSteps(this, totalSteps, stepHours, new StepWriter.Callback() {
            @Override
            public void onSuccess(long steps) {
                stepWriteBtn.setEnabled(true);
                stepStatusText.setText(String.format("已寫入 %,d 步", steps));
                stepStatusText.setTextColor(UIHelper.ACCENT_GREEN);
                Log.i(TAG, "Steps written: " + steps);
            }

            @Override
            public void onError(String message) {
                stepWriteBtn.setEnabled(true);
                stepStatusText.setText("寫入失敗: " + message);
                stepStatusText.setTextColor(UIHelper.ACCENT_RED);
                Log.e(TAG, "Step write error: " + message);
            }
        });
    }

    // ===================== 自主更新（GitHub Release） =====================

    private String getCurrentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 從 tag 取出純版本號：去掉開頭 v、去掉 '-' 之後的後綴（如 v1.1-c2 -> 1.1）。 */
    private static String parseVersion(String tag) {
        if (tag == null) return "0";
        String t = tag.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            t = t.substring(1);
        }
        int dash = t.indexOf('-');
        if (dash >= 0) {
            t = t.substring(0, dash);
        }
        return t.isEmpty() ? "0" : t;
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? safeInt(pa[i]) : 0;
            int vb = i < pb.length ? safeInt(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void checkForUpdate(boolean manual) {
        if (manual) {
            Toast.makeText(this, "檢查更新中...", Toast.LENGTH_SHORT).show();
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "GpsMocker-Android");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                if (conn.getResponseCode() != 200) {
                    if (manual) {
                        runOnUiThread(() -> Toast.makeText(this, "檢查更新失敗", Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                JSONObject json = new JSONObject(sb.toString());
                String latest = parseVersion(json.optString("tag_name"));
                String current = getCurrentVersionName();

                String apkUrl = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.optString("name").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url");
                            break;
                        }
                    }
                }

                final String fApkUrl = apkUrl;
                if (compareVersions(latest, current) > 0 && fApkUrl != null) {
                    runOnUiThread(() -> showUpdateDialog(latest, current, fApkUrl));
                } else if (manual) {
                    runOnUiThread(() -> Toast.makeText(this, "已是最新版本 (v" + current + ")", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Update check failed: " + e.getMessage());
                if (manual) {
                    runOnUiThread(() -> Toast.makeText(this, "檢查更新失敗: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    private void showUpdateDialog(String latest, String current, String apkUrl) {
        new AlertDialog.Builder(this)
                .setTitle("發現新版本")
                .setMessage("最新版本: v" + latest + "\n目前版本: v" + current + "\n\n是否下載並安裝？")
                .setPositiveButton("下載並安裝", (d, w) -> downloadAndInstall(apkUrl))
                .setNegativeButton("稍後", null)
                .show();
    }

    private void downloadAndInstall(String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要安裝權限")
                    .setMessage("請允許本 App 安裝未知來源應用，然後再按一次「檢查更新」。")
                    .setPositiveButton("前往設定", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(this, "無法開啟設定", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        TextView pct = new TextView(this);
        int m = UIHelper.dp(this, 16);
        pct.setPadding(m, UIHelper.dp(this, 8), m, 0);
        pct.setText("下載中... 0%");

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.setMargins(m, 0, m, 0);
        bar.setLayoutParams(barLp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(pct);
        box.addView(bar);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("下載更新")
                .setView(box)
                .setCancelable(false)
                .create();
        dlg.show();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(apkUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "GpsMocker-Android");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int total = conn.getContentLength();
                File outFile = new File(getExternalFilesDir(null), "update.apk");
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int read;
                    long sum = 0;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        sum += read;
                        if (total > 0) {
                            int pctVal = (int) (sum * 100 / total);
                            runOnUiThread(() -> {
                                bar.setProgress(pctVal);
                                pct.setText("下載中... " + pctVal + "%");
                            });
                        }
                    }
                }
                runOnUiThread(() -> {
                    dlg.dismiss();
                    installApk(outFile);
                });
            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + e.getMessage());
                runOnUiThread(() -> {
                    dlg.dismiss();
                    Toast.makeText(this, "下載失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    private void installApk(File file) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Install failed: " + e.getMessage());
            Toast.makeText(this, "安裝失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
