import Foundation
import CoreLocation

struct RouteWaypoint: Identifiable {
    let id = UUID()
    var name: String
    var coordinate: CLLocationCoordinate2D
    var durationMinutes: Int  // how long to stay at this waypoint

    init(name: String, coordinate: CLLocationCoordinate2D, durationMinutes: Int = 0) {
        self.name = name
        self.coordinate = coordinate
        self.durationMinutes = durationMinutes
    }
}
