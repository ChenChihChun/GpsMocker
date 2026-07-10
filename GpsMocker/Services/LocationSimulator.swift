import Foundation
import CoreLocation
import Combine

/// Simulates GPS location movement along a route.
/// On iOS, true GPS mocking requires developer tools or MDM.
/// This simulator tracks virtual position and can export GPX files for Xcode simulation.
final class LocationSimulator: ObservableObject {
    static let shared = LocationSimulator()

    @Published var isRunning = false
    @Published var currentLat: Double = 0
    @Published var currentLng: Double = 0
    @Published var progress: Double = 0  // 0.0 - 1.0
    @Published var hasArrived = false
    @Published var lastError: String?
    @Published var isFixedPoint = false
    @Published var isJumpMode = false
    @Published var segmentIndex = 0
    @Published var segmentCount = 0
    @Published var statusText = ""
    @Published var bearing: Double = 0
    @Published var speed: Double = 0  // m/s

    private var timer: Timer?
    private var routePoints: [CLLocationCoordinate2D] = []
    private var totalDistance: Double = 0
    private var travelledDistance: Double = 0
    private var currentIndex = 0
    private var segmentFraction: Double = 0
    private var speedMps: Double = 0  // meters per second
    private var startTime: Date?

    // Jump mode
    private var jumpLocations: [CLLocationCoordinate2D] = []
    private var jumpStopDuration: TimeInterval = 30
    private var jumpCurrentIndex = 0
    private var jumpStopTimer: TimeInterval = 0

    // Multi-segment
    struct Segment {
        let points: [CLLocationCoordinate2D]
        let distance: Double
        let durationSeconds: Double
        let staySeconds: Double  // stay at endpoint
    }
    private var segments: [Segment] = []
    private var currentSegmentIdx = 0
    private var segmentElapsed: Double = 0
    private var stayingAtWaypoint = false
    private var stayElapsed: Double = 0

    private init() {}

    // MARK: - Fixed Point

    func startFixedPoint(at coord: CLLocationCoordinate2D) {
        stop()
        isFixedPoint = true
        isJumpMode = false
        currentLat = coord.latitude
        currentLng = coord.longitude
        hasArrived = true
        isRunning = true
        progress = 1.0
        statusText = "定點模式: \(String(format: "%.6f", coord.latitude)), \(String(format: "%.6f", coord.longitude))"
    }

    // MARK: - Route Simulation

    func startRoute(points: [CLLocationCoordinate2D], durationSeconds: Double) {
        guard points.count >= 2 else {
            lastError = "路線至少需要兩個點"
            return
        }
        stop()

        isFixedPoint = false
        isJumpMode = false
        routePoints = points
        totalDistance = GeoMath.totalDistance(route: points)
        speedMps = totalDistance / durationSeconds
        currentIndex = 0
        segmentFraction = 0
        travelledDistance = 0
        hasArrived = false
        progress = 0
        startTime = Date()

        currentLat = points[0].latitude
        currentLng = points[0].longitude
        isRunning = true

        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.updateRoutePosition()
        }
    }

    func startMultiSegmentRoute(segments: [Segment]) {
        guard !segments.isEmpty else { return }
        stop()

        isFixedPoint = false
        isJumpMode = false
        self.segments = segments
        segmentCount = segments.count
        currentSegmentIdx = 0
        segmentElapsed = 0
        stayingAtWaypoint = false
        stayElapsed = 0
        hasArrived = false
        progress = 0
        isRunning = true

        let first = segments[0].points[0]
        currentLat = first.latitude
        currentLng = first.longitude

        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.updateMultiSegment()
        }
    }

    // MARK: - Jump Mode

    func startJumpMode(locations: [CLLocationCoordinate2D], stopDuration: TimeInterval) {
        guard !locations.isEmpty else { return }
        stop()

        isFixedPoint = false
        isJumpMode = true
        jumpLocations = locations
        jumpStopDuration = stopDuration
        jumpCurrentIndex = 0
        jumpStopTimer = 0
        segmentCount = locations.count
        segmentIndex = 0
        hasArrived = false
        isRunning = true

        let first = locations[0]
        currentLat = first.latitude
        currentLng = first.longitude
        statusText = "跳點模式: 1/\(locations.count)"

        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.updateJumpMode()
        }
    }

    // MARK: - Stop

    func stop() {
        timer?.invalidate()
        timer = nil
        isRunning = false
        hasArrived = false
        isFixedPoint = false
        isJumpMode = false
        progress = 0
        segments = []
        routePoints = []
        jumpLocations = []
        statusText = ""
    }

    // MARK: - GPX Export

    func exportGPX(route: [CLLocationCoordinate2D], name: String = "GpsMocker Route") -> URL? {
        var gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="GpsMocker-iOS">
          <trk>
            <name>\(name)</name>
            <trkseg>

        """

        let formatter = ISO8601DateFormatter()
        let now = Date()
        for (i, coord) in route.enumerated() {
            let time = formatter.string(from: now.addingTimeInterval(Double(i)))
            gpx += "      <trkpt lat=\"\(coord.latitude)\" lon=\"\(coord.longitude)\"><time>\(time)</time></trkpt>\n"
        }

        gpx += """
            </trkseg>
          </trk>
        </gpx>
        """

        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("route.gpx")
        do {
            try gpx.write(to: fileURL, atomically: true, encoding: .utf8)
            return fileURL
        } catch {
            lastError = "GPX 匯出失敗: \(error.localizedDescription)"
            return nil
        }
    }

    // MARK: - Private Updates

    private func updateRoutePosition() {
        guard routePoints.count >= 2, currentIndex < routePoints.count - 1 else {
            hasArrived = true
            progress = 1.0
            statusText = "已到達目的地"
            timer?.invalidate()
            timer = nil
            return
        }

        let from = routePoints[currentIndex]
        let to = routePoints[currentIndex + 1]
        let segDist = GeoMath.distance(from: from, to: to)

        let stepDistance = speedMps  // 1 second interval
        travelledDistance += stepDistance

        if segDist > 0 {
            segmentFraction += stepDistance / segDist
        } else {
            segmentFraction = 1.0
        }

        while segmentFraction >= 1.0 && currentIndex < routePoints.count - 2 {
            segmentFraction -= 1.0
            currentIndex += 1
        }

        if currentIndex >= routePoints.count - 1 {
            let last = routePoints.last!
            currentLat = last.latitude
            currentLng = last.longitude
            hasArrived = true
            progress = 1.0
            statusText = "已到達目的地"
            timer?.invalidate()
            return
        }

        let curFrom = routePoints[currentIndex]
        let curTo = routePoints[currentIndex + 1]
        let pos = GeoMath.interpolate(from: curFrom, to: curTo, fraction: min(segmentFraction, 1.0))
        currentLat = pos.latitude
        currentLng = pos.longitude
        bearing = GeoMath.bearing(from: curFrom, to: curTo)
        speed = speedMps

        progress = min(travelledDistance / max(totalDistance, 1), 1.0)
        statusText = String(format: "%.6f, %.6f (%.1f%%)", currentLat, currentLng, progress * 100)
    }

    private func updateMultiSegment() {
        guard currentSegmentIdx < segments.count else {
            hasArrived = true
            progress = 1.0
            statusText = "已到達目的地"
            timer?.invalidate()
            return
        }

        if stayingAtWaypoint {
            stayElapsed += 1
            let seg = segments[currentSegmentIdx]
            if stayElapsed >= seg.staySeconds {
                stayingAtWaypoint = false
                stayElapsed = 0
                currentSegmentIdx += 1
                segmentElapsed = 0
                segmentIndex = currentSegmentIdx
            }
            statusText = String(format: "停留中 %.0f/%.0f 秒 (段 %d/%d)",
                                stayElapsed, segments[currentSegmentIdx < segments.count ? currentSegmentIdx : segments.count - 1].staySeconds,
                                currentSegmentIdx + 1, segments.count)
            return
        }

        let seg = segments[currentSegmentIdx]
        segmentElapsed += 1
        let fraction = min(segmentElapsed / max(seg.durationSeconds, 1), 1.0)

        if seg.points.count >= 2 {
            let totalPts = seg.points.count - 1
            let exactIdx = fraction * Double(totalPts)
            let idx = min(Int(exactIdx), totalPts - 1)
            let subFrac = exactIdx - Double(idx)

            let pos = GeoMath.interpolate(from: seg.points[idx], to: seg.points[min(idx + 1, totalPts)], fraction: subFrac)
            currentLat = pos.latitude
            currentLng = pos.longitude
            bearing = GeoMath.bearing(from: seg.points[idx], to: seg.points[min(idx + 1, totalPts)])
            speed = seg.distance / max(seg.durationSeconds, 1)
        }

        let overallProgress = (Double(currentSegmentIdx) + fraction) / Double(segments.count)
        progress = overallProgress

        if fraction >= 1.0 {
            if seg.staySeconds > 0 {
                stayingAtWaypoint = true
                stayElapsed = 0
            } else {
                currentSegmentIdx += 1
                segmentElapsed = 0
                segmentIndex = currentSegmentIdx
            }
        }

        statusText = String(format: "%.6f, %.6f (段 %d/%d, %.1f%%)",
                            currentLat, currentLng, currentSegmentIdx + 1, segments.count, progress * 100)
    }

    private func updateJumpMode() {
        jumpStopTimer += 1

        if jumpStopTimer >= jumpStopDuration {
            jumpStopTimer = 0
            jumpCurrentIndex += 1

            if jumpCurrentIndex >= jumpLocations.count {
                hasArrived = true
                isRunning = false
                statusText = "跳點模式完成"
                timer?.invalidate()
                return
            }

            let loc = jumpLocations[jumpCurrentIndex]
            currentLat = loc.latitude
            currentLng = loc.longitude
            segmentIndex = jumpCurrentIndex
        }

        progress = Double(jumpCurrentIndex + 1) / Double(jumpLocations.count)
        statusText = String(format: "跳點模式: %d/%d (%.6f, %.6f) 停留 %.0f/%.0f秒",
                            jumpCurrentIndex + 1, jumpLocations.count,
                            currentLat, currentLng, jumpStopTimer, jumpStopDuration)
    }
}
