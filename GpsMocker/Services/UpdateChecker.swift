import Foundation

struct GitHubRelease: Decodable {
    let tagName: String
    let name: String?
    let body: String?
    let htmlUrl: String

    enum CodingKeys: String, CodingKey {
        case tagName = "tag_name"
        case name, body
        case htmlUrl = "html_url"
    }
}

final class UpdateChecker: ObservableObject {
    static let shared = UpdateChecker()

    @Published var updateAvailable = false
    @Published var latestVersion = ""
    @Published var releaseURL = ""
    @Published var statusMessage = ""

    private let currentVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    private let repoAPI = "https://api.github.com/repos/ChenChihChun/GpsMocker-iOS/releases/latest"

    private init() {}

    func checkForUpdate() async {
        guard let url = URL(string: repoAPI) else { return }

        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            let release = try JSONDecoder().decode(GitHubRelease.self, from: data)
            let remoteVersion = release.tagName.replacingOccurrences(of: "v", with: "")

            await MainActor.run {
                latestVersion = remoteVersion
                releaseURL = release.htmlUrl
                updateAvailable = isNewer(remote: remoteVersion, current: currentVersion)
                if updateAvailable {
                    statusMessage = "有新版本 v\(remoteVersion) 可用"
                } else {
                    statusMessage = "已是最新版本 v\(currentVersion)"
                }
            }
        } catch {
            await MainActor.run {
                statusMessage = "檢查更新失敗"
            }
        }
    }

    private func isNewer(remote: String, current: String) -> Bool {
        let r = remote.split(separator: ".").compactMap { Int($0) }
        let c = current.split(separator: ".").compactMap { Int($0) }
        for i in 0..<max(r.count, c.count) {
            let rv = i < r.count ? r[i] : 0
            let cv = i < c.count ? c[i] : 0
            if rv > cv { return true }
            if rv < cv { return false }
        }
        return false
    }
}
