package com.gpsmock.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final String TAG = "GpsMocker";
    private static final int PERMISSION_LOCATION = 1001;

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

    private double currentLat = 0, currentLng = 0;
    private boolean hasLocation = false;

    private double startLat = 0, startLng = 0;
    private String startName = "";
    private boolean hasStartPoint = false;

    private LinearLayout waypointsContainer;
    private final java.util.List<Waypoint> waypoints = new java.util.ArrayList<>();

    private Handler uiHandler;
    private TextView mockStatusText;

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

        scrollView.addView(content);
        simTabView = scrollView;

        // 頂部分頁列（模擬 / 金色花盆）
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(UIHelper.BG_TOP_BAR);
        int tabPad = UIHelper.dp(this, 6);
        tabBar.setPadding(tabPad, tabPad, tabPad, tabPad);
        tabSimBtn = tabButton("模擬");
        tabPotBtn = tabButton("金色花盆");
        tabSimBtn.setOnClickListener(v -> selectTab(0));
        tabPotBtn.setOnClickListener(v -> selectTab(1));
        tabBar.addView(tabSimBtn);
        tabBar.addView(tabPotBtn);
        root.addView(tabBar);

        // 內容容器：兩個分頁疊在一起，切換 visibility
        FrameLayout contentFrame = new FrameLayout(this);
        potTabView = buildFlowerPotTab();
        contentFrame.addView(simTabView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentFrame.addView(potTabView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        selectTab(0);
        loadPresets();
        loadFlowerPots();
        renderWaypoints();
        checkAndRequestPermission();
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
        boolean sim = index == 0;
        simTabView.setVisibility(sim ? View.VISIBLE : View.GONE);
        potTabView.setVisibility(sim ? View.GONE : View.VISIBLE);
        styleTab(tabSimBtn, sim);
        styleTab(tabPotBtn, !sim);
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
        content.addView(btnRow);

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

        String shownCategory = null;
        for (GpsMockDbHelper.FlowerPot pot : pots) {
            if (!pot.category.equals(shownCategory)) {
                shownCategory = pot.category;
                String label = GpsMockDbHelper.CATEGORY_PERMANENT.equals(pot.category) ? "常駐" : "活動";
                potsContainer.addView(UIHelper.sectionHeader(this, label));
            }
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

        if (GpsMockDbHelper.CATEGORY_EVENT.equals(pot.category)) {
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
        }

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
                    loadFlowerPots();
                })
                .setNegativeButton("取消", null);

        if (pot.corrected) {
            b.setNeutralButton("復原", (d, w) -> {
                dbHelper.revertFlowerPot(pot.id);
                Toast.makeText(this, "已復原", Toast.LENGTH_SHORT).show();
                loadFlowerPots();
            });
        }
        b.show();
    }
}
