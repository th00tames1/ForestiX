import XCTest
import Models

final class FieldStudyTests: XCTestCase {
    private func config() -> FieldStudyConfiguration {
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = "SYNTHETIC-STUDY"; c.operatorId = "OP-1"; return c
    }
    private func archive() throws -> FieldStudyArchive {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-study-tests-"+UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }; return FieldStudyArchive(root: root)
    }
    private func record(configuration: FieldStudyConfiguration? = nil) -> FieldMeasurementRecord {
        .init(sessionId: UUID().uuidString, treeId: "TREE-034", kind: .dbh, attemptNumber: 1,
              configuration: configuration ?? config(), runtimeSettings: ["estimator_epoch": "9", "sampling_fraction": "0.8"])
    }
    func testConfigurationRoundTripsSnakeCaseWithoutAcronymLoss() throws {
        let c = config(); let bytes = try FieldStudyJSON.encoder().encode(c)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        XCTAssertEqual(json["preserve_ai_inputs"] as? Bool, true)
        XCTAssertEqual(try FieldStudyJSON.decoder().decode(FieldStudyConfiguration.self, from: bytes), c)
    }
    func testProtocolLockRequiresVersionChangeNotASettingToggle() throws {
        var c = config(); c.protocolLocked = true
        var changed = c; changed.ar.maximumDistanceM = 30
        XCTAssertThrowsError(try changed.validateTransition(from: c, developerMode: true))
        changed.protocolVersion = "2"; XCTAssertNoThrow(try changed.validateTransition(from: c, developerMode: true))
        changed = c; changed.protocolLocked = false
        XCTAssertThrowsError(try changed.validateTransition(from: c, developerMode: true))
        changed = c; changed.operatorId = "OP-2"; changed.plannedRepeat = 2
        XCTAssertNoThrow(try changed.validateTransition(from: c, developerMode: false))
    }
    func testReferenceDefinitionEditingRequiresDeveloperMode() throws {
        let c = config(); var changed = c
        changed.referenceMethods.append(.init(name: "Caliper", kind: .dbh, unit: .cm))
        XCTAssertThrowsError(try changed.validateTransition(from: c, developerMode: false))
        XCTAssertNoThrow(try changed.validateTransition(from: c, developerMode: true))
    }
    func testInvalidUnitsCoordinatesAndRepetitionsAreRefused() throws {
        XCTAssertThrowsError(try FieldReferenceMethod(name: "Bad", kind: .dbh, unit: .m).validate())
        var c = config(); c.plannedRepeat = 0; XCTAssertThrowsError(try c.validate())
        c = config(); c.ar.maximumDistanceM = .nan; XCTAssertThrowsError(try c.validate())
        XCTAssertThrowsError(try FieldStudyTarget(studyId: "S", treeId: "T", label: "1", latitude: 999, longitude: 0).validate())
        XCTAssertThrowsError(try FieldStudyTarget(studyId: "S", treeId: "T", label: "1", latitude: 45).validate())
        XCTAssertNoThrow(try FieldStudyTarget(studyId: "S", treeId: "T", label: "1").validate())
    }
    func testReferencesPreserveEveryRepeatOriginalUnitsAndSupersession() throws {
        let a = try archive(); let t = FieldStudyTarget(studyId: "S", treeId: "T", label: "1")
        try a.saveTargets([t])
        let method = FieldReferenceMethod(name: "Tape", kind: .dbh, unit: .inch, role: .groundTruth)
        let r = FieldReferenceObservation(targetId: t.id, method: method, inputValue: 10, operatorId: "OP")
        XCTAssertThrowsError(try a.appendReference(r, developerMode: false))
        try a.appendReference(r, developerMode: true)
        let corrected = FieldReferenceObservation(targetId: t.id, method: method, inputValue: 11,
                                                   operatorId: "OP", supersedesId: r.id)
        try a.appendReference(corrected, developerMode: true)
        let second = FieldReferenceObservation(targetId: t.id, method: method, inputValue: 12,
                                              plannedRepeat: 2, referenceRepeat: 2, operatorId: "OP")
        try a.appendReference(second, developerMode: true)
        XCTAssertEqual(a.scanReferences().values.count, 3)
        XCTAssertEqual(try XCTUnwrap(a.scanReferences().values.first { $0.id == r.id }).baseValue, 25.4, accuracy: 1e-12)
        XCTAssertThrowsError(try a.appendReference(r, developerMode: true))
        let branch = FieldReferenceObservation(targetId: t.id, method: method, inputValue: 13,
                                                operatorId: "OP", supersedesId: r.id)
        XCTAssertThrowsError(try a.appendReference(branch, developerMode: true))
    }
    func testMeasurementAndRawIdentityCannotDivergeOrBeReassigned() throws {
        let a = try archive(); var r = record(); r.rawStatus = .pending; r.rawBundleId = r.id
        try a.createMeasurement(r)
        XCTAssertThrowsError(try a.updateMeasurement(id: r.id) { $0.treeId = "ANOTHER-TREE" })
        XCTAssertThrowsError(try a.updateMeasurement(id: r.id) { $0.rawBundleId = UUID().uuidString })
        XCTAssertThrowsError(try a.createMeasurement(r))
        let done = try a.updateMeasurement(id: r.id, expectedRevision: 0) {
            $0.outcome = .result; $0.liveValue = 25; $0.selection = .accepted; $0.rawStatus = .saved
            $0.events.append(.init(action: "accepted"))
        }
        XCTAssertEqual(done.revision, 1)
        XCTAssertThrowsError(try a.updateMeasurement(id: r.id, expectedRevision: 0) { $0.rawStatus = .missing })
        XCTAssertThrowsError(try a.updateMeasurement(id: r.id) { $0.liveValue = 30 })
        XCTAssertThrowsError(try a.updateMeasurement(id: r.id) { $0.outcome = .failed; $0.failureReason = "late" })
        XCTAssertNoThrow(try a.updateMeasurement(id: r.id) { $0.selection = .superseded })
    }
    func testNoDepthFailureAndInterruptedAttemptsRemainExportableRecords() throws {
        let a = try archive(); let failed = record(); try a.createMeasurement(failed)
        try a.updateMeasurement(id: failed.id) { $0.outcome = .failed; $0.failureReason = "no_depth" }
        let pending = record(); try a.createMeasurement(pending)
        XCTAssertEqual(try a.recoverInterruptedAttempts(), 1)
        XCTAssertEqual(try a.recoverInterruptedAttempts(), 0)
        XCTAssertEqual(a.scanMeasurements().values.count, 2)
        XCTAssertEqual(try a.measurement(id: failed.id).failureReason, "no_depth")
        XCTAssertEqual(try a.measurement(id: pending.id).outcome, .interrupted)
    }
    func testCorruptFilesAreCountedAndCannotBeSilentlyOverwritten() throws {
        let a = try archive(); let r = record(); try a.createMeasurement(r)
        let broken = a.root.appendingPathComponent("measurements/"+UUID().uuidString+".json")
        try Data("{truncated".utf8).write(to: broken)
        let scan = a.scanMeasurements(); XCTAssertEqual(scan.total, 2); XCTAssertEqual(scan.issues.count, 1)
        try Data("bad".utf8).write(to: a.root.appendingPathComponent("configuration.json"))
        XCTAssertThrowsError(try a.saveConfiguration(config(), developerMode: true))
        XCTAssertEqual(try String(contentsOf: a.root.appendingPathComponent("configuration.json"), encoding: .utf8), "bad")
    }
    func testTargetCoordinatesAreImmutableInExistingMeasurementSnapshots() throws {
        let a = try archive(); let c = config()
        let target = FieldStudyTarget(studyId: c.studyId, treeId: "T", label: "1", latitude: 45, longitude: -123)
        var r = FieldMeasurementRecord(sessionId: UUID().uuidString, treeId: "T", targetId: target.id,
            targetSnapshot: target, kind: .dbh, attemptNumber: 1, configuration: c, runtimeSettings: [:])
        try a.createMeasurement(r)
        var moved = target; moved.latitude = 46; try a.saveTargets([moved])
        XCTAssertEqual(try a.measurement(id: r.id).targetSnapshot?.latitude, 45)
        r.targetSnapshot = moved
        XCTAssertThrowsError(try a.updateMeasurement(id: r.id) { $0.targetSnapshot = moved })
    }
    func testClockIncludesRetriesAndBackgroundWithoutWallClockDependence() {
        var clock = FieldMeasurementClock(now: 100)
        clock.beginAttempt(now: 102); clock.noteResult(validResult: false, now: 104)
        clock.setForeground(false, now: 105); clock.setForeground(true, now: 110)
        clock.noteResult(validResult: true, now: 112)
        clock.beginAttempt(now: 113); clock.noteResult(validResult: true, now: 115)
        clock.noteAccepted(now: 118)
        let t = clock.snapshot(now: 118)
        XCTAssertEqual(t.screenElapsedSeconds, 18); XCTAssertEqual(t.backgroundSeconds, 5)
        XCTAssertEqual(t.activeElapsedSeconds, 13); XCTAssertEqual(t.firstValidResultElapsedSeconds, 12)
        XCTAssertEqual(t.attemptFirstResultSeconds, 2); XCTAssertEqual(t.attemptElapsedSeconds, 5)
        XCTAssertEqual(t.acceptedElapsedSeconds, 18); XCTAssertTrue(t.clockValid)
    }
    func testClockRejectsNonfiniteOrReversedTimeInsteadOfReportingNegativeDuration() {
        var clock = FieldMeasurementClock(now: 10); clock.beginAttempt(now: 11)
        let bad = clock.snapshot(now: .nan); XCTAssertFalse(bad.clockValid); XCTAssertEqual(bad.screenElapsedSeconds, 1)
        let reverse = clock.snapshot(now: 9); XCTAssertFalse(reverse.clockValid); XCTAssertEqual(reverse.screenElapsedSeconds, 1)
        var nextTarget = FieldMeasurementClock(now: 1000); XCTAssertEqual(nextTarget.snapshot(now: 1000).screenElapsedSeconds, 0)
    }
    func testAIProvenanceCannotClaimAppliedWithoutAnActualMaskAndInference() {
        var ai = FieldAIProvenance(); ai.boundaryApplied = true
        XCTAssertThrowsError(try ai.validate())
        ai.inferenceRan = true; ai.maskDetected = true; ai.inferenceId = UUID().uuidString
        XCTAssertNoThrow(try ai.validate())
    }
    func testProtocolRuntimeMismatchIsExplicitNotSilentlyCompatible() {
        var c = config(); c.protocolLocked = true; c.pinnedRuntimeSettings = ["epoch": "9"]
        let r = FieldMeasurementRecord(sessionId: UUID().uuidString, treeId: "T", kind: .height,
            attemptNumber: 1, configuration: c, runtimeSettings: ["epoch": "10"])
        XCTAssertFalse(r.protocolConforms)
    }
    private struct InteropFixture: Codable {
        var configuration: FieldStudyConfiguration
        var targets: [FieldStudyTarget]
        var references: [FieldReferenceObservation]
        var measurements: [FieldMeasurementRecord]
        var protocolFingerprint: String
    }
    func testFrozenSyntheticFixtureIsValidAndRetainsItsProtocolFingerprint() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource:"field-study-v1",withExtension:"json",subdirectory:"Fixtures"))
        let fixture = try FieldStudyJSON.decoder().decode(InteropFixture.self,from:Data(contentsOf:url))
        try fixture.configuration.validate();try fixture.targets.forEach { try $0.validate() }
        try fixture.references.forEach { try $0.validate(developerMode:true) };try fixture.measurements.forEach { try $0.validate() }
        XCTAssertEqual(fixture.configuration.protocolFingerprint,fixture.protocolFingerprint)
        XCTAssertEqual(fixture.measurements[0].id,fixture.measurements[0].rawBundleId)
        XCTAssertEqual(fixture.measurements[0].referenceIds,[fixture.references[0].id])
        XCTAssertEqual(fixture.references[0].method.name,"테이프 reference")
    }
    func testSyntheticInteropFixtureRoundTrip() throws {
        var c = config(); c.referenceMethods[0].name = "테이프 reference"; c.referenceMethods[0].unit = .inch
        let target = FieldStudyTarget(studyId:c.studyId,treeId:"TREE-034",label:"034",latitude:45,longitude:-123,
            sourceLayerId:"synthetic-layer",sourceFeatureId:"1",attributes:["fixture":"synthetic"])
        let reference = FieldReferenceObservation(id:"33333333-3333-4333-8333-333333333333",targetId:target.id,
            method:c.referenceMethods[0],inputValue:10,operatorId:"OP-1",recordedAt:"2026-01-01T00:00:00.000Z")
        var r = FieldMeasurementRecord(id:"11111111-1111-4111-8111-111111111111",sessionId:"22222222-2222-4222-8222-222222222222",
            treeId:target.treeId,targetId:target.id,targetSnapshot:target,kind:.dbh,attemptNumber:2,
            configuration:c,runtimeSettings:["estimator_epoch":"9","sampling_fraction":"0.8"],startedAt:"2026-01-01T00:00:00.000Z")
        r.outcome = .result; r.selection = .accepted; r.liveValue = 25.4; r.liveSigma = 0.5
        r.rawStatus = .pending; r.rawBundleId = r.id; r.referenceIds = [reference.id]
        var clock = FieldMeasurementClock(now:100); clock.beginAttempt(now:119.27)
        clock.noteResult(validResult:true,now:121.27);clock.noteAccepted(now:123.47)
        r.timing = clock.snapshot(now:123.47)
        r.events = [.init(action:"accepted",timestamp:"2026-01-01T00:00:23.470Z")]
        let fixture = InteropFixture(configuration:c,targets:[target],references:[reference],measurements:[r],protocolFingerprint:c.protocolFingerprint)
        let bytes = try FieldStudyJSON.encoder().encode(fixture)
        let decoded = try FieldStudyJSON.decoder().decode(InteropFixture.self,from:bytes)
        XCTAssertEqual(decoded.configuration,c);XCTAssertEqual(decoded.targets,[target]);XCTAssertEqual(decoded.references,[reference])
        XCTAssertEqual(decoded.measurements,[r]);try decoded.measurements[0].validate()
        XCTAssertEqual(decoded.protocolFingerprint,c.protocolFingerprint)
        if let output = ProcessInfo.processInfo.environment["FORESTIX_FIELD_STUDY_FIXTURE_OUT"] {
            try bytes.write(to:URL(fileURLWithPath:output),options:.atomic)
        }
    }
}
