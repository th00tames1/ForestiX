// Post-scan metadata sheet — attaches species + position + damage
// + note to a freshly-fitted scan before the cruiser hits Accept.
//
// Pragmatic compromise: the ideal Arboreal-style UX is to long-press
// the world-anchored AR cylinder/sphere to edit, keeping the cruiser
// in the AR scene. That's a significant refactor of ARSceneMarker
// gesture handling. This sheet is the 70 % solution — reachable from
// a single "Edit details" pill on the result panel, doesn't leave
// the scan cover, and the bound values flow into the
// QuickMeasureEntry written on Accept.
//
// Long-press AR editing remains on the roadmap; this sheet keeps
// the data model and CSV export honest in the meantime.

import SwiftUI
import Models

public struct ScanMetadataSheet: View {

    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    /// What scan kind we're attaching metadata to.
    public enum Kind { case diameter, height }
    public let kind: Kind

    @Binding public var speciesCode: String?
    @Binding public var position: QuickMeasureEntry.StemPosition?
    @Binding public var damageCodes: [String]
    @Binding public var note: String

    public init(kind: Kind,
                speciesCode: Binding<String?>,
                position: Binding<QuickMeasureEntry.StemPosition?>,
                damageCodes: Binding<[String]>,
                note: Binding<String>) {
        self.kind = kind
        self._speciesCode = speciesCode
        self._position = position
        self._damageCodes = damageCodes
        self._note = note
    }

    public var body: some View {
        NavigationStack {
            Form {
                speciesSection
                if kind == .diameter {
                    positionSection
                }
                damageSection
                noteSection
            }
            .navigationTitle("Reading details")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    // MARK: - Species

    private var speciesSection: some View {
        Section(header: Text("SPECIES").font(ForestixType.sectionHead)) {
            Picker("Species",
                   selection: Binding(
                    get: { speciesCode ?? "" },
                    set: { speciesCode = $0.isEmpty ? nil : $0 })
            ) {
                Text("— Unspecified —").tag("")
                ForEach(speciesOptions, id: \.0) { code, name in
                    Text("\(code) · \(name)").tag(code)
                }
            }
        }
    }

    private var speciesOptions: [(String, String)] {
        let region = settings.region ?? .all
        let regional = RegionalSpecies.defaultSpecies(for: region)
        // Always allow "Other" rather than locking to the regional
        // list — cruisers occasionally measure non-regional trees.
        return regional + [("OT", "Other")]
    }

    // MARK: - Position

    private var positionSection: some View {
        Section(
            header: Text("POSITION").font(ForestixType.sectionHead),
            footer: Text("Default DBH = 1.3 m. Mark butt / upper / stump if you measured elsewhere.")
        ) {
            Picker("Where on the stem",
                   selection: Binding(
                    get: { position ?? .dbh },
                    set: { position = $0 })
            ) {
                ForEach(QuickMeasureEntry.StemPosition.allCases, id: \.self) { p in
                    Text(p.displayName).tag(p)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    // MARK: - Damage

    private var damageSection: some View {
        Section(
            header: Text("DAMAGE").font(ForestixType.sectionHead),
            footer: Text("Drives cull deductions in stand-and-stock reports.")
        ) {
            ForEach(damageOptions, id: \.self) { tag in
                Toggle(tag.capitalized, isOn: Binding(
                    get: { damageCodes.contains(tag) },
                    set: { isOn in
                        if isOn {
                            if !damageCodes.contains(tag) { damageCodes.append(tag) }
                        } else {
                            damageCodes.removeAll { $0 == tag }
                        }
                    }))
            }
        }
    }

    private var damageOptions: [String] {
        ["sweep", "fork", "broken-top", "rot", "scar", "lean"]
    }

    // MARK: - Note

    private var noteSection: some View {
        Section(header: Text("NOTE").font(ForestixType.sectionHead)) {
            TextField("Free-text note (optional)",
                      text: $note, axis: .vertical)
                .lineLimit(2...4)
        }
    }
}
