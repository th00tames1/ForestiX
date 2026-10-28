import XCTest
import Models

final class FieldTargetApplicationBindingTests: XCTestCase {
    private let plot = UUID().uuidString
    private var target: FieldStudyTarget { .init(studyId: "SYNTHETIC",treeId: "0003",label: "003") }
    private var other: FieldStudyTarget { .init(studyId: "SYNTHETIC",treeId: "0004",label: "004") }
    private func pair(number: Int?,plot: String?,kind: FieldStudyKind = .dbh,target: FieldStudyTarget? = nil,id: String = UUID().uuidString)
        throws -> (FieldApplicationReading,FieldMeasurementRecord) {
        let target = target ?? self.target
        var c = FieldStudyConfiguration(); c.enabled = true; c.studyId = target.studyId; c.operatorId = "TEST"
        var s = FieldMeasurementSession(now: 0)
        try s.begin(id: id,configuration: c,target: target,kind: kind,runtime: [:],references: [],rawRequested: false,aiEnabled: false,typed: false,now: 1)
        try s.finish(id: id,value: 25,sigma: 1,valid: true,reason: nil,ai: .init(),now: 2)
        try s.accept(id: id,resultRecordId: id,resultStore: "quick_history",now: 3)
        return (.init(id: id,kind: kind,plotId: plot,treeNumber: number),try XCTUnwrap(s.current))
    }
    func testExternalNumericStringNeverBecomesLocalNumberWithoutAnExactResultLink() throws {
        let row = FieldApplicationReading(id: UUID().uuidString,kind: nil,plotId: plot,treeNumber: 12)
        let scope = try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [row],records: [])
        XCTAssertEqual(scope.treeNumber,13); XCTAssertFalse(scope.existing); XCTAssertEqual(target.treeId,"0003")
        XCTAssertTrue(scope.sourceResultIds.isEmpty)
    }
    func testDbhAndHeightReuseOneExactApplicationTreeWithCaseInsensitiveUUIDs() throws {
        let (dbh,d) = try pair(number: 7,plot: plot,kind: .dbh)
        let (height,h) = try pair(number: 7,plot: plot.lowercased(),kind: .height)
        let row = FieldApplicationReading(id: dbh.id.lowercased(),kind: dbh.kind,plotId: dbh.plotId,treeNumber: dbh.treeNumber)
        let scope = try FieldTargetApplicationBinding.resolve(target: target,plotId: plot.lowercased(),readings: [row,height],records: [d,h])
        XCTAssertEqual(scope.treeNumber,7); XCTAssertTrue(scope.existing)
        XCTAssertEqual(Set(scope.sourceResultIds),Set([dbh.id.lowercased(),height.id.lowercased()]))
    }
    func testDeletedPendingFailedAndCruiseRowsCannotSupplyAQuickBinding() throws {
        let (row,record) = try pair(number: 5,plot: plot)
        var pending = record; pending.selection = .pending
        var failed = record; failed.outcome = .failed
        var cruise = record; cruise.resultStore = "cruise"
        for records in [[FieldMeasurementRecord](),[pending],[failed],[cruise]] {
            let s = try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [row],records: records)
            XCTAssertEqual(s.treeNumber,6); XCTAssertFalse(s.existing)
        }
        XCTAssertFalse(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [],records: [record]).existing)
    }
    func testBindingIsPlotScopedAndFreshCounterIncludesOtherPlots() throws {
        let otherPlot = UUID().uuidString
        let (a,ar) = try pair(number: 2,plot: plot), (b,br) = try pair(number: 8,plot: otherPlot)
        XCTAssertEqual(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [a,b],records: [ar,br]).treeNumber,2)
        XCTAssertEqual(try FieldTargetApplicationBinding.resolve(target: target,plotId: otherPlot,readings: [a,b],records: [ar,br]).treeNumber,8)
        XCTAssertEqual(try FieldTargetApplicationBinding.resolve(target: other,plotId: plot,readings: [a,b],records: [ar,br]).treeNumber,9)
    }
    func testConflictingLocalNumbersOrTargetsAreNeverGuessedThrough() throws {
        let (a,ar) = try pair(number: 2,plot: plot), (b,br) = try pair(number: 8,plot: plot)
        XCTAssertThrowsError(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [a,b],records: [ar,br]))
        let (foreign,fr) = try pair(number: 2,plot: plot,target: other)
        XCTAssertThrowsError(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [a,foreign],records: [ar,fr]))
    }
    func testDuplicatedOrMisboundResultUUIDAndKindAreRejected() throws {
        let (row,record) = try pair(number: 2,plot: plot)
        XCTAssertThrowsError(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [row,row],records: [record]))
        let (_,foreign) = try pair(number: 2,plot: plot,target: other,id: row.id)
        XCTAssertThrowsError(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [row],records: [record,foreign]))
        let wrongKind = FieldApplicationReading(id: row.id,kind: .height,plotId: plot,treeNumber: 2)
        XCTAssertThrowsError(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [wrongKind],records: [record]))
        var wrongAttempt = record; wrongAttempt.id = UUID().uuidString
        XCTAssertThrowsError(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [row],records: [wrongAttempt]))
    }
    func testInvalidNumbersAndMalformedUUIDNeverOverflowOrInventBindings() throws {
        let full = FieldApplicationReading(id: UUID().uuidString,kind: .dbh,plotId: plot,treeNumber: 2_147_483_647)
        XCTAssertThrowsError(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [full],records: []))
        let (unscoped,record) = try pair(number: nil,plot: plot)
        XCTAssertThrowsError(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [unscoped],records: [record]))
        let corrupt = FieldApplicationReading(id: "not-a-uuid",kind: nil,plotId: plot,treeNumber: 3)
        XCTAssertThrowsError(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [corrupt],records: []))
    }
    func testNilPlotAndSupersededButStillPresentExplicitLinksRemainExact() throws {
        let (row,record) = try pair(number: 6,plot: nil)
        var superseded = record; superseded.selection = .superseded
        XCTAssertTrue(try FieldTargetApplicationBinding.resolve(target: target,plotId: nil,readings: [row],records: [superseded]).existing)
        XCTAssertFalse(try FieldTargetApplicationBinding.resolve(target: target,plotId: plot,readings: [row],records: [record]).existing)
    }
}
