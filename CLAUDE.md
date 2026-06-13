# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug --stacktrace

# Build release APK
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

There are no automated tests in this project.

## Project Overview

GPS Mocker is a single-activity Android app (Java, minSdk 26, targetSdk 33) that mocks device GPS location by interpolating movement from a start point to a destination over a specified duration. It optionally routes along real roads via the OSRM API.

## Architecture

**Single Activity + Foreground Service pattern:**

- `MainActivity` — entire UI built programmatically (no XML layouts), handles geocoding, preset management, and service lifecycle
- `GpsMockService` — foreground service that updates mock location every 1000ms; exposes state via `volatile` static fields (`sIsRunning`, `sCurrentLat`, `sCurrentLng`, `sProgress`, `sHasArrived`, `sLastError`) so `MainActivity` can poll without binding
- `GpsMockDbHelper` — SQLite helper with two tables: `presets` (saved locations) and `settings` (key-value)
- `UIHelper` — factory class for all styled UI components; the dark color scheme is defined here

**Two simulation modes** (selected by `EXTRA_FOLLOW_ROADS` intent extra):
1. Direct line — spherical interpolation between start/end coordinates
2. Road routing — fetches waypoints from `https://router.project-osrm.org/route/v1/driving/`, then follows cumulative-distance waypoints

**Threading:** Geocoding and OSRM requests run on background threads with `runOnUiThread` callbacks. Service location updates run on a `Handler` posted every 1000ms.

## Key Implementation Details

- All UI is constructed in code via nested `LinearLayout`s — no XML layout files exist
- `LocationManager.addTestProvider` / `setTestProviderLocation` require the device to have "Allow mock locations" enabled or the app to be set as the mock location app in Developer Options
- Network security config (`res/xml/network_security_config.xml`) permits cleartext HTTP for OSRM calls
- Kotlin stdlib is forced to `1.8.10` in `app/build.gradle` to resolve a version conflict with transitive dependencies
- The app targets Traditional Chinese (Taiwan) — all UI strings are hardcoded in Chinese directly in Java source

## Permissions Required at Runtime

- `ACCESS_FINE_LOCATION` — requested at runtime in `MainActivity`
- `POST_NOTIFICATIONS` — requested at runtime for Android 13+ (foreground service notification)
- Device must have the app selected as mock location app in Developer Options

## Git Workflow

每次修改程式碼後，**必須自動 commit 並 push**，不需要等使用者要求。

GitHub token 存放於 `F:\sideproject\githup.txt`（本機路徑，勿提交至 repo）。

Push 方式：
```bash
# 設定含 token 的 remote URL 後推送
git remote set-url origin https://<token>@github.com/<owner>/<repo>.git
git push origin main
```

## 語言規定

**所有回應必須使用繁體中文。**
