import Foundation
import SQLite3

final class DatabaseService {
    static let shared = DatabaseService()
    private var db: OpaquePointer?

    private init() {
        openDatabase()
        createTables()
    }

    deinit {
        sqlite3_close(db)
    }

    private func openDatabase() {
        let fileURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("gpsmocker.db")
        if sqlite3_open(fileURL.path, &db) != SQLITE_OK {
            print("Database open error")
        }
    }

    private func createTables() {
        let createPresets = """
        CREATE TABLE IF NOT EXISTS presets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            lat REAL NOT NULL,
            lng REAL NOT NULL,
            created_at INTEGER NOT NULL
        );
        """
        let createFlowerPots = """
        CREATE TABLE IF NOT EXISTS flower_pots (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            lat REAL NOT NULL,
            lng REAL NOT NULL,
            orig_lat REAL,
            orig_lng REAL,
            category TEXT NOT NULL DEFAULT 'event',
            corrected INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL
        );
        """
        let createSettings = """
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT
        );
        """
        execute(createPresets)
        execute(createFlowerPots)
        execute(createSettings)
    }

    private func execute(_ sql: String) {
        var errMsg: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &errMsg) != SQLITE_OK {
            if let err = errMsg {
                print("SQL error: \(String(cString: err))")
                sqlite3_free(errMsg)
            }
        }
    }

    // MARK: - Presets

    func getAllPresets() -> [LocationPreset] {
        let sql = "SELECT id, name, lat, lng, created_at FROM presets ORDER BY created_at DESC;"
        var stmt: OpaquePointer?
        var results: [LocationPreset] = []

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                let preset = LocationPreset(
                    id: sqlite3_column_int64(stmt, 0),
                    name: String(cString: sqlite3_column_text(stmt, 1)),
                    lat: sqlite3_column_double(stmt, 2),
                    lng: sqlite3_column_double(stmt, 3),
                    createdAt: Date(timeIntervalSince1970: Double(sqlite3_column_int64(stmt, 4)))
                )
                results.append(preset)
            }
        }
        sqlite3_finalize(stmt)
        return results
    }

    @discardableResult
    func insertPreset(_ preset: LocationPreset) -> Int64 {
        let sql = "INSERT INTO presets (name, lat, lng, created_at) VALUES (?, ?, ?, ?);"
        var stmt: OpaquePointer?
        var newId: Int64 = -1

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (preset.name as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 2, preset.lat)
            sqlite3_bind_double(stmt, 3, preset.lng)
            sqlite3_bind_int64(stmt, 4, Int64(preset.createdAt.timeIntervalSince1970))
            if sqlite3_step(stmt) == SQLITE_DONE {
                newId = sqlite3_last_insert_rowid(db)
            }
        }
        sqlite3_finalize(stmt)
        return newId
    }

    func deletePreset(id: Int64) {
        let sql = "DELETE FROM presets WHERE id = ?;"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_int64(stmt, 1, id)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    // MARK: - Flower Pots

    func getAllFlowerPots() -> [FlowerPot] {
        let sql = "SELECT id, name, lat, lng, orig_lat, orig_lng, category, corrected, created_at FROM flower_pots ORDER BY created_at DESC;"
        var stmt: OpaquePointer?
        var results: [FlowerPot] = []

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            while sqlite3_step(stmt) == SQLITE_ROW {
                let origLat: Double? = sqlite3_column_type(stmt, 4) != SQLITE_NULL ? sqlite3_column_double(stmt, 4) : nil
                let origLng: Double? = sqlite3_column_type(stmt, 5) != SQLITE_NULL ? sqlite3_column_double(stmt, 5) : nil

                let pot = FlowerPot(
                    id: sqlite3_column_int64(stmt, 0),
                    name: String(cString: sqlite3_column_text(stmt, 1)),
                    lat: sqlite3_column_double(stmt, 2),
                    lng: sqlite3_column_double(stmt, 3),
                    origLat: origLat,
                    origLng: origLng,
                    category: String(cString: sqlite3_column_text(stmt, 6)),
                    corrected: sqlite3_column_int(stmt, 7) != 0,
                    createdAt: Date(timeIntervalSince1970: Double(sqlite3_column_int64(stmt, 8)))
                )
                results.append(pot)
            }
        }
        sqlite3_finalize(stmt)
        return results
    }

    @discardableResult
    func insertFlowerPot(_ pot: FlowerPot) -> Int64 {
        let sql = "INSERT INTO flower_pots (name, lat, lng, orig_lat, orig_lng, category, corrected, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?);"
        var stmt: OpaquePointer?
        var newId: Int64 = -1

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (pot.name as NSString).utf8String, -1, nil)
            sqlite3_bind_double(stmt, 2, pot.lat)
            sqlite3_bind_double(stmt, 3, pot.lng)
            if let origLat = pot.origLat {
                sqlite3_bind_double(stmt, 4, origLat)
            } else {
                sqlite3_bind_null(stmt, 4)
            }
            if let origLng = pot.origLng {
                sqlite3_bind_double(stmt, 5, origLng)
            } else {
                sqlite3_bind_null(stmt, 5)
            }
            sqlite3_bind_text(stmt, 6, (pot.category as NSString).utf8String, -1, nil)
            sqlite3_bind_int(stmt, 7, pot.corrected ? 1 : 0)
            sqlite3_bind_int64(stmt, 8, Int64(pot.createdAt.timeIntervalSince1970))
            if sqlite3_step(stmt) == SQLITE_DONE {
                newId = sqlite3_last_insert_rowid(db)
            }
        }
        sqlite3_finalize(stmt)
        return newId
    }

    func updateFlowerPotCoordinates(id: Int64, lat: Double, lng: Double) {
        let sql = "UPDATE flower_pots SET lat = ?, lng = ?, corrected = 1 WHERE id = ?;"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_double(stmt, 1, lat)
            sqlite3_bind_double(stmt, 2, lng)
            sqlite3_bind_int64(stmt, 3, id)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func deleteFlowerPot(id: Int64) {
        let sql = "DELETE FROM flower_pots WHERE id = ?;"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_int64(stmt, 1, id)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }

    func deleteAllFlowerPots() {
        execute("DELETE FROM flower_pots;")
    }

    // MARK: - Settings

    func getSetting(key: String) -> String? {
        let sql = "SELECT value FROM settings WHERE key = ?;"
        var stmt: OpaquePointer?
        var value: String?

        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (key as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) == SQLITE_ROW {
                if let cStr = sqlite3_column_text(stmt, 1) {
                    value = String(cString: cStr)
                }
            }
        }
        sqlite3_finalize(stmt)
        return value
    }

    func setSetting(key: String, value: String) {
        let sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?);"
        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
            sqlite3_bind_text(stmt, 1, (key as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (value as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
        sqlite3_finalize(stmt)
    }
}
