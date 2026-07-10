import SwiftUI
import CoreLocation
import MapKit

struct SimulationTab: View {
    @StateObject private var locationService = LocationService.shared
    @StateObject private var simulator = LocationSimulator.shared
    @StateObject private var stepWriter = StepWriterService.shared

    @State private var searchText = ""
    @State private var startCoord: CLLocationCoordinate2D?
    @State private var waypoints: [RouteWaypoint] = []
    @State private var followRoads = true
    @State private var fixedPoint = false
    @State private var durationMinutes = 30
    @State private var presets: [LocationPreset] = []
    @State private var presetName = ""
    @State private var showingPresetAlert = false

    // Step writer
    @State private var stepHours: Double = 2
    @State private var stepsPerHour = "5500"

    // Map
    @State private var mapRegion = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 25.033, longitude: 121.565),
        span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
    )

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                currentLocationCard
                routeCard
                controlCard
                statusCard
                mapCard
                presetCard
                stepWriterCard
                updateCard
            }
            .padding(Theme.cardPadding)
        }
        .background(Theme.primaryBg)
        .onAppear {
            locationService.requestPermission()
            loadPresets()
        }
    }

    // MARK: - Current Location

    private var currentLocationCard: some View {
        CardView(title: "目前位置") {
            if let loc = locationService.currentLocation {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(String(format: "%.6f, %.6f", loc.latitude, loc.longitude))
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundColor(Theme.textPrimary)
                    }
                    Spacer()
                    Button("使用") {
                        startCoord = loc
                    }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(Theme.accentBlue)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Theme.buttonBg)
                    .cornerRadius(8)
                }
            } else {
                Text("等待 GPS 定位...")
                    .font(.system(size: 14))
                    .foregroundColor(Theme.textSecondary)
            }
        }
    }

    // MARK: - Route Configuration

    private var routeCard: some View {
        CardView(title: "路線設定") {
            VStack(spacing: 10) {
                // Start point
                HStack {
                    Text("起點:")
                        .foregroundColor(Theme.textSecondary)
                        .font(.system(size: 14))
                    if let start = startCoord {
                        Text(String(format: "%.4f, %.4f", start.latitude, start.longitude))
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundColor(Theme.accentGreen)
                    } else {
                        Text("未設定")
                            .font(.system(size: 13))
                            .foregroundColor(Theme.textSecondary)
                    }
                    Spacer()
                }

                // Search bar
                HStack {
                    TextField("搜尋地址或輸入座標", text: $searchText)
                        .textFieldStyle(DarkTextFieldStyle())
                    Button("搜尋") {
                        searchLocation()
                    }
                    .buttonStyle(AccentButtonStyle())
                }

                // Waypoints
                ForEach(Array(waypoints.enumerated()), id: \.element.id) { index, wp in
                    HStack {
                        Image(systemName: "\(index + 1).circle.fill")
                            .foregroundColor(Theme.accentBlue)
                        Text(wp.name)
                            .font(.system(size: 13))
                            .foregroundColor(Theme.textPrimary)
                            .lineLimit(1)
                        Spacer()
                        Text("\(wp.durationMinutes)分")
                            .font(.system(size: 12))
                            .foregroundColor(Theme.textSecondary)
                        Button(action: { waypoints.remove(at: index) }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(Theme.accentRed)
                        }
                    }
                    .padding(.vertical, 4)
                }

                // Options
                HStack {
                    Toggle("沿道路", isOn: $followRoads)
                        .toggleStyle(SwitchToggleStyle(tint: Theme.accentBlue))
                        .font(.system(size: 14))
                        .foregroundColor(Theme.textPrimary)

                    Spacer()

                    Toggle("定點模式", isOn: $fixedPoint)
                        .toggleStyle(SwitchToggleStyle(tint: Theme.accentOrange))
                        .font(.system(size: 14))
                        .foregroundColor(Theme.textPrimary)
                }

                if !fixedPoint {
                    HStack {
                        Text("行程時間:")
                            .font(.system(size: 14))
                            .foregroundColor(Theme.textSecondary)
                        Stepper("\(durationMinutes) 分鐘", value: $durationMinutes, in: 1...1440)
                            .font(.system(size: 14))
                            .foregroundColor(Theme.textPrimary)
                    }
                }
            }
        }
    }

    // MARK: - Control

    private var controlCard: some View {
        HStack(spacing: 12) {
            if simulator.isRunning {
                Button(action: { simulator.stop() }) {
                    HStack {
                        Image(systemName: "stop.fill")
                        Text("停止")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Theme.accentRed)
                    .foregroundColor(.white)
                    .cornerRadius(Theme.cornerRadius)
                    .font(.system(size: 16, weight: .bold))
                }
            } else {
                Button(action: { startSimulation() }) {
                    HStack {
                        Image(systemName: "play.fill")
                        Text("開始模擬")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Theme.accentGreen)
                    .foregroundColor(.white)
                    .cornerRadius(Theme.cornerRadius)
                    .font(.system(size: 16, weight: .bold))
                }

                Button(action: { exportGPX() }) {
                    HStack {
                        Image(systemName: "square.and.arrow.up")
                        Text("匯出 GPX")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Theme.buttonBg)
                    .foregroundColor(Theme.accentBlue)
                    .cornerRadius(Theme.cornerRadius)
                    .font(.system(size: 16, weight: .bold))
                }
            }
        }
    }

    // MARK: - Status

    private var statusCard: some View {
        Group {
            if simulator.isRunning {
                CardView(title: "模擬狀態") {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(String(format: "%.6f, %.6f", simulator.currentLat, simulator.currentLng))
                                .font(.system(size: 14, design: .monospaced))
                                .foregroundColor(Theme.accentGreen)
                            Spacer()
                            Text(String(format: "%.1f%%", simulator.progress * 100))
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(Theme.accentBlue)
                        }

                        ProgressView(value: simulator.progress)
                            .tint(Theme.accentBlue)

                        Text(simulator.statusText)
                            .font(.system(size: 12))
                            .foregroundColor(Theme.textSecondary)

                        if simulator.hasArrived {
                            Text("已到達目的地")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(Theme.accentGreen)
                        }
                    }
                }
            }
        }
    }

    // MARK: - Map

    private var mapCard: some View {
        CardView(title: "地圖") {
            Map(coordinateRegion: $mapRegion, annotationItems: mapAnnotations) { item in
                MapAnnotation(coordinate: item.coordinate) {
                    Circle()
                        .fill(item.color)
                        .frame(width: 10, height: 10)
                }
            }
            .frame(height: 200)
            .cornerRadius(8)
        }
    }

    private var mapAnnotations: [MapPoint] {
        var points: [MapPoint] = []
        if let start = startCoord {
            points.append(MapPoint(coordinate: start, color: .green))
        }
        for wp in waypoints {
            points.append(MapPoint(coordinate: wp.coordinate, color: .blue))
        }
        if simulator.isRunning {
            points.append(MapPoint(
                coordinate: CLLocationCoordinate2D(latitude: simulator.currentLat, longitude: simulator.currentLng),
                color: .red
            ))
        }
        return points
    }

    // MARK: - Presets

    private var presetCard: some View {
        CardView(title: "預設地點") {
            VStack(spacing: 8) {
                HStack {
                    TextField("名稱", text: $presetName)
                        .textFieldStyle(DarkTextFieldStyle())
                    Button("儲存目前位置") {
                        savePreset()
                    }
                    .buttonStyle(AccentButtonStyle())
                }

                ForEach(presets) { preset in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(preset.name)
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(Theme.textPrimary)
                            Text(String(format: "%.4f, %.4f", preset.lat, preset.lng))
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundColor(Theme.textSecondary)
                        }
                        Spacer()
                        Button("起點") {
                            startCoord = preset.coordinate
                        }
                        .font(.system(size: 12))
                        .foregroundColor(Theme.accentGreen)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Theme.buttonBg)
                        .cornerRadius(6)

                        Button("終點") {
                            addWaypoint(name: preset.name, coord: preset.coordinate)
                        }
                        .font(.system(size: 12))
                        .foregroundColor(Theme.accentBlue)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Theme.buttonBg)
                        .cornerRadius(6)

                        Button(action: { deletePreset(preset) }) {
                            Image(systemName: "trash")
                                .foregroundColor(Theme.accentRed)
                                .font(.system(size: 14))
                        }
                    }
                    .padding(.vertical, 4)
                    Divider().background(Theme.divider)
                }
            }
        }
    }

    // MARK: - Step Writer

    private var stepWriterCard: some View {
        CardView(title: "步數寫入 (HealthKit)") {
            VStack(spacing: 10) {
                HStack {
                    Text("時數:")
                        .foregroundColor(Theme.textSecondary)
                        .font(.system(size: 14))
                    ForEach([1, 2, 3, 4, 6, 8], id: \.self) { h in
                        Button("\(h)h") {
                            stepHours = Double(h)
                        }
                        .font(.system(size: 12, weight: stepHours == Double(h) ? .bold : .regular))
                        .foregroundColor(stepHours == Double(h) ? Theme.accentBlue : Theme.textSecondary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(stepHours == Double(h) ? Theme.buttonBg : Color.clear)
                        .cornerRadius(6)
                    }
                }

                HStack {
                    Text("每小時步數:")
                        .foregroundColor(Theme.textSecondary)
                        .font(.system(size: 14))
                    TextField("5500", text: $stepsPerHour)
                        .textFieldStyle(DarkTextFieldStyle())
                        .keyboardType(.numberPad)
                        .frame(width: 80)
                    Text("(建議 3000-8000)")
                        .font(.system(size: 11))
                        .foregroundColor(Theme.textSecondary)
                }

                Button(action: {
                    Task {
                        if !stepWriter.isAuthorized {
                            await stepWriter.requestAuthorization()
                        }
                        let sph = Int(stepsPerHour) ?? 5500
                        await stepWriter.writeSteps(totalHours: stepHours, stepsPerHour: sph)
                    }
                }) {
                    HStack {
                        if stepWriter.isWriting {
                            ProgressView()
                                .tint(.white)
                                .scaleEffect(0.8)
                        }
                        Text(stepWriter.isWriting ? "寫入中..." : "寫入步數")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(stepWriter.isWriting ? Theme.textSecondary : Theme.accentGreen)
                    .foregroundColor(.white)
                    .cornerRadius(8)
                    .font(.system(size: 14, weight: .bold))
                }
                .disabled(stepWriter.isWriting)

                if !stepWriter.statusMessage.isEmpty {
                    Text(stepWriter.statusMessage)
                        .font(.system(size: 12))
                        .foregroundColor(Theme.textSecondary)
                }
            }
        }
    }

    // MARK: - Update

    private var updateCard: some View {
        CardView(title: "版本") {
            HStack {
                let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
                Text("v\(version)")
                    .font(.system(size: 14))
                    .foregroundColor(Theme.textPrimary)
                Spacer()
                Button("檢查更新") {
                    Task { await UpdateChecker.shared.checkForUpdate() }
                }
                .buttonStyle(AccentButtonStyle())
            }
        }
    }

    // MARK: - Actions

    private func searchLocation() {
        let text = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        // Try coordinate parse first
        if let coord = GeoMath.parseCoordinate(text) {
            addWaypoint(name: text, coord: coord)
            searchText = ""
            return
        }

        // Geocode
        Task {
            if let coord = await locationService.geocode(address: text) {
                await MainActor.run {
                    addWaypoint(name: text, coord: coord)
                    searchText = ""
                }
            }
        }
    }

    private func addWaypoint(name: String, coord: CLLocationCoordinate2D) {
        if startCoord == nil {
            startCoord = coord
        } else {
            waypoints.append(RouteWaypoint(name: name, coordinate: coord))
        }
        mapRegion.center = coord
    }

    private func startSimulation() {
        guard let start = startCoord else { return }

        if fixedPoint {
            simulator.startFixedPoint(at: start)
            return
        }

        guard !waypoints.isEmpty else { return }

        var allPoints = [start]
        allPoints.append(contentsOf: waypoints.map { $0.coordinate })

        let totalSeconds = Double(durationMinutes) * 60

        if followRoads {
            Task {
                do {
                    let route = try await OSRMService.shared.fetchMultiWaypointRoute(waypoints: allPoints)
                    await MainActor.run {
                        simulator.startRoute(points: route, durationSeconds: totalSeconds)
                    }
                } catch {
                    await MainActor.run {
                        // Fallback to direct line
                        simulator.startRoute(points: allPoints, durationSeconds: totalSeconds)
                    }
                }
            }
        } else {
            simulator.startRoute(points: allPoints, durationSeconds: totalSeconds)
        }
    }

    private func exportGPX() {
        guard let start = startCoord else { return }
        var allPoints = [start]
        allPoints.append(contentsOf: waypoints.map { $0.coordinate })

        if let url = simulator.exportGPX(route: allPoints) {
            let ac = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let window = windowScene.windows.first,
               let rootVC = window.rootViewController {
                rootVC.present(ac, animated: true)
            }
        }
    }

    private func loadPresets() {
        presets = DatabaseService.shared.getAllPresets()
    }

    private func savePreset() {
        guard let loc = startCoord ?? locationService.currentLocation else { return }
        let name = presetName.isEmpty ? String(format: "%.4f, %.4f", loc.latitude, loc.longitude) : presetName
        let preset = LocationPreset(name: name, lat: loc.latitude, lng: loc.longitude)
        DatabaseService.shared.insertPreset(preset)
        presetName = ""
        loadPresets()
    }

    private func deletePreset(_ preset: LocationPreset) {
        if let id = preset.id {
            DatabaseService.shared.deletePreset(id: id)
            loadPresets()
        }
    }
}

// MARK: - Map Point

struct MapPoint: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
    let color: Color
}
