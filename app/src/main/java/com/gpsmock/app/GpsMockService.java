package com.gpsmock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public class GpsMockService extends Service {

    private static final String TAG = "GpsMockService";

    public static final String EXTRA_START_LAT = "start_lat";
    public static final String EXTRA_START_LNG = "start_lng";
    public static final String EXTRA_END_LAT = "end_lat";
    public static final String EXTRA_END_LNG = "end_lng";
    public static final String EXTRA_DURATION_MS = "duration_ms";
    public static final String EXTRA_FOLLOW_ROADS = "follow_roads";
    public static final String EXTRA_FIXED_POINT = "fixed_point";
    // 多點路線：所有點的經緯度（長度 P）與每段時間（長度 P-1）
    // 跳點模式：每個點各一個停留時間（長度 P），依序定位到每個點並停留
    public static final String EXTRA_LATS = "lats";
    public static final String EXTRA_LNGS = "lngs";
    public static final String EXTRA_SEG_DURATIONS = "seg_durations";
    public static final String EXTRA_JUMP_MODE = "jump_mode";
    // 跳點模式選用：每個停留點對應的菇點 id（長度 P），-1 表示非菇點（例如家）
    public static final String EXTRA_STOP_IDS = "stop_ids";

    public static final String ACTION_STOP = "com.gpsmock.app.GPS_MOCK_STOP";
    public static final String ACTION_TOGGLE_PAUSE = "com.gpsmock.app.GPS_MOCK_TOGGLE_PAUSE";
    public static final String ACTION_MARK_BIG = "com.gpsmock.app.GPS_MOCK_MARK_BIG";
    public static final String ACTION_RECORD_SPOT = "com.gpsmock.app.GPS_MOCK_RECORD_SPOT";

    private static final String CHANNEL_ID = "gps_mock_channel";
    private static final int NOTIFICATION_ID = 9527;
    private static final String[] MOCK_PROVIDERS = {
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
    };
    private static final long UPDATE_INTERVAL_MS = 1000;

    private static volatile boolean sIsRunning = false;
    private static volatile boolean sHasArrived = false;
    private static volatile double sCurrentLat = 0;
    private static volatile double sCurrentLng = 0;
    private static volatile double sProgress = 0;
    private static volatile String sLastError = null;
    private static volatile boolean sFixedPoint = false;
    private static volatile int sSegIndex = 0;
    private static volatile int sSegCount = 0;
    private static volatile boolean sJumpMode = false;
    private static volatile boolean sPaused = false;

    private LocationManager locationManager;
    private GpsMockDbHelper dbHelper;
    private Handler handler;
    private boolean hasArrived = false;

    private double startLat, startLng; // 起點（定點模式與通知初始值）
    private boolean followRoads;
    private boolean fixedPoint;
    private boolean jumpMode;

    private final List<Segment> segments = new ArrayList<>();
    private long totalDurationMs;
    private int curSeg;
    private long segStartTimeMs; // uses SystemClock.elapsedRealtime()
    private float currentSpeed;
    private int notifyCounter;
    // 每次啟動遞增；背景抓路線完成時用來判斷結果是否已過期
    private int sessionId;
    private long[] stopIds; // 跳點模式各停留點的菇點 id，可為 null
    private boolean paused;
    private long pausedAtMs;

    /** 一段路線：折線點、累積距離、總距離、該段時間。 */
    private static class Segment {
        List<double[]> points = new ArrayList<>();
        double[] cumDist;
        double totalDist;
        long durationMs;
    }

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sIsRunning) return;

            if (fixedPoint) {
                sCurrentLat = startLat;
                sCurrentLng = startLng;
                sProgress = 0;
                currentSpeed = 0;
                setMockLocation(startLat, startLng, 0);
                updateNotification(0, startLat, startLng);
                handler.postDelayed(this, UPDATE_INTERVAL_MS);
                return;
            }

            if (segments.isEmpty()) {
                handler.postDelayed(this, UPDATE_INTERVAL_MS);
                return;
            }

            if (paused) {
                // 暫停：停在目前位置，計時凍結
                currentSpeed = 0;
                setMockLocation(sCurrentLat, sCurrentLng, 0);
                handler.postDelayed(this, UPDATE_INTERVAL_MS);
                return;
            }

            Segment seg = segments.get(curSeg);
            long now = SystemClock.elapsedRealtime();
            long segElapsed = now - segStartTimeMs;
            double segFrac = seg.durationMs > 0 ? Math.min(1.0, (double) segElapsed / seg.durationMs) : 1.0;

            double[] pos;
            double bearing;
            if (jumpMode) {
                // 跳點模式：定位在該停留點，等待 durationMs 後跳到下一點
                pos = seg.points.get(0);
                bearing = 0;
                currentSpeed = 0;
            } else {
                double targetDist = segFrac * seg.totalDist;
                pos = positionAlong(seg, targetDist);
                bearing = bearingAlong(seg, targetDist);
                currentSpeed = seg.durationMs > 0 ? (float) (seg.totalDist / (seg.durationMs / 1000.0)) : 0f;
            }

            sCurrentLat = pos[0];
            sCurrentLng = pos[1];
            sSegIndex = curSeg;

            double overall = totalDurationMs > 0
                    ? Math.min(1.0, (double) (durationBefore(curSeg) + segElapsed) / totalDurationMs)
                    : 1.0;
            sProgress = overall;

            setMockLocation(pos[0], pos[1], bearing);
            notifyCounter++;
            if (notifyCounter % 5 == 0 || segFrac >= 1.0) {
                updateNotification(overall, pos[0], pos[1]);
            }

            if (segFrac >= 1.0) {
                if (curSeg < segments.size() - 1) {
                    segStartTimeMs += seg.durationMs;
                    curSeg++;
                    sSegIndex = curSeg;
                    Log.i(TAG, "Advance to segment " + (curSeg + 1) + "/" + segments.size());
                } else if (!hasArrived) {
                    hasArrived = true;
                    sHasArrived = true;
                    Log.i(TAG, "Arrived at final waypoint");
                }
            }
            handler.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        dbHelper = new GpsMockDbHelper(this);
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            startForeground(NOTIFICATION_ID, buildNotification(0, 0, 0));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "User stopped mock");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_PAUSE.equals(intent.getAction())
                || ACTION_MARK_BIG.equals(intent.getAction())
                || ACTION_RECORD_SPOT.equals(intent.getAction())) {
            if (!sIsRunning) {
                // 服務已停止（殘留的通知），不重新啟動
                stopSelf();
                return START_NOT_STICKY;
            }
            handleNotificationAction(intent.getAction());
            return START_NOT_STICKY;
        }

        // startForegroundService 啟動後必須盡快呼叫 startForeground，
        // 否則即使接著 stopSelf 也會觸發 ForegroundServiceDidNotStartInTimeException
        sessionId++;
        handler.removeCallbacks(updateRunnable);
        startForeground(NOTIFICATION_ID, buildNotification(0, sCurrentLat, sCurrentLng));

        fixedPoint = intent.getBooleanExtra(EXTRA_FIXED_POINT, false);
        followRoads = intent.getBooleanExtra(EXTRA_FOLLOW_ROADS, false);
        jumpMode = intent.getBooleanExtra(EXTRA_JUMP_MODE, false);

        paused = false;
        sPaused = false;
        stopIds = null;

        double[] lats = intent.getDoubleArrayExtra(EXTRA_LATS);
        double[] lngs = intent.getDoubleArrayExtra(EXTRA_LNGS);
        long[] segDur = intent.getLongArrayExtra(EXTRA_SEG_DURATIONS);

        double[] ptLats;
        double[] ptLngs;
        long[] durations;
        if (jumpMode && !fixedPoint && lats != null && lngs != null && segDur != null
                && lats.length >= 1 && lngs.length == lats.length && segDur.length == lats.length) {
            ptLats = lats;
            ptLngs = lngs;
            durations = segDur;
            long[] ids = intent.getLongArrayExtra(EXTRA_STOP_IDS);
            if (ids != null && ids.length == lats.length) {
                stopIds = ids;
            }
        } else if (!fixedPoint && lats != null && lngs != null && segDur != null
                && lats.length >= 2 && lngs.length == lats.length && segDur.length == lats.length - 1) {
            ptLats = lats;
            ptLngs = lngs;
            durations = segDur;
        } else {
            // 相容 / 定點模式：單一 start -> end
            double sLat = intent.getDoubleExtra(EXTRA_START_LAT, 0);
            double sLng = intent.getDoubleExtra(EXTRA_START_LNG, 0);
            double eLat = intent.getDoubleExtra(EXTRA_END_LAT, sLat);
            double eLng = intent.getDoubleExtra(EXTRA_END_LNG, sLng);
            long d = intent.getLongExtra(EXTRA_DURATION_MS, 600_000);
            ptLats = new double[]{sLat, eLat};
            ptLngs = new double[]{sLng, eLng};
            durations = new long[]{d};
        }

        if (fixedPoint) {
            followRoads = false;
            jumpMode = false;
        }
        if (jumpMode) {
            followRoads = false; // 跳點模式不需要道路路線
        }
        startLat = ptLats[0];
        startLng = ptLngs[0];

        totalDurationMs = 0;
        for (long d : durations) {
            totalDurationMs += d;
        }

        Log.i(TAG, String.format("Starting: %d points, %d segments, total %dmin, roads: %s, fixed: %s",
                ptLats.length, durations.length, totalDurationMs / 60000,
                followRoads ? "yes" : "no", fixedPoint ? "yes" : "no"));

        if (!setupMockProvider()) {
            sLastError = "請在「設定 > 開發者選項 > 選取模擬位置應用程式」中選擇 GPS 模擬器";
            Log.e(TAG, "Cannot setup Mock Provider: " + sLastError);
            sIsRunning = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        sLastError = null;

        sIsRunning = true;
        sHasArrived = false;
        hasArrived = false;
        sCurrentLat = startLat;
        sCurrentLng = startLng;
        sProgress = 0;
        sFixedPoint = fixedPoint;
        sJumpMode = jumpMode;
        sSegIndex = 0;
        sSegCount = fixedPoint ? 0 : durations.length;

        startForeground(NOTIFICATION_ID, buildNotification(0, startLat, startLng));

        if (fixedPoint) {
            segments.clear();
            startSegmentedSimulation();
        } else if (jumpMode) {
            buildStopSegments(ptLats, ptLngs, durations);
            startSegmentedSimulation();
        } else if (followRoads) {
            fetchSegmentsAndStart(ptLats, ptLngs, durations);
        } else {
            buildDirectSegments(ptLats, ptLngs, durations);
            startSegmentedSimulation();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sessionId++;
        sIsRunning = false;
        sFixedPoint = false;
        sJumpMode = false;
        sPaused = false;
        dbHelper.close();
        handler.removeCallbacks(updateRunnable);
        removeMockProvider();
        Log.i(TAG, "Service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean setupMockProvider() {
        try {
            for (String provider : MOCK_PROVIDERS) {
                try {
                    locationManager.removeTestProvider(provider);
                } catch (SecurityException se) {
                    Log.w(TAG, "SecurityException removing " + provider + ": " + se.getMessage());
                } catch (Exception ignored) {
                    // Provider may not exist yet; safe to ignore before re-adding.
                }

                if (Build.VERSION.SDK_INT >= 31) {
                    locationManager.addTestProvider(
                            provider,
                            false, false, false, false, true, true, true,
                            ProviderProperties.POWER_USAGE_LOW,
                            ProviderProperties.ACCURACY_FINE);
                } else {
                    locationManager.addTestProvider(
                            provider,
                            false, false, false, false, true, true, true,
                            Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                }
                locationManager.setTestProviderEnabled(provider, true);
                Log.i(TAG, "Mock Provider setup success: " + provider);
            }
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Permission error: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Setup Provider failed: " + e.getMessage());
            return false;
        }
    }

    private void removeMockProvider() {
        for (String provider : MOCK_PROVIDERS) {
            try {
                locationManager.setTestProviderEnabled(provider, false);
                locationManager.removeTestProvider(provider);
            } catch (Exception ignored) {
                // Provider may already be removed or unavailable during teardown.
            }
        }
    }

    private void setMockLocation(double lat, double lng, double bearing) {
        long time = System.currentTimeMillis();
        long elapsedNanos = SystemClock.elapsedRealtimeNanos();

        for (String provider : MOCK_PROVIDERS) {
            Location loc = new Location(provider);
            loc.setLatitude(lat);
            loc.setLongitude(lng);
            loc.setAltitude(10.0);
            loc.setAccuracy(3.0f);
            loc.setBearing((float) bearing);
            loc.setSpeed(currentSpeed);
            loc.setTime(time);
            loc.setElapsedRealtimeNanos(elapsedNanos);

            try {
                locationManager.setTestProviderLocation(provider, loc);
            } catch (Exception e) {
                Log.e(TAG, "Set location failed for " + provider + ": " + e.getMessage());
            }
        }
    }

    private static double[] interpolate(double lat1, double lng1, double lat2, double lng2, double fraction) {
        double phi1 = Math.toRadians(lat1);
        double lambda1 = Math.toRadians(lng1);
        double phi2 = Math.toRadians(lat2);
        double lambda2 = Math.toRadians(lng2);

        double deltaPhi = phi2 - phi1;
        double deltaLambda = lambda2 - lambda1;

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double delta = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        if (delta < 1e-10) {
            return new double[]{lat1, lng1};
        }

        double coefA = Math.sin((1 - fraction) * delta) / Math.sin(delta);
        double coefB = Math.sin(fraction * delta) / Math.sin(delta);

        double x = coefA * Math.cos(phi1) * Math.cos(lambda1) + coefB * Math.cos(phi2) * Math.cos(lambda2);
        double y = coefA * Math.cos(phi1) * Math.sin(lambda1) + coefB * Math.cos(phi2) * Math.sin(lambda2);
        double z = coefA * Math.sin(phi1) + coefB * Math.sin(phi2);

        double phi = Math.atan2(z, Math.sqrt(x * x + y * y));
        double lambda = Math.atan2(y, x);

        return new double[]{Math.toDegrees(phi), Math.toDegrees(lambda)};
    }

    private static double calculateBearing(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLambda = Math.toRadians(lng2 - lng1);

        double y = Math.sin(deltaLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);

        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }

    private static double haversineDistance(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371000;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lng2 - lng1);

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    // ---- 多段路線 ----

    private long durationBefore(int idx) {
        long sum = 0;
        for (int i = 0; i < idx && i < segments.size(); i++) {
            sum += segments.get(i).durationMs;
        }
        return sum;
    }

    private static void computeCumDist(Segment seg) {
        double[] cum = new double[seg.points.size()];
        cum[0] = 0;
        for (int i = 1; i < seg.points.size(); i++) {
            double[] a = seg.points.get(i - 1);
            double[] b = seg.points.get(i);
            cum[i] = cum[i - 1] + haversineDistance(a[0], a[1], b[0], b[1]);
        }
        seg.cumDist = cum;
        seg.totalDist = cum[cum.length - 1];
    }

    private void buildDirectSegments(double[] ptLats, double[] ptLngs, long[] durations) {
        segments.clear();
        for (int i = 0; i < durations.length; i++) {
            Segment seg = new Segment();
            seg.points.add(new double[]{ptLats[i], ptLngs[i]});
            seg.points.add(new double[]{ptLats[i + 1], ptLngs[i + 1]});
            seg.durationMs = durations[i];
            computeCumDist(seg);
            segments.add(seg);
        }
    }

    /** 跳點模式：每個點各成一段（起訖同點），停留該點的時間。 */
    private void buildStopSegments(double[] ptLats, double[] ptLngs, long[] durations) {
        segments.clear();
        for (int i = 0; i < durations.length; i++) {
            Segment seg = new Segment();
            seg.points.add(new double[]{ptLats[i], ptLngs[i]});
            seg.points.add(new double[]{ptLats[i], ptLngs[i]});
            seg.durationMs = durations[i];
            computeCumDist(seg);
            segments.add(seg);
        }
    }

    private void fetchSegmentsAndStart(double[] ptLats, double[] ptLngs, long[] durations) {
        Log.i(TAG, "Fetching OSRM routes for " + durations.length + " segments...");
        final int session = sessionId;
        new Thread(() -> {
            List<Segment> built = new ArrayList<>();
            for (int i = 0; i < durations.length; i++) {
                Segment seg = new Segment();
                List<double[]> poly = fetchOsrmPolyline(ptLats[i], ptLngs[i], ptLats[i + 1], ptLngs[i + 1]);
                if (poly != null && poly.size() >= 2) {
                    seg.points = poly;
                } else {
                    seg.points.add(new double[]{ptLats[i], ptLngs[i]});
                    seg.points.add(new double[]{ptLats[i + 1], ptLngs[i + 1]});
                }
                seg.durationMs = durations[i];
                computeCumDist(seg);
                built.add(seg);
            }
            handler.post(() -> {
                if (session != sessionId || !sIsRunning) {
                    Log.i(TAG, "Discard stale OSRM result");
                    return;
                }
                segments.clear();
                segments.addAll(built);
                startSegmentedSimulation();
            });
        }).start();
    }

    private List<double[]> fetchOsrmPolyline(double lat1, double lng1, double lat2, double lng2) {
        try {
            String urlStr = String.format(
                    "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                    lng1, lat1, lng2, lat2);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "GpsMocker/1.0 Android");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "OSRM API returned " + conn.getResponseCode() + ", using direct line");
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject json = new JSONObject(sb.toString());
            if (!"Ok".equals(json.optString("code"))) {
                Log.w(TAG, "OSRM returned error: " + json.optString("code"));
                return null;
            }
            JSONArray routes = json.getJSONArray("routes");
            if (routes.length() == 0) {
                return null;
            }
            JSONArray coordinates = routes.getJSONObject(0)
                    .getJSONObject("geometry").getJSONArray("coordinates");
            List<double[]> points = new ArrayList<>();
            for (int i = 0; i < coordinates.length(); i++) {
                JSONArray c = coordinates.getJSONArray(i);
                points.add(new double[]{c.getDouble(1), c.getDouble(0)});
            }
            return points.size() >= 2 ? points : null;
        } catch (Exception e) {
            Log.e(TAG, "OSRM route fetch failed: " + e.getMessage());
            return null;
        }
    }

    private void startSegmentedSimulation() {
        curSeg = 0;
        segStartTimeMs = SystemClock.elapsedRealtime();
        notifyCounter = 0;
        handler.removeCallbacks(updateRunnable);
        handler.post(updateRunnable);
        Log.i(TAG, "Simulation started, segments=" + segments.size());
    }

    private static double[] positionAlong(Segment seg, double targetDist) {
        List<double[]> pts = seg.points;
        double[] cum = seg.cumDist;
        if (pts.isEmpty()) {
            return new double[]{0, 0};
        }
        // 直線段（兩端點）：用球面插值維持平滑
        if (pts.size() == 2) {
            double f = seg.totalDist > 0 ? targetDist / seg.totalDist : 0;
            double[] a = pts.get(0);
            double[] b = pts.get(1);
            return interpolate(a[0], a[1], b[0], b[1], f);
        }
        for (int i = 1; i < cum.length; i++) {
            if (cum[i] >= targetDist) {
                double segLen = cum[i] - cum[i - 1];
                if (segLen < 0.1) {
                    return pts.get(i);
                }
                double f = (targetDist - cum[i - 1]) / segLen;
                double[] p1 = pts.get(i - 1);
                double[] p2 = pts.get(i);
                return new double[]{p1[0] + f * (p2[0] - p1[0]), p1[1] + f * (p2[1] - p1[1])};
            }
        }
        return pts.get(pts.size() - 1);
    }

    private static double bearingAlong(Segment seg, double targetDist) {
        List<double[]> pts = seg.points;
        double[] cum = seg.cumDist;
        if (pts.size() < 2) {
            return 0;
        }
        for (int i = 1; i < cum.length; i++) {
            if (cum[i] >= targetDist) {
                double[] p1 = pts.get(i - 1);
                double[] p2 = pts.get(i);
                return calculateBearing(p1[0], p1[1], p2[0], p2[1]);
            }
        }
        double[] p1 = pts.get(pts.size() - 2);
        double[] p2 = pts.get(pts.size() - 1);
        return calculateBearing(p1[0], p1[1], p2[0], p2[1]);
    }

    // ---- 通知按鈕 ----

    private void handleNotificationAction(String action) {
        if (ACTION_TOGGLE_PAUSE.equals(action)) {
            setPaused(!paused);
        } else if (ACTION_MARK_BIG.equals(action)) {
            long id = currentStopId();
            if (id < 0) return;
            long expiresAt = System.currentTimeMillis() + Math.round(getBigHours() * 3600_000);
            dbHelper.setExpiresAt(id, expiresAt);
            setPaused(true); // 看到大菇通常要停下來加入
            Toast.makeText(this, "已標記大菇，巡邏暫停", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Marked big mushroom at spot " + id);
        } else if (ACTION_RECORD_SPOT.equals(action)) {
            String name = "菇點 " + new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new Date());
            dbHelper.insertFlowerPot(name, sCurrentLat, sCurrentLng, GpsMockDbHelper.CATEGORY_MUSHROOM);
            Toast.makeText(this, "已記錄菇點", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Recorded spot at " + sCurrentLat + "," + sCurrentLng);
        }
        updateNotification(sProgress, sCurrentLat, sCurrentLng);
    }

    private void setPaused(boolean pause) {
        if (pause == paused) return;
        long now = SystemClock.elapsedRealtime();
        if (pause) {
            pausedAtMs = now;
        } else {
            segStartTimeMs += now - pausedAtMs; // 扣掉暫停的時間
        }
        paused = pause;
        sPaused = pause;
    }

    /** 目前停留點對應的菇點 id；非巡邏或不是菇點時回傳 -1。 */
    private long currentStopId() {
        if (!jumpMode || stopIds == null || curSeg >= stopIds.length) return -1;
        return stopIds[curSeg];
    }

    private double getBigHours() {
        try {
            return Double.parseDouble(dbHelper.getSetting(GpsMockDbHelper.KEY_BIG_HOURS, "2"));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent i = new Intent(this, GpsMockService.class);
        i.setAction(action);
        return PendingIntent.getService(
                this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 依模式加上通知按鈕（最多 3 個）：巡邏菇點時為 標記大菇/暫停/停止，其他為 記錄菇點/停止。 */
    private void addActions(NotificationCompat.Builder b, PendingIntent stopPending) {
        if (stopIds != null) {
            if (currentStopId() >= 0) {
                b.addAction(android.R.drawable.btn_star, "標記大菇", actionIntent(ACTION_MARK_BIG, 2));
            }
            b.addAction(android.R.drawable.ic_media_pause, paused ? "繼續" : "暫停",
                    actionIntent(ACTION_TOGGLE_PAUSE, 3));
        } else {
            b.addAction(android.R.drawable.ic_menu_add, "記錄菇點", actionIntent(ACTION_RECORD_SPOT, 4));
        }
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GPS 模擬器",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("GPS 位置模擬服務");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(double progress, double lat, double lng) {
        Intent stopIntent = new Intent(this, GpsMockService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (fixedPoint) {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("GPS 定點模擬中")
                    .setContentText(String.format("位置: %.4f, %.4f", lat, lng))
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setOngoing(true)
                    .setContentIntent(openPending);
            addActions(b, stopPending);
            return b.build();
        }

        int percent = (int) (progress * 100);
        String statusText;
        if (hasArrived) {
            statusText = String.format("位置: %.4f, %.4f | 已到達終點", lat, lng);
        } else {
            long remainingMs = (long) ((1.0 - progress) * totalDurationMs);
            statusText = String.format("位置: %.4f, %.4f | 剩餘 %s", lat, lng, formatDuration(remainingMs));
        }

        String title;
        if (hasArrived) {
            title = jumpMode ? "跳點巡迴 - 已完成" : "GPS 模擬 - 已到達";
        } else if (jumpMode) {
            title = String.format("%s（第 %d/%d 點）%s", stopIds != null ? "巡邏菇點" : "跳點巡迴中",
                    curSeg + 1, segments.size(), paused ? " 暫停中" : "");
        } else if (segments.size() > 1) {
            title = String.format("GPS 模擬中 %d%%（第 %d/%d 段）", percent, curSeg + 1, segments.size());
        } else {
            title = String.format("GPS 模擬中 %d%%", percent);
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, false)
                .setContentIntent(openPending);
        addActions(b, stopPending);
        return b.build();
    }

    private void updateNotification(double progress, double lat, double lng) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(progress, lat, lng));
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes > 0) {
            return String.format("%d分%d秒", minutes, seconds);
        }
        return String.format("%d秒", seconds);
    }

    public static boolean isRunning() {
        return sIsRunning;
    }

    public static double getCurrentLat() {
        return sCurrentLat;
    }

    public static double getCurrentLng() {
        return sCurrentLng;
    }

    public static double getProgress() {
        return sProgress;
    }

    public static boolean hasArrived() {
        return sHasArrived;
    }

    public static boolean isFixedPoint() {
        return sFixedPoint;
    }

    public static int getSegIndex() {
        return sSegIndex;
    }

    public static int getSegCount() {
        return sSegCount;
    }

    public static boolean isJumpMode() {
        return sJumpMode;
    }

    public static boolean isPaused() {
        return sPaused;
    }

    public static String getLastError() {
        return sLastError;
    }

    public static void clearLastError() {
        sLastError = null;
    }
}
