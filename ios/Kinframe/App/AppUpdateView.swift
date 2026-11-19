import KinframeKit
import SwiftUI

/// "Update kinframe": this build is below the server's minimum (README §5.15), so nothing else is usable. Update opens
/// the store page; Check again re-reads discovery (an operator may have lowered the minimum).
struct AppUpdateView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL
    let storeURL: URL
    @State private var checking = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                LogoMark(size: 72)
                    .padding(.top, 48)
                    .accessibilityHidden(true)
                Text(L("ios.appUpdate.title"))
                    .font(.display(.largeTitle))
                    .foregroundStyle(Palette.text)
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(L("ios.appUpdate.body"))
                    .font(.title3)
                    .foregroundStyle(Palette.textMuted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Text(L("ios.appUpdate.version", ["version": AppBuild.current.versionName, "build": AppBuild.current.buildString]))
                    .font(.footnote)
                    .foregroundStyle(Palette.textFaint)
            }
            .padding(.horizontal, 24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 10) {
                Button(L("ios.appUpdate.update")) { openURL(storeURL) }
                    .buttonStyle(.kf(.primary, large: true, fill: true))
                Button {
                    Task {
                        checking = true
                        await app.checkForAppUpdate()
                        checking = false
                    }
                } label: {
                    if checking {
                        ProgressView().frame(maxWidth: .infinity)
                    } else {
                        Text(L("ios.appUpdate.checkAgain")).frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.kf(.secondary, large: true))
                .disabled(checking)
            }
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .padding(.bottom, 8)
            .frame(maxWidth: 560)
            .background(Palette.bg.opacity(0.94))
        }
        .pageBackground()
    }
}

/// "A new version of kinframe is available", with Update and dismiss (remembered for this recommended build).
struct AppUpdateBanner: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL
    let recommendedBuild: Int
    let storeURL: URL

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.down.app")
                .foregroundStyle(Palette.primarySoftText)
                .accessibilityHidden(true)
            Text(L("ios.appUpdate.banner"))
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Palette.primarySoftText)
                .fixedSize(horizontal: false, vertical: true)
            Button(L("ios.appUpdate.update")) { openURL(storeURL) }
                .font(.footnote.weight(.bold))
                .foregroundStyle(Palette.primary)
            Button {
                app.dismissAppUpdate(recommendedBuild)
            } label: {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Palette.primarySoftText)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(L("common.dismiss"))
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .padding(.vertical, 6)
        .background(Capsule().fill(Palette.primarySoft))
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .accessibilityElement(children: .contain)
    }
}
