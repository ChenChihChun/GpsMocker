import Foundation
import CoreLocation

struct OSRMRoute: Decodable {
    let routes: [OSRMRouteEntry]?

    struct OSRMRouteEntry: Decodable {
        let geometry: OSRMGeometry?
    }

    struct OSRMGeometry: Decodable {
        let coordinates: [[Double]]
    }
}

final class OSRMService {
    static let shared = OSRMService()
    private let baseURL = "https://router.project-osrm.org/route/v1/driving/"

    private init() {}

    func fetchRoute(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) async throws -> [CLLocationCoordinate2D] {
        let urlString = "\(baseURL)\(from.longitude),\(from.latitude);\(to.longitude),\(to.latitude)?overview=full&geometries=geojson"
        guard let url = URL(string: urlString) else {
            throw URLError(.badURL)
        }

        let (data, _) = try await URLSession.shared.data(from: url)
        let result = try JSONDecoder().decode(OSRMRoute.self, from: data)

        guard let coords = result.routes?.first?.geometry?.coordinates else {
            throw NSError(domain: "OSRM", code: -1, userInfo: [NSLocalizedDescriptionKey: "No route found"])
        }

        return coords.map { CLLocationCoordinate2D(latitude: $0[1], longitude: $0[0]) }
    }

    func fetchMultiWaypointRoute(waypoints: [CLLocationCoordinate2D]) async throws -> [CLLocationCoordinate2D] {
        guard waypoints.count >= 2 else { return waypoints }

        var allCoords: [CLLocationCoordinate2D] = []
        for i in 0..<(waypoints.count - 1) {
            let segment = try await fetchRoute(from: waypoints[i], to: waypoints[i + 1])
            if i > 0 && !allCoords.isEmpty {
                allCoords.removeLast() // avoid duplicate at junction
            }
            allCoords.append(contentsOf: segment)
        }
        return allCoords
    }
}
