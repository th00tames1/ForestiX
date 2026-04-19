// Phase 7 — pre-field checklist.
//
// Reached from the project dashboard ("Pre-field check" row). Surfaces
// seven readiness checks and a big green/orange/red summary banner so
// the cruiser knows at a glance whether it's safe to drive out.

import SwiftUI
import Models

public struct PreFieldChecklistScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: PreFieldChecklistViewModel

    public init(project: Project) {
        _viewModel = StateObject(wrappedValue:
            PreFieldChecklistViewModel(project: project))
    }

    public var body: some View {
        List {
            Section {
                summaryBanner
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
            Section("Checks") {
                ForEach(viewModel.items) { item in
                    row(for: item)
                }
            }
            Section {
                Button {
                    viewModel.runAll()
                } label: {
                    Label("Re-run checks", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("prefield.rerun")
            }
        }
        .navigationTitle("Pre-field check")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task {
            viewModel.configure(with: environment)
            viewModel.runAll()
        }
    }

    // MARK: - Summary banner

    @ViewBuilder private var summaryBanner: some View {
        let (title, tint, systemImage) = bannerDescriptor()
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(.white)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.title3).bold()
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.9))
            }
            Spacer()
        }
        .padding()
        .background(tint)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .accessibilityIdentifier("prefield.summaryBanner")
    }

    private func bannerDescriptor() -> (String, Color, String) {
        if viewModel.items.isEmpty {
            return ("Running checks…", .gray, "clock")
        }
        if viewModel.items.contains(where: { $0.severity == .fail }) {
            return ("Not ready", .red, "xmark.octagon.fill")
        }
        if viewModel.items.contains(where: { $0.severity == .warn }) {
            return ("Ready with warnings", .orange, "exclamationmark.triangle.fill")
        }
        return ("Ready for field", .green, "checkmark.seal.fill")
    }

    private var subtitle: String {
        let pass = viewModel.items.filter { $0.severity == .pass }.count
        let total = viewModel.items.count
        return "\(pass) of \(total) checks passed"
    }

    // MARK: - Row

    @ViewBuilder private func row(for item: ChecklistItem) -> some View {
        HStack(alignment: .top, spacing: 12) {
            symbol(for: item.severity)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title).font(.headline)
                Text(item.message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(item.title), \(item.severity.accessibilityWord)")
        .accessibilityValue(item.message)
    }

    @ViewBuilder
    private func symbol(for severity: ChecklistItem.Severity) -> some View {
        switch severity {
        case .pass:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .warn:
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        case .fail:
            Image(systemName: "xmark.octagon.fill")
                .foregroundStyle(.red)
        }
    }
}

private extension ChecklistItem.Severity {
    var accessibilityWord: String {
        switch self {
        case .pass: return "pass"
        case .warn: return "warning"
        case .fail: return "fail"
        }
    }
}
