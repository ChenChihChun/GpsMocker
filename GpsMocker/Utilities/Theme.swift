import SwiftUI

struct Theme {
    static let primaryBg = Color(hex: "0F1923")
    static let cardBg = Color(hex: "1A2733")
    static let topBar = Color(hex: "162330")
    static let accentBlue = Color(hex: "4FC3F7")
    static let accentGreen = Color(hex: "66BB6A")
    static let accentRed = Color(hex: "EF5350")
    static let accentOrange = Color(hex: "FFA726")
    static let textPrimary = Color(hex: "ECEFF1")
    static let textSecondary = Color(hex: "90A4AE")
    static let divider = Color(hex: "263238")
    static let inputBg = Color(hex: "0D1520")
    static let buttonBg = Color(hex: "1E3A5F")

    static let cornerRadius: CGFloat = 12
    static let cardPadding: CGFloat = 16
}

extension Color {
    init(hex: String) {
        let scanner = Scanner(string: hex)
        var rgb: UInt64 = 0
        scanner.scanHexInt64(&rgb)
        self.init(
            red: Double((rgb >> 16) & 0xFF) / 255.0,
            green: Double((rgb >> 8) & 0xFF) / 255.0,
            blue: Double(rgb & 0xFF) / 255.0
        )
    }
}
