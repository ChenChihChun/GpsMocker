import Foundation
import HealthKit

final class StepWriterService: ObservableObject {
    static let shared = StepWriterService()
    private let healthStore = HKHealthStore()

    @Published var isAuthorized = false
    @Published var isWriting = false
    @Published var statusMessage = ""

    private init() {}

    var isAvailable: Bool {
        HKHealthStore.isHealthDataAvailable()
    }

    func requestAuthorization() async {
        guard isAvailable else {
            await MainActor.run { statusMessage = "此裝置不支援 HealthKit" }
            return
        }

        guard let stepType = HKQuantityType.quantityType(forIdentifier: .stepCount) else { return }
        let typesToWrite: Set<HKSampleType> = [stepType]

        do {
            try await healthStore.requestAuthorization(toShare: typesToWrite, read: [])
            await MainActor.run { isAuthorized = true }
        } catch {
            await MainActor.run { statusMessage = "HealthKit 授權失敗: \(error.localizedDescription)" }
        }
    }

    /// Write steps distributed over a time period
    /// - Parameters:
    ///   - totalHours: duration in hours (0.5 - 24)
    ///   - stepsPerHour: steps per hour (default 5500)
    func writeSteps(totalHours: Double, stepsPerHour: Int = 5500) async {
        guard isAvailable else {
            await MainActor.run { statusMessage = "此裝置不支援 HealthKit" }
            return
        }

        guard let stepType = HKQuantityType.quantityType(forIdentifier: .stepCount) else { return }

        await MainActor.run {
            isWriting = true
            statusMessage = "正在寫入步數..."
        }

        let now = Date()
        let totalMinutes = totalHours * 60
        let chunkCount = Int(totalMinutes / 30)
        let stepsPerChunk = Double(stepsPerHour) / 2.0 // 30-min chunks

        var samples: [HKQuantitySample] = []

        for i in 0..<max(chunkCount, 1) {
            let chunkStart = now.addingTimeInterval(-totalHours * 3600 + Double(i) * 1800)
            let chunkEnd = chunkStart.addingTimeInterval(1800)

            // ±15% random variation
            let variation = Double.random(in: 0.85...1.15)
            let steps = Int(stepsPerChunk * variation)

            let quantity = HKQuantity(unit: .count(), doubleValue: Double(steps))
            let sample = HKQuantitySample(
                type: stepType,
                quantity: quantity,
                start: chunkStart,
                end: chunkEnd
            )
            samples.append(sample)
        }

        do {
            try await healthStore.save(samples)
            let totalSteps = samples.reduce(0) { $0 + Int($1.quantity.doubleValue(for: .count())) }
            await MainActor.run {
                isWriting = false
                statusMessage = "已寫入 \(totalSteps) 步 (共 \(String(format: "%.1f", totalHours)) 小時)"
            }
        } catch {
            await MainActor.run {
                isWriting = false
                statusMessage = "寫入失敗: \(error.localizedDescription)"
            }
        }
    }
}
