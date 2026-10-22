import KinframeKit
import SwiftUI

struct SettingsTab: View { var body: some View { Text("Settings") } }
struct InviteView: View { let api: API; let token: String; var body: some View { Text(token) } }
