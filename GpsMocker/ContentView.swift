import SwiftUI

enum AppTab: Int {
    case simulation = 0
    case flowerPots = 1
}

struct ContentView: View {
    @State private var selectedTab: AppTab = .simulation

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("GPS Mocker")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(Theme.accentBlue)
                Spacer()
                Text("iOS")
                    .font(.system(size: 14))
                    .foregroundColor(Theme.textSecondary)
            }
            .padding(.horizontal, Theme.cardPadding)
            .padding(.vertical, 12)
            .background(Theme.topBar)

            // Tab buttons
            HStack(spacing: 0) {
                tabButton(title: "模擬導航", tab: .simulation)
                tabButton(title: "金色花盆", tab: .flowerPots)
            }
            .background(Theme.topBar)

            Divider()
                .background(Theme.divider)

            // Content
            switch selectedTab {
            case .simulation:
                SimulationTab()
            case .flowerPots:
                FlowerPotTab()
            }
        }
        .background(Theme.primaryBg)
    }

    private func tabButton(title: String, tab: AppTab) -> some View {
        Button(action: { selectedTab = tab }) {
            Text(title)
                .font(.system(size: 15, weight: selectedTab == tab ? .bold : .regular))
                .foregroundColor(selectedTab == tab ? Theme.accentBlue : Theme.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .overlay(
                    Rectangle()
                        .frame(height: 2)
                        .foregroundColor(selectedTab == tab ? Theme.accentBlue : .clear),
                    alignment: .bottom
                )
        }
    }
}
