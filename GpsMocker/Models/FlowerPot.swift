import Foundation
import CoreLocation

struct FlowerPot: Identifiable, Codable {
    var id: Int64?
    var name: String
    var lat: Double
    var lng: Double
    var origLat: Double?
    var origLng: Double?
    var category: String  // "event" or "permanent"
    var corrected: Bool
    var createdAt: Date

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lng)
    }

    init(id: Int64? = nil, name: String, lat: Double, lng: Double,
         origLat: Double? = nil, origLng: Double? = nil,
         category: String = "event", corrected: Bool = false,
         createdAt: Date = Date()) {
        self.id = id
        self.name = name
        self.lat = lat
        self.lng = lng
        self.origLat = origLat
        self.origLng = origLng
        self.category = category
        self.corrected = corrected
        self.createdAt = createdAt
    }
}
