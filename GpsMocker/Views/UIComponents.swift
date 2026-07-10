import SwiftUI

// MARK: - Card View

struct CardView<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(Theme.accentBlue)

            content()
        }
        .padding(Theme.cardPadding)
        .background(Theme.cardBg)
        .cornerRadius(Theme.cornerRadius)
    }
}

// MARK: - Dark Text Field Style

struct DarkTextFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Theme.inputBg)
            .foregroundColor(Theme.textPrimary)
            .cornerRadius(8)
            .font(.system(size: 14))
    }
}

// MARK: - Accent Button Style

struct AccentButtonStyle: ButtonStyle {
    var color: Color = Theme.accentBlue

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .medium))
            .foregroundColor(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(configuration.isPressed ? color.opacity(0.7) : color)
            .cornerRadius(8)
    }
}
