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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.Spinner;
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
    private TextView destResultText;
    private Spinner durationSpinner;
    private CheckBox followRoadsCheck;
    private Button startStopBtn;
    private LinearLayout presetsContainer;
    private TextView statusText;

    private double currentLat = 0, currentLng = 0;
    private boolean hasLocation = false;

    private double startLat = 0, startLng = 0;
    private String startName = "";
    private boolean hasStartPoint = false;
    private boolean useCurrentAsStart = true;

    private double destLat = 0, destLng = 0;
    private String destName = "";
    private boolean hasDestination = false;

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
                if (GpsMockService.hasArrived()) {
                    mockStatusText.setText(String.format("Arrived: %.4f, %.4f", lat, lng));
                } else {
                    mockStatusText.setText(String.format("Mock Location: %.4f, %.4f (%d%%)", lat, lng, progress));
                }
                mockStatusText.setTextColor(UIHelper.ACCENT_GREEN);
                uiHandler.postDelayed(this, 1000);
            } else {
                mockStatusText.setText("");
            }
            updateButtonState();
        }
    };

    private final long[] DURATION_VALUES = {
            10 * 60 * 1000,
            30 * 60 * 1000,
            60 * 60 * 1000,
            120 * 60 * 1000,
            -1,
    };
    private final String[] DURATION_LABELS = {"10 min", "30 min", "1 hour", "2 hours", "Custom..."};
    private long customDurationMs = 60 * 60 * 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(UIHelper.BG_TOP_BAR);

        dbHelper = new GpsMockDbHelper(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        geocoder = new Geocoder(this, Locale.getDefault());
        uiHandler = new Handler(Looper.getMainLooper());

        LinearLayout root = UIHelper.pageRoot(this);

        LinearLayout topBar = UIHelper.topBar(this, "GPS Mocker");
        root.addView(topBar);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = UIHelper.dp(this, 16);
        content.setPadding(p, p, p, p);

        // Start point card
        content.addView(UIHelper.sectionHeader(this, "START POINT"));
        LinearLayout startCard = UIHelper.card(this);

        TextView gpsLabel = new TextView(this);
        gpsLabel.setText("Current GPS Location:");
        gpsLabel.setTextSize(14);
        gpsLabel.setTextColor(UIHelper.TEXT_SECONDARY);
        startCard.addView(gpsLabel);

        LinearLayout gpsRow = new LinearLayout(this);
        gpsRow.setOrientation(LinearLayout.HORIZONTAL);
        gpsRow.setGravity(Gravity.CENTER_VERTICAL);

        currentLocText = new TextView(this);
        currentLocText.setText("Acquiring...");
        currentLocText.setTextSize(14);
        currentLocText.setTextColor(UIHelper.TEXT_PRIMARY);
        currentLocText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        gpsRow.addView(currentLocText);

        Button useGpsBtn = UIHelper.smallButton(this, "Use", UIHelper.ACCENT_GREEN);
        useGpsBtn.setOnClickListener(v -> {
            if (hasLocation) {
                useCurrentAsStart = true;
                hasStartPoint = true;
                startLat = currentLat;
                startLng = currentLng;
                startName = "Current Location";
                startResultText.setText(String.format("Using current location (%.4f, %.4f)", currentLat, currentLng));
                startResultText.setTextColor(UIHelper.ACCENT_GREEN);
                startInput.setText("");
            } else {
                Toast.makeText(this, "GPS location not available", Toast.LENGTH_SHORT).show();
            }
        });
        gpsRow.addView(useGpsBtn);

        Button refreshLocBtn = UIHelper.smallButton(this, "Refresh", UIHelper.ACCENT_BLUE);
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
        customLabel.setText("Or search custom start point:");
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

        startInput = UIHelper.styledInput(this, "e.g. Tokyo Station");
        startInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        startSearchRow.addView(startInput);

        Button startSearchBtn = UIHelper.smallButton(this, "Search", UIHelper.ACCENT_BLUE);
        LinearLayout.LayoutParams startSearchBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UIHelper.dp(this, 44));
        startSearchBtnLp.setMargins(UIHelper.dp(this, 8), 0, 0, 0);
        startSearchBtn.setLayoutParams(startSearchBtnLp);
        startSearchBtn.setOnClickListener(v -> searchStartPoint());
        startSearchRow.addView(startSearchBtn);

        startCard.addView(startSearchRow);

        startResultText = new TextView(this);
        startResultText.setText("Default: current GPS location");
        startResultText.setTextSize(13);
        startResultText.setTextColor(UIHelper.TEXT_HINT);
        startResultText.setPadding(0, UIHelper.dp(this, 8), 0, 0);
        startCard.addView(startResultText);

        content.addView(startCard);

        // Destination card
        content.addView(UIHelper.sectionHeader(this, "DESTINATION"));
        LinearLayout destCard = UIHelper.card(this);

        TextView destLabel = new TextView(this);
        destLabel.setText("Enter place name:");
        destLabel.setTextSize(14);
        destLabel.setTextColor(UIHelper.TEXT_SECONDARY);
        destCard.addView(destLabel);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams searchRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchRowLp.setMargins(0, UIHelper.dp(this, 4), 0, 0);
        searchRow.setLayoutParams(searchRowLp);

        destInput = UIHelper.styledInput(this, "e.g. Taipei 101, Eiffel Tower");
        destInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        searchRow.addView(destInput);

        Button searchBtn = UIHelper.smallButton(this, "Search", UIHelper.ACCENT_BLUE);
        LinearLayout.LayoutParams searchBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UIHelper.dp(this, 44));
        searchBtnLp.setMargins(UIHelper.dp(this, 8), 0, 0, 0);
        searchBtn.setLayoutParams(searchBtnLp);
        searchBtn.setOnClickListener(v -> searchPlace());
        searchRow.addView(searchBtn);

        destCard.addView(searchRow);

        destResultText = new TextView(this);
        destResultText.setText("No destination selected");
        destResultText.setTextSize(13);
        destResultText.setTextColor(UIHelper.TEXT_HINT);
        destResultText.setPadding(0, UIHelper.dp(this, 8), 0, UIHelper.dp(this, 8));
        destCard.addView(destResultText);

        LinearLayout durRow = new LinearLayout(this);
        durRow.setOrientation(LinearLayout.HORIZONTAL);
        durRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams durRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        durRowLp.setMargins(0, UIHelper.dp(this, 4), 0, UIHelper.dp(this, 4));
        durRow.setLayoutParams(durRowLp);

        TextView durLabel = new TextView(this);
        durLabel.setText("Duration:");
        durLabel.setTextSize(14);
        durLabel.setTextColor(UIHelper.TEXT_SECONDARY);
        durLabel.setMinWidth(UIHelper.dp(this, 80));
        durRow.addView(durLabel);

        durationSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, DURATION_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        durationSpinner.setAdapter(adapter);
        durationSpinner.setBackground(UIHelper.roundRectStroke(UIHelper.BG_INPUT, Color.parseColor("#2E4050"), 14, 1, this));
        durationSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (DURATION_VALUES[position] == -1) {
                    showCustomDurationDialog();
                }
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        durRow.addView(durationSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        destCard.addView(durRow);

        followRoadsCheck = new CheckBox(this);
        followRoadsCheck.setText("Follow roads (use OSRM routing)");
        followRoadsCheck.setTextColor(UIHelper.TEXT_PRIMARY);
        followRoadsCheck.setTextSize(14);
        followRoadsCheck.setChecked(true);
        followRoadsCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(UIHelper.ACCENT_BLUE));
        LinearLayout.LayoutParams followLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        followLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        followRoadsCheck.setLayoutParams(followLp);
        destCard.addView(followRoadsCheck);

        Button savePresetBtn = UIHelper.smallButton(this, "Save as Preset", UIHelper.ACCENT_GREEN);
        savePresetBtn.setOnClickListener(v -> showSavePresetDialog());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UIHelper.dp(this, 36));
        saveLp.setMargins(0, UIHelper.dp(this, 8), 0, 0);
        savePresetBtn.setLayoutParams(saveLp);
        destCard.addView(savePresetBtn);

        content.addView(destCard);

        startStopBtn = UIHelper.primaryButton(this, "Start Mock");
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

        content.addView(UIHelper.sectionHeader(this, "PRESETS"));
        presetsContainer = new LinearLayout(this);
        presetsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(presetsContainer);

        scrollView.addView(content);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        loadPresets();
        checkAndRequestPermission();
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
                currentLocText.setText("Location permission required");
                currentLocText.setTextColor(UIHelper.ACCENT_RED);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            currentLocText.setText("Location permission required");
            return;
        }

        currentLocText.setText("Acquiring...");
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
                currentLocText.setText("Please enable GPS");
                currentLocText.setTextColor(UIHelper.ACCENT_RED);
            }
        } catch (Exception e) {
            currentLocText.setText("Failed to get location: " + e.getMessage());
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
            Toast.makeText(this, "Please enter a start point name", Toast.LENGTH_SHORT).show();
            return;
        }

        startResultText.setText("Searching...");
        startResultText.setTextColor(UIHelper.TEXT_SECONDARY);

        new Thread(() -> {
            try {
                List<Address> results = geocoder.getFromLocationName(query, 5);
                runOnUiThread(() -> {
                    if (results == null || results.isEmpty()) {
                        startResultText.setText("Not found: \"" + query + "\"");
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
                    startResultText.setText("Search failed: " + e.getMessage());
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
                .setTitle("Select Start Point")
                .setItems(items, (dialog, which) -> selectStartAddress(addresses.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void selectStartAddress(Address address) {
        startLat = address.getLatitude();
        startLng = address.getLongitude();
        startName = formatAddress(address);
        hasStartPoint = true;
        useCurrentAsStart = false;

        startResultText.setText(String.format("%s\n(%.6f, %.6f)", startName, startLat, startLng));
        startResultText.setTextColor(UIHelper.ACCENT_GREEN);

        Log.i(TAG, "Selected start: " + startName);
    }

    private void searchPlace() {
        String query = destInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Please enter a place name", Toast.LENGTH_SHORT).show();
            return;
        }

        destResultText.setText("Searching...");
        destResultText.setTextColor(UIHelper.TEXT_SECONDARY);

        new Thread(() -> {
            try {
                List<Address> results = geocoder.getFromLocationName(query, 5);
                runOnUiThread(() -> {
                    if (results == null || results.isEmpty()) {
                        destResultText.setText("Not found: \"" + query + "\"");
                        destResultText.setTextColor(UIHelper.ACCENT_RED);
                        hasDestination = false;
                    } else if (results.size() == 1) {
                        selectAddress(results.get(0));
                    } else {
                        showAddressPicker(results);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    destResultText.setText("Search failed: " + e.getMessage());
                    destResultText.setTextColor(UIHelper.ACCENT_RED);
                    hasDestination = false;
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
                .setTitle("Select Place")
                .setItems(items, (dialog, which) -> selectAddress(addresses.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void selectAddress(Address address) {
        destLat = address.getLatitude();
        destLng = address.getLongitude();
        destName = formatAddress(address);
        hasDestination = true;

        destResultText.setText(String.format("%s\n(%.6f, %.6f)", destName, destLat, destLng));
        destResultText.setTextColor(UIHelper.ACCENT_GREEN);

        Log.i(TAG, "Selected destination: " + destName);
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

    private void setDestination(double lat, double lng, String name) {
        destLat = lat;
        destLng = lng;
        destName = name;
        hasDestination = true;
        destInput.setText(name);
        destResultText.setText(String.format("%s\n(%.6f, %.6f)", name, lat, lng));
        destResultText.setTextColor(UIHelper.ACCENT_GREEN);
    }

    private void toggleMock() {
        if (GpsMockService.isRunning()) {
            Intent stopIntent = new Intent(this, GpsMockService.class);
            stopIntent.setAction(GpsMockService.ACTION_STOP);
            startService(stopIntent);
            uiHandler.removeCallbacks(statusUpdateRunnable);
            mockStatusText.setText("");
            updateButtonState();
            Toast.makeText(this, "Mock stopped", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Please set a start point (use GPS or search)", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasDestination) {
            Toast.makeText(this, "Please search and select a destination", Toast.LENGTH_SHORT).show();
            return;
        }

        int durationIdx = durationSpinner.getSelectedItemPosition();
        long duration = DURATION_VALUES[durationIdx] == -1 ? customDurationMs : DURATION_VALUES[durationIdx];
        double distance = haversineDistance(actualStartLat, actualStartLng, destLat, destLng);

        String startDesc = hasStartPoint ? startName : "Current Location";
        Log.i(TAG, String.format("Starting mock: %s -> %s, distance %.1fkm, duration %dmin",
                startDesc, destName, distance / 1000, duration / 60000));

        Intent intent = new Intent(this, GpsMockService.class);
        intent.putExtra(GpsMockService.EXTRA_START_LAT, actualStartLat);
        intent.putExtra(GpsMockService.EXTRA_START_LNG, actualStartLng);
        intent.putExtra(GpsMockService.EXTRA_END_LAT, destLat);
        intent.putExtra(GpsMockService.EXTRA_END_LNG, destLng);
        intent.putExtra(GpsMockService.EXTRA_DURATION_MS, duration);
        intent.putExtra(GpsMockService.EXTRA_FOLLOW_ROADS, followRoadsCheck.isChecked());

        startForegroundService(intent);
        Toast.makeText(this, "Starting mock location", Toast.LENGTH_SHORT).show();

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
            startStopBtn.setText("Stop Mock");
            startStopBtn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_RED, 14, this));
            statusText.setText("Mocking... Click button or notification to stop");
            statusText.setTextColor(UIHelper.ACCENT_GREEN);
        } else {
            startStopBtn.setText("Start Mock");
            startStopBtn.setBackground(UIHelper.roundRect(UIHelper.ACCENT_GREEN, 14, this));
            statusText.setText("Select this app as Mock Location App in Developer Options");
            statusText.setTextColor(UIHelper.TEXT_SECONDARY);
            mockStatusText.setText("");
        }
    }

    private static double haversineDistance(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lng2 - lng1);

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private void showMockLocationSetupDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Setup Mock Location")
                .setMessage("Please go to \"Settings > Developer Options > Select mock location app\" and select GPS Mocker.")
                .setPositiveButton("Go to Settings", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadPresets() {
        presetsContainer.removeAllViews();
        List<GpsMockDbHelper.Preset> presets = dbHelper.getAllPresets();

        if (presets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No presets saved");
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

            Button useBtn = UIHelper.smallButton(this, "Use", UIHelper.ACCENT_BLUE);
            useBtn.setOnClickListener(v -> setDestination(preset.lat, preset.lng, preset.name));

            Button delBtn = UIHelper.smallButton(this, "Delete", UIHelper.ACCENT_RED);
            delBtn.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Delete Preset")
                        .setMessage("Delete \"" + preset.name + "\"?")
                        .setPositiveButton("Delete", (d, w) -> {
                            dbHelper.deletePreset(preset.id);
                            Log.i(TAG, "Deleted preset: " + preset.name);
                            loadPresets();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            row.addView(info);
            row.addView(useBtn);
            row.addView(delBtn);

            presetsContainer.addView(row);
        }
    }

    private void showSavePresetDialog() {
        if (!hasDestination) {
            Toast.makeText(this, "Please search and select a destination first", Toast.LENGTH_SHORT).show();
            return;
        }

        EditText nameInput = new EditText(this);
        nameInput.setHint("Preset name");
        nameInput.setText(destName);

        new AlertDialog.Builder(this)
                .setTitle("Save Preset")
                .setView(nameInput)
                .setPositiveButton("Save", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = String.format("%.2f, %.2f", destLat, destLng);
                    }
                    dbHelper.insertPreset(name, destLat, destLng);
                    Log.i(TAG, "Saved preset: " + name);
                    loadPresets();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCustomDurationDialog() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("Minutes");
        input.setText(String.valueOf(customDurationMs / 60000));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("Custom Duration")
                .setMessage("Enter duration in minutes")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        int minutes = Integer.parseInt(input.getText().toString().trim());
                        if (minutes < 1) minutes = 1;
                        if (minutes > 1440) minutes = 1440;
                        customDurationMs = minutes * 60 * 1000L;
                        Toast.makeText(this, "Set to " + minutes + " minutes", Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    durationSpinner.setSelection(0);
                })
                .show();
    }
}
