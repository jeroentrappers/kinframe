import KinframeKit
import SwiftUI

struct FrameDetailView: View { let route: FrameRoute; var body: some View { Text(route.id) } }
struct HistoryTab: View { var body: some View { Text("History") } }
struct SettingsTab: View { var body: some View { Text("Settings") } }
struct InviteView: View { let api: API; let token: String; var body: some View { Text(token) } }
struct HistoryRoute: Hashable { var sendId: String }
