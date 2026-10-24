import XCTest
import Models

final class FieldTargetOverlayTests: XCTestCase {
    private var configuration: FieldARConfiguration { var c = FieldARConfiguration(); c.enabled = true; return c }
    private func fix() -> FieldOverlayLocation {
        .init(latitude: 0, longitude: 0, horizontalAccuracyM: 1, fixAgeSeconds: 0.2,
              headingTrueDegrees: 0, headingAccuracyDegrees: 2, headingAgeSeconds: 0.1, arTrackingNormal: true)
    }
    private func target(_ number: Int, northM: Double, eastM: Double = 0) -> FieldStudyTarget {
        .init(studyId: "S", treeId: "T\(number)", label: "\(number)",
              latitude: northM/111195.0802335, longitude: eastM/111195.0802335)
    }
    func testEachPositionAndHeadingFailureDisablesOnlyTheOverlay() {
        let c = configuration
        XCTAssertEqual(FieldTargetOverlayPolicy.quality(configuration: c, location: nil), .positionUnavailable)
        var f = fix(); f.fixAgeSeconds = 3
        XCTAssertEqual(FieldTargetOverlayPolicy.quality(configuration: c, location: f), .positionStale)
        f = fix(); f.horizontalAccuracyM = 4
        XCTAssertEqual(FieldTargetOverlayPolicy.quality(configuration: c, location: f), .positionUncertain)
        f = fix(); f.headingTrueDegrees = nil
        XCTAssertEqual(FieldTargetOverlayPolicy.quality(configuration: c, location: f), .headingUnavailable)
        f = fix(); f.headingAgeSeconds = 3
        XCTAssertEqual(FieldTargetOverlayPolicy.quality(configuration: c, location: f), .headingStale)
        f = fix(); f.headingAccuracyDegrees = 11
        XCTAssertEqual(FieldTargetOverlayPolicy.quality(configuration: c, location: f), .headingUncertain)
        f = fix(); f.arTrackingNormal = false
        XCTAssertEqual(FieldTargetOverlayPolicy.quality(configuration: c, location: f), .trackingUnavailable)
    }
    func testRangeFieldOfViewCountAndStudyFiltering() {
        var c = configuration; c.maximumPoints = 2
        let ts = [target(1,northM:5),target(2,northM:10),target(3,northM:15),target(4,northM:30),
                  target(5,northM:-5),target(6,northM:5,eastM:10),FieldStudyTarget(studyId:"OTHER",treeId:"1",label:"1",latitude:0.00001,longitude:0)]
        let d = FieldTargetOverlayPolicy.evaluate(configuration:c,studyId:"S",targets:ts,location:fix(),horizontalFieldOfViewDegrees:60)
        XCTAssertEqual(d.status,.ready); XCTAssertEqual(d.candidates.map(\.label),["1","2"])
    }
    func testHysteresisDoesNotAdmitANewOutOfRangeTarget() {
        let t = target(1,northM:21)
        let first = FieldTargetOverlayPolicy.evaluate(configuration:configuration,studyId:"S",targets:[t],location:fix(),horizontalFieldOfViewDegrees:60)
        XCTAssertTrue(first.candidates.isEmpty)
        let retained = FieldTargetOverlayPolicy.evaluate(configuration:configuration,studyId:"S",targets:[t],location:fix(),
            horizontalFieldOfViewDegrees:60,previouslyVisible:[t.id])
        XCTAssertEqual(retained.candidates.count,1)
    }
    func testSelectedTargetSurvivesCountLimitButNotUnsafePosition() {
        var c = configuration; c.maximumPoints = 1
        let near = target(1,northM:5), selected = target(2,northM:10)
        let d = FieldTargetOverlayPolicy.evaluate(configuration:c,studyId:"S",targets:[near,selected],location:fix(),
            horizontalFieldOfViewDegrees:60,selectedTargetId:selected.id)
        XCTAssertEqual(d.candidates.first?.targetId,selected.id)
        var bad = fix(); bad.horizontalAccuracyM = 99
        XCTAssertTrue(FieldTargetOverlayPolicy.evaluate(configuration:c,studyId:"S",targets:[selected],location:bad,
            horizontalFieldOfViewDegrees:60,selectedTargetId:selected.id).candidates.isEmpty)
    }
    func testWraparoundAndMissingCoordinatesAreNotFabricated() {
        XCTAssertEqual(FieldTargetOverlayPolicy.wrappedDegrees(359),-1)
        XCTAssertEqual(FieldTargetOverlayPolicy.wrappedDegrees(-359),1)
        let t = FieldStudyTarget(studyId:"S",treeId:"T",label:"1")
        XCTAssertTrue(FieldTargetOverlayPolicy.evaluate(configuration:configuration,studyId:"S",targets:[t],location:fix(),
            horizontalFieldOfViewDegrees:60).candidates.isEmpty)
    }
    func testInvalidFixAndViewportDoNotCrashOrUseStalePoints() {
        var f = fix(); f.latitude = .nan
        XCTAssertEqual(FieldTargetOverlayPolicy.quality(configuration:configuration,location:f),.positionUnavailable)
        f = fix(); f.headingAccuracyDegrees = -1
        XCTAssertEqual(FieldTargetOverlayPolicy.quality(configuration:configuration,location:f),.headingUncertain)
        XCTAssertEqual(FieldTargetOverlayPolicy.evaluate(configuration:configuration,studyId:"S",targets:[],location:fix(),
            horizontalFieldOfViewDegrees:.nan).status,.viewportUnavailable)
    }
}
