// THE capture-photo viewer — one screen, every surface.
//
// The map peek, the field-log detail sheet and the cruise tree peek all
// present THIS view. They each had a viewer of their own before, and none
// of them could reach past the first frame: a Full measurement records a
// diameter AND a height, each with its own Accept snapshot, so the second
// photo sat on the tree with no way in. One viewer is what stops the
// gesture, the caption and the chrome drifting apart between the three
// again — the divergence this repo keeps having to undo.
//
// PAGING STAYS INVISIBLE UNTIL THERE IS SOMEWHERE TO PAGE TO. A tree with
// one photo renders exactly as it did before: no counter, no dots, no new
// chrome. The counter appears from the second photo on, and only then.
//
// The chrome is fixed dark in both appearances — it sits on a photograph.

import SwiftUI
import Common
import Models
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Page model

/// One labelled cell in a page's bottom strip (TREE / METHOD / GPS).
struct MeasurePhotoMetaCell: Identifiable {
    let label: String
    let value: String
    var id: String { label }
}

/// One page of the viewer: the photo, and the caption saying what it
/// belongs to. A photo with no caption is half a record — with two frames
/// on a tree the cruiser cannot otherwise tell the diameter frame from the
/// height frame.
struct MeasurePhotoPage: Identifiable {

    /// How the caption under the photo reads.
    enum Caption {
        /// A quick-measure reading's own frame: the kind and the value in
        /// figures ("DBH 32.4 cm"), then σ and the confidence word.
        case reading
        /// A cruise tree's photo. The Tree row keeps ONE photo path and
        /// does not record which leg of the measurement it came off, so
        /// the page is labelled with the tree rather than with a reading
        /// it cannot honestly claim.
        case tree
    }

    /// Filename inside `MeasurePhotoStore.directory`.
    let photoPath: String
    let headline: String
    let detail: String?
    /// Empty renders no meta row at all.
    let metaCells: [MeasurePhotoMetaCell]
    let caption: Caption

    var id: String { photoPath }
}

// MARK: - Building pages

extension MeasurePhotoPage {

    /// Every photo attached to a group of readings, IN THE ORDER THEY WERE
    /// TAKEN. The history hands entries back newest-first, which would page
    /// a Full measurement height-then-diameter — backwards from the way the
    /// cruiser shot them.
    static func pages(for entries: [QuickMeasureEntry],
                      unitSystem: UnitSystem) -> [MeasurePhotoPage] {
        let inCaptureOrder = entries.sorted { $0.createdAt < $1.createdAt }
        return inCaptureOrder.compactMap { page(for: $0, unitSystem: unitSystem) }
    }

    /// A cruise tree's single Accept snapshot. See `Caption.tree` for why
    /// it is not labelled with a diameter or a height.
    static func pages(forCruiseTree photoPath: String?,
                      title: String,
                      subtitle: String) -> [MeasurePhotoPage] {
        guard let photoPath else { return [] }
        return [MeasurePhotoPage(photoPath: photoPath,
                                 headline: title,
                                 detail: subtitle,
                                 metaCells: [],
                                 caption: .tree)]
    }

    /// nil for a reading that was never photographed — there is no page to
    /// show, and an empty path would open a black screen.
    private static func page(for entry: QuickMeasureEntry,
                             unitSystem: UnitSystem) -> MeasurePhotoPage? {
        guard let photoPath = entry.photoPath else { return nil }
        let descriptor = ConfidenceStyle.descriptor(for: entry.confidenceRaw)
        let detail = [sigmaText(entry, unitSystem), descriptor.label]
            .compactMap { $0 }.joined(separator: " · ")
        let cells = [
            MeasurePhotoMetaCell(label: "TREE", value: treeText(entry)),
            MeasurePhotoMetaCell(label: "METHOD", value: methodText(entry.method)),
            MeasurePhotoMetaCell(label: "GPS", value: gpsText(entry))
        ]
        return MeasurePhotoPage(photoPath: photoPath,
                                headline: headlineText(entry, unitSystem),
                                detail: detail,
                                metaCells: cells,
                                caption: .reading)
    }

    private static func headlineText(_ entry: QuickMeasureEntry,
                                     _ unitSystem: UnitSystem) -> String {
        switch entry.kind {
        case .dbh:
            // The peek card this viewer opens from labels these two
            // values "DBH" and "HEIGHT"; Ø and H were the only place
            // they appeared as symbols.
            return "DBH " + MeasurementFormatter.diameter(cm: entry.value, in: unitSystem)
        case .height:
            return "Height " + MeasurementFormatter.height(m: entry.value, in: unitSystem)
        case .crown:
            return String(format: "%.1f × %.1f m",
                          entry.value, entry.secondaryValue ?? 0)
        case .distance:
            return MeasurementFormatter.distance(m: entry.value, in: unitSystem)
        case .samplingPlot:
            return String(format: "%.1f m radius", entry.value)
        }
    }

    private static func sigmaText(_ entry: QuickMeasureEntry,
                                  _ unitSystem: UnitSystem) -> String? {
        guard let sigma = entry.sigma, sigma > 0 else { return nil }
        switch entry.kind {
        case .dbh:
            return MeasurementFormatter.diameterSigma(mm: sigma, in: unitSystem)
        case .height:
            return MeasurementFormatter.heightSigma(m: sigma, in: unitSystem)
        case .crown, .distance, .samplingPlot:
            return String(format: "±%.2f m", sigma)
        }
    }

    private static func treeText(_ entry: QuickMeasureEntry) -> String {
        var parts: [String] = []
        // The caption strip has room for the whole name, so it prints the
        // full title rather than the pin's shortened form.
        if let n = entry.treeNumber {
            parts.append(TreeLabel.title(name: entry.treeName, number: n))
        }
        if let species = entry.speciesCode, !species.isEmpty {
            parts.append(RegionalSpecies.name(forCode: species))
        }
        return parts.isEmpty ? "—" : parts.joined(separator: " · ")
    }

    /// The entry stores the fix itself (not its accuracy), so the GPS
    /// cell shows the coordinates the reading was anchored to.
    private static func gpsText(_ entry: QuickMeasureEntry) -> String {
        guard let lat = entry.latitude, let lon = entry.longitude else { return "—" }
        return String(format: "%.5f, %.5f", lat, lon)
    }

    /// Plain-language name for the stored capture method. `entry.method`
    /// holds the raw enum/tag string that goes into the CSV export
    /// ("lidarChordSilhouette", "vioWalkoffTangent", "two-point.lidar",
    /// …); printing it verbatim put camelCase identifiers on a cruiser's
    /// screen. The raw value is unchanged in storage and export — only
    /// this readout is translated, and an unknown tag degrades to a
    /// generic phrase rather than leaking the identifier.
    private static func methodText(_ raw: String) -> String {
        switch raw {
        case "lidarChordSilhouette", "lidarPartialArcSingleView",
             "lidarPartialArcDualView", "lidarIrregular":
            return "Trunk scan"
        case "arCaliper", "arVioCircleFit":
            return "Trunk scan (earlier app version)"
        case "manualCaliper":       return "Measured by hand"
        case "manualVisual":        return "Estimated by eye"
        case "vioWalkoffTangent":   return "Walked back and sighted"
        case "tapeTangent":         return "Tape and angle"
        case "manualEntry":         return "Typed in"
        case "imputedHD":           return "Estimated from the height curve"
        case "ar.crown.dh":         return "Crown edges tapped"
        case "ar.tap":              return "Centre dropped on the ground"
        default:
            if raw.hasPrefix("live.")      { return "Pointed at a target" }
            if raw.hasPrefix("two-point.") { return "Two points on screen" }
            return "Measured in Forestix"
        }
    }
}

// MARK: - What the viewer was opened on

/// The pages a presented viewer is showing, and which one it opens on.
struct PhotoViewerContext: Identifiable {
    let pages: [MeasurePhotoPage]
    let startIndex: Int
    var id: String { pages[0].photoPath }

    /// nil when there is no photo to show. The affordances that build this
    /// are disabled without a photo, and a viewer holding nothing would be
    /// a black screen with a close button on it.
    init?(pages: [MeasurePhotoPage], startIndex: Int = 0) {
        guard !pages.isEmpty else { return nil }
        self.pages = pages
        self.startIndex = min(max(startIndex, 0), pages.count - 1)
    }
}

// MARK: - Fixed chrome colours

/// Fixed on purpose in both appearances: every one of these sits on a
/// photograph, not on the app's surface.
enum MeasurePhotoChrome {
    static let ink = Color(red: 0.949, green: 0.961, blue: 0.953)      // #F2F5F3
    static let inkDim = Color(red: 0.647, green: 0.682, blue: 0.659)   // #A5AEA8
    /// Meta-cell labels — dark-appearance textSecondary.
    static let labelDim = Color(red: 0.718, green: 0.753, blue: 0.729) // #B7C0BA
    /// The "Photo unavailable" line — the Android viewer's own grey, so the
    /// two platforms say the same thing in the same colour.
    static let missing = Color(red: 0x79 / 255.0, green: 0x83 / 255.0, blue: 0x7D / 255.0) // #79837D
    /// Dark-glass chrome base (mock `rgba(6,9,10,…)`).
    static let glass = Color(red: 6 / 255, green: 9 / 255, blue: 10 / 255) // #06090A
    static let backdrop = Color(red: 0.039, green: 0.051, blue: 0.043)  // #0A0D0B
}

// MARK: - The viewer

/// Full-screen AR-snapshot viewer: the photo (feed + overlay, captured the
/// instant the measurement landed — not at Accept, see MeasurePhotoStore)
/// with the reading's identity along the bottom, and a horizontal swipe to
/// the tree's next photo when it has one.
struct MeasurePhotoDetailView: View {
    let context: PhotoViewerContext

    @Environment(\.dismiss) private var dismiss
    @State private var index: Int

    init(context: PhotoViewerContext) {
        self.context = context
        _index = State(initialValue: context.startIndex)
    }

    private var pageCount: Int { context.pages.count }

    var body: some View {
        ZStack {
            MeasurePhotoChrome.backdrop.ignoresSafeArea()
            pager
            topBar
        }
    }

    /// One page per photo. With a single page there is nothing to swipe to
    /// and nothing extra on screen — the same picture the viewer has always
    /// shown, in the same place.
    private var pager: some View {
        TabView(selection: $index) {
            ForEach(Array(context.pages.enumerated()), id: \.element.id) { position, page in
                MeasurePhotoPageView(page: page).tag(position)
            }
        }
        .measurePhotoPaging()
    }

    private var topBar: some View {
        VStack {
            HStack {
                if pageCount > 1 { counter }
                Spacer()
                closeButton
            }
            .padding(.horizontal, 14)
            Spacer()
        }
    }

    /// "1 / 2" — says there IS a second photo and which one is on screen.
    /// Only drawn from two photos up; one photo gets no counter at all.
    private var counter: some View {
        Text("\(index + 1) / \(pageCount)")
            .font(.system(size: 13, weight: .semibold, design: .monospaced))
            .foregroundStyle(MeasurePhotoChrome.ink)
            .padding(.horizontal, 10)
            .frame(height: 28)
            .background(Capsule().fill(MeasurePhotoChrome.glass.opacity(0.70)))
            .frame(height: 44)   // centred against the close button's row
            .accessibilityLabel("Photo \(index + 1) of \(pageCount)")
            .accessibilityIdentifier("measurePhoto.counter")
    }

    private var closeButton: some View {
        Button {
            dismiss()
        } label: {
            Image(systemName: "xmark")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(MeasurePhotoChrome.ink)
                .frame(width: 44, height: 44)
                .background(Circle().fill(MeasurePhotoChrome.glass.opacity(0.70)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Close photo")
        .accessibilityIdentifier("measurePhoto.close")
    }
}

private extension View {
    /// Horizontal, swipe-driven paging with the built-in dots suppressed —
    /// the viewer draws its own counter, and only when there is more than
    /// one photo. A no-op off iOS, where the page style does not exist and
    /// this viewer is never presented; it keeps the file compiling for the
    /// macOS test host.
    @ViewBuilder
    func measurePhotoPaging() -> some View {
        #if os(iOS)
        self.tabViewStyle(.page(indexDisplayMode: .never))
        #else
        self
        #endif
    }
}

// MARK: - One page

/// The photo itself plus its caption strip. Each page loads its own file,
/// so paging never shows the previous photo under the next one's caption.
private struct MeasurePhotoPageView: View {
    let page: MeasurePhotoPage

    #if canImport(UIKit)
    @State private var image: UIImage?
    /// Set once the load has actually come back empty. Until then the page
    /// shows the spinner — "unloaded" and "not there" must not look alike.
    @State private var loadFailed = false
    #endif

    var body: some View {
        ZStack {
            #if canImport(UIKit)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if loadFailed {
                // THE READING CLAIMED A PHOTO AND THE FILE IS NOT THERE. It
                // can happen for real: the container moves between installs,
                // and — since the JPEG is now written after the filename is
                // handed to the reading — a write can fail on a full device
                // after Accept already took the name. Say so. A spinner that
                // never resolves reads as "still loading" forever, which is
                // the app quietly claiming evidence it does not have. Same
                // words as the Android viewer.
                Text("Photo unavailable")
                    .font(.system(size: 15))
                    .foregroundStyle(MeasurePhotoChrome.missing)
            } else {
                ProgressView().tint(MeasurePhotoChrome.ink)
            }
            #endif

            VStack {
                Spacer()
                meta
            }
        }
        #if canImport(UIKit)
        .task(id: page.photoPath) {
            image = nil
            loadFailed = false
            let url = MeasurePhotoStore.url(for: page.photoPath)
            let data = await Task.detached { try? Data(contentsOf: url) }.value
            let decoded = data.flatMap { UIImage(data: $0) }
            image = decoded
            loadFailed = decoded == nil
        }
        #endif
    }

    private var meta: some View {
        VStack(alignment: .leading, spacing: page.caption == .reading ? 10 : 4) {
            headline
            if !page.metaCells.isEmpty {
                HStack(alignment: .top, spacing: 18) {
                    ForEach(page.metaCells) { cell in
                        metaCell(cell)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 40)
        .padding(.bottom, 30)
        .background(
            LinearGradient(colors: [MeasurePhotoChrome.glass.opacity(0),
                                    MeasurePhotoChrome.glass.opacity(0.92)],
                           startPoint: .top, endPoint: .bottom))
    }

    /// A reading leads with its number, so the value and its σ sit on one
    /// baseline in figures. A cruise tree leads with the tree's name, which
    /// is prose and stacks above its date line.
    @ViewBuilder
    private var headline: some View {
        switch page.caption {
        case .reading:
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(page.headline)
                    .font(.system(size: 30, weight: .heavy, design: .monospaced))
                    .foregroundStyle(MeasurePhotoChrome.ink)
                Text(page.detail ?? "")
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(MeasurePhotoChrome.inkDim)
            }
        case .tree:
            Text(page.headline)
                .font(.system(size: 17, weight: .heavy))
                .foregroundStyle(MeasurePhotoChrome.ink)
            Text(page.detail ?? "")
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(MeasurePhotoChrome.inkDim)
        }
    }

    private func metaCell(_ cell: MeasurePhotoMetaCell) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(cell.label)
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(MeasurePhotoChrome.labelDim)
            Text(cell.value)
                .font(.system(size: 13, weight: .bold, design: .monospaced))
                .foregroundStyle(MeasurePhotoChrome.ink)
                .lineLimit(1)
        }
    }
}
