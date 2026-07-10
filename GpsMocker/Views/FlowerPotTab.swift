import SwiftUI
import CoreLocation

struct FlowerPotTab: View {
    @StateObject private var simulator = LocationSimulator.shared
    @State private var flowerPots: [FlowerPot] = []
    @State private var importText = ""
    @State private var singleName = ""
    @State private var singleCoord = ""
    @State private var stopDuration = "30"
    @State private var showDeleteAllConfirm = false
    @State private var editingPotId: Int64?
    @State private var editLat = ""
    @State private var editLng = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                importCard
                addSingleCard
                tourCard
                potListCard
            }
            .padding(Theme.cardPadding)
        }
        .background(Theme.primaryBg)
        .onAppear { loadPots() }
    }

    // MARK: - Import

    private var importCard: some View {
        CardView(title: "匯入花盆座標") {
            VStack(spacing: 8) {
                Text("每行一個座標 (緯度,經度) 或 Google Maps 連結")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.textSecondary)

                TextEditor(text: $importText)
                    .font(.system(size: 13, design: .monospaced))
                    .frame(height: 100)
                    .background(Theme.inputBg)
                    .foregroundColor(Theme.textPrimary)
                    .cornerRadius(8)
                    .scrollContentBackground(.hidden)

                Button(action: importPots) {
                    Text("匯入")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Theme.accentBlue)
                        .foregroundColor(.white)
                        .cornerRadius(8)
                        .font(.system(size: 14, weight: .bold))
                }
            }
        }
    }

    // MARK: - Add Single

    private var addSingleCard: some View {
        CardView(title: "新增單一花盆") {
            VStack(spacing: 8) {
                HStack {
                    TextField("名稱", text: $singleName)
                        .textFieldStyle(DarkTextFieldStyle())
                    TextField("緯度,經度", text: $singleCoord)
                        .textFieldStyle(DarkTextFieldStyle())
                }
                Button(action: addSingle) {
                    Text("新增")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Theme.accentGreen)
                        .foregroundColor(.white)
                        .cornerRadius(8)
                        .font(.system(size: 14, weight: .bold))
                }
            }
        }
    }

    // MARK: - Tour

    private var tourCard: some View {
        CardView(title: "巡遊花盆") {
            VStack(spacing: 8) {
                HStack {
                    Text("每點停留:")
                        .foregroundColor(Theme.textSecondary)
                        .font(.system(size: 14))
                    TextField("30", text: $stopDuration)
                        .textFieldStyle(DarkTextFieldStyle())
                        .keyboardType(.numberPad)
                        .frame(width: 60)
                    Text("秒")
                        .foregroundColor(Theme.textSecondary)
                        .font(.system(size: 14))
                    Spacer()
                }

                HStack(spacing: 12) {
                    if simulator.isRunning && simulator.isJumpMode {
                        Button(action: { simulator.stop() }) {
                            HStack {
                                Image(systemName: "stop.fill")
                                Text("停止巡遊")
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Theme.accentRed)
                            .foregroundColor(.white)
                            .cornerRadius(8)
                            .font(.system(size: 14, weight: .bold))
                        }
                    } else {
                        Button(action: startTour) {
                            HStack {
                                Image(systemName: "figure.walk")
                                Text("開始巡遊 (\(flowerPots.count) 點)")
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(flowerPots.isEmpty ? Theme.textSecondary : Theme.accentOrange)
                            .foregroundColor(.white)
                            .cornerRadius(8)
                            .font(.system(size: 14, weight: .bold))
                        }
                        .disabled(flowerPots.isEmpty)
                    }
                }

                if simulator.isRunning && simulator.isJumpMode {
                    VStack(spacing: 4) {
                        ProgressView(value: simulator.progress)
                            .tint(Theme.accentOrange)
                        Text(simulator.statusText)
                            .font(.system(size: 12))
                            .foregroundColor(Theme.textSecondary)
                    }
                }
            }
        }
    }

    // MARK: - Pot List

    private var potListCard: some View {
        CardView(title: "花盆列表 (\(flowerPots.count))") {
            VStack(spacing: 0) {
                HStack {
                    Spacer()
                    Button(action: { showDeleteAllConfirm = true }) {
                        HStack {
                            Image(systemName: "trash")
                            Text("全部刪除")
                        }
                        .font(.system(size: 13))
                        .foregroundColor(Theme.accentRed)
                    }
                    .alert("確定刪除所有花盆?", isPresented: $showDeleteAllConfirm) {
                        Button("刪除", role: .destructive) {
                            DatabaseService.shared.deleteAllFlowerPots()
                            loadPots()
                        }
                        Button("取消", role: .cancel) {}
                    }
                }
                .padding(.bottom, 8)

                ForEach(flowerPots) { pot in
                    VStack(spacing: 6) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text(pot.name)
                                        .font(.system(size: 14, weight: .medium))
                                        .foregroundColor(Theme.textPrimary)
                                    if pot.corrected {
                                        Text("已校正")
                                            .font(.system(size: 10))
                                            .foregroundColor(Theme.accentOrange)
                                            .padding(.horizontal, 4)
                                            .padding(.vertical, 1)
                                            .background(Theme.accentOrange.opacity(0.2))
                                            .cornerRadius(4)
                                    }
                                    Text(pot.category == "permanent" ? "常駐" : "活動")
                                        .font(.system(size: 10))
                                        .foregroundColor(pot.category == "permanent" ? Theme.accentGreen : Theme.accentBlue)
                                        .padding(.horizontal, 4)
                                        .padding(.vertical, 1)
                                        .background((pot.category == "permanent" ? Theme.accentGreen : Theme.accentBlue).opacity(0.2))
                                        .cornerRadius(4)
                                }
                                Text(String(format: "%.6f, %.6f", pot.lat, pot.lng))
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundColor(Theme.textSecondary)
                            }
                            Spacer()

                            // Actions
                            HStack(spacing: 8) {
                                Button(action: {
                                    if editingPotId == pot.id {
                                        editingPotId = nil
                                    } else {
                                        editingPotId = pot.id
                                        editLat = String(format: "%.6f", pot.lat)
                                        editLng = String(format: "%.6f", pot.lng)
                                    }
                                }) {
                                    Image(systemName: "pencil")
                                        .foregroundColor(Theme.accentBlue)
                                        .font(.system(size: 14))
                                }

                                Button(action: {
                                    // Use as start point in simulator
                                    LocationSimulator.shared.startFixedPoint(at: pot.coordinate)
                                }) {
                                    Image(systemName: "location.fill")
                                        .foregroundColor(Theme.accentGreen)
                                        .font(.system(size: 14))
                                }

                                Button(action: {
                                    if let id = pot.id {
                                        DatabaseService.shared.deleteFlowerPot(id: id)
                                        loadPots()
                                    }
                                }) {
                                    Image(systemName: "trash")
                                        .foregroundColor(Theme.accentRed)
                                        .font(.system(size: 14))
                                }
                            }
                        }

                        // Edit row
                        if editingPotId == pot.id {
                            HStack {
                                TextField("緯度", text: $editLat)
                                    .textFieldStyle(DarkTextFieldStyle())
                                    .keyboardType(.decimalPad)
                                TextField("經度", text: $editLng)
                                    .textFieldStyle(DarkTextFieldStyle())
                                    .keyboardType(.decimalPad)
                                Button("更新") {
                                    if let id = pot.id,
                                       let lat = Double(editLat),
                                       let lng = Double(editLng) {
                                        DatabaseService.shared.updateFlowerPotCoordinates(id: id, lat: lat, lng: lng)
                                        editingPotId = nil
                                        loadPots()
                                    }
                                }
                                .buttonStyle(AccentButtonStyle())
                            }
                        }
                    }
                    .padding(.vertical, 6)
                    Divider().background(Theme.divider)
                }
            }
        }
    }

    // MARK: - Actions

    private func loadPots() {
        flowerPots = DatabaseService.shared.getAllFlowerPots()
    }

    private func importPots() {
        let lines = importText.components(separatedBy: .newlines)
        var count = 0
        for (i, line) in lines.enumerated() {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            if let coord = GeoMath.parseCoordinate(trimmed) {
                let pot = FlowerPot(
                    name: "花盆 #\(i + 1)",
                    lat: coord.latitude,
                    lng: coord.longitude
                )
                DatabaseService.shared.insertFlowerPot(pot)
                count += 1
            }
        }
        importText = ""
        loadPots()
    }

    private func addSingle() {
        guard let coord = GeoMath.parseCoordinate(singleCoord) else { return }
        let name = singleName.isEmpty ? "花盆" : singleName
        let pot = FlowerPot(name: name, lat: coord.latitude, lng: coord.longitude)
        DatabaseService.shared.insertFlowerPot(pot)
        singleName = ""
        singleCoord = ""
        loadPots()
    }

    private func startTour() {
        let coords = flowerPots.map { $0.coordinate }
        let duration = TimeInterval(Int(stopDuration) ?? 30)
        simulator.startJumpMode(locations: coords, stopDuration: duration)
    }
}
