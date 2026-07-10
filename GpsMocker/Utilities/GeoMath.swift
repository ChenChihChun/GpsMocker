import Foundation
import CoreLocation

struct GeoMath {
    /// Haversine distance in meters between two coordinates
    static func distance(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        let R = 6371000.0
        let lat1 = from.latitude * .pi / 180
        let lat2 = to.latitude * .pi / 180
        let dLat = (to.latitude - from.latitude) * .pi / 180
        let dLon = (to.longitude - from.longitude) * .pi / 180

        let a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /// Bearing in degrees from one coordinate to another
    static func bearing(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        let lat1 = from.latitude * .pi / 180
        let lat2 = to.latitude * .pi / 180
        let dLon = (to.longitude - from.longitude) * .pi / 180

        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let bearing = atan2(y, x) * 180 / .pi
        return bearing.truncatingRemainder(dividingBy: 360 + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Spherical interpolation between two coordinates
    static func interpolate(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D, fraction: Double) -> CLLocationCoordinate2D {
        let lat1 = from.latitude * .pi / 180
        let lon1 = from.longitude * .pi / 180
        let lat2 = to.latitude * .pi / 180
        let lon2 = to.longitude * .pi / 180

        let d = distance(from: from, to: to) / 6371000.0
        if d < 1e-10 { return from }

        let a = sin((1 - fraction) * d) / sin(d)
        let b = sin(fraction * d) / sin(d)

        let x = a * cos(lat1) * cos(lon1) + b * cos(lat2) * cos(lon2)
        let y = a * cos(lat1) * sin(lon1) + b * cos(lat2) * sin(lon2)
        let z = a * sin(lat1) + b * sin(lat2)

        let lat = atan2(z, sqrt(x * x + y * y)) * 180 / .pi
        let lon = atan2(y, x) * 180 / .pi
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// Calculate total distance of a route (array of coordinates) in meters
    static func totalDistance(route: [CLLocationCoordinate2D]) -> Double {
        guard route.count >= 2 else { return 0 }
        var total = 0.0
        for i in 0..<(route.count - 1) {
            total += distance(from: route[i], to: route[i + 1])
        }
        return total
    }

    /// Parse coordinate string: "lat, lng" or Google Maps URL
    static func parseCoordinate(_ input: String) -> CLLocationCoordinate2D? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)

        // Try Google Maps URL pattern: @lat,lng or !3dlat!4dlng
        if trimmed.contains("google") || trimmed.contains("maps") {
            if let range = trimmed.range(of: #"@(-?\d+\.?\d*),(-?\d+\.?\d*)"#, options: .regularExpression) {
                let match = String(trimmed[range]).dropFirst() // remove @
                let parts = match.split(separator: ",")
                if parts.count >= 2, let lat = Double(parts[0]), let lng = Double(parts[1]) {
                    return CLLocationCoordinate2D(latitude: lat, longitude: lng)
                }
            }
            if let range3d = trimmed.range(of: #"!3d(-?\d+\.?\d*)"#, options: .regularExpression),
               let range4d = trimmed.range(of: #"!4d(-?\d+\.?\d*)"#, options: .regularExpression) {
                let latStr = String(trimmed[range3d]).replacingOccurrences(of: "!3d", with: "")
                let lngStr = String(trimmed[range4d]).replacingOccurrences(of: "!4d", with: "")
                if let lat = Double(latStr), let lng = Double(lngStr) {
                    return CLLocationCoordinate2D(latitude: lat, longitude: lng)
                }
            }
        }

        // Try simple "lat, lng" format
        let cleaned = trimmed.replacingOccurrences(of: " ", with: "")
        let parts = cleaned.split(separator: ",")
        if parts.count == 2, let lat = Double(parts[0]), let lng = Double(parts[1]) {
            if lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 {
                return CLLocationCoordinate2D(latitude: lat, longitude: lng)
            }
        }
        return nil
    }
}
