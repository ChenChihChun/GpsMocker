import Foundation
import CoreLocation

struct LocationPreset: Identifiable, Codable {
    var id: Int64?
    var name: String
    var lat: Double
    var lng: Double
    var createdAt: Date

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lng)
    }

    init(id: Int64? = nil, name: String, lat: Double, lng: Double, createdAt: Date = Date()) {
        self.id = id
        self.name = name
        self.lat = lat
        self.lng = lng
        self.createdAt = createdAt
    }
}
