import XCTest
import CoreLocation
@testable import Detour

/// The first Swift unit test in the repo (#124). Covers `LocationBroadcast`'s
/// fix-age arithmetic — a delivery-lag back-date, two clamps and a unit
/// conversion — which is exactly the shape review is bad at. `LocationBroadcast`
/// reads two clocks; these drive it with a fake one so the age is exact rather
/// than "roughly now".
@MainActor
final class LocationBroadcastTests: XCTestCase {

    private func fix(at timestamp: Date) -> CLLocation {
        CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 50.85, longitude: 4.35),
            altitude: 0,
            horizontalAccuracy: 5,
            verticalAccuracy: 5,
            timestamp: timestamp
        )
    }

    /// Fake clock. `uptime` is read from a mutable box so a test can advance it
    /// between `publish` and `lastSample`.
    private func fixedClock(uptime: @escaping () -> TimeInterval, wall: Date) -> LocationBroadcast.Clock {
        LocationBroadcast.Clock(uptime: uptime, wallNow: { wall })
    }

    func testNilBeforeFirstFix() {
        XCTAssertNil(LocationBroadcast(clock: .system).lastSample)
    }

    func testFreshFixReadsZeroAge() {
        let wall = Date(timeIntervalSince1970: 10_000)
        let b = LocationBroadcast(clock: fixedClock(uptime: { 1000 }, wall: wall))
        b.publish(fix(at: wall)) // delivered the instant it was taken
        XCTAssertEqual(b.lastSample?.ageMs, 0)
    }

    func testAgeAdvancesWithTheUptimeClock() {
        let wall = Date(timeIntervalSince1970: 10_000)
        var uptime: TimeInterval = 1000
        let b = LocationBroadcast(clock: fixedClock(uptime: { uptime }, wall: wall))
        b.publish(fix(at: wall))
        uptime = 1005 // five seconds pass
        XCTAssertEqual(b.lastSample?.ageMs, 5000)
    }

    func testDeliveryLagBackDatesTheFix() {
        let wall = Date(timeIntervalSince1970: 10_000)
        let b = LocationBroadcast(clock: fixedClock(uptime: { 1000 }, wall: wall))
        // CoreLocation took the reading 2 s before handing it to us.
        b.publish(fix(at: wall.addingTimeInterval(-2)))
        XCTAssertEqual(b.lastSample?.ageMs, 2000)
    }

    func testFixStampedInTheFutureClampsToZero() {
        let wall = Date(timeIntervalSince1970: 10_000)
        let b = LocationBroadcast(clock: fixedClock(uptime: { 1000 }, wall: wall))
        // A clock correction landing inside the delivery window.
        b.publish(fix(at: wall.addingTimeInterval(5)))
        XCTAssertEqual(b.lastSample?.ageMs, 0)
    }

    func testUptimeGoingBackwardsClampsToZero() {
        let wall = Date(timeIntervalSince1970: 10_000)
        var uptime: TimeInterval = 1000
        let b = LocationBroadcast(clock: fixedClock(uptime: { uptime }, wall: wall))
        b.publish(fix(at: wall))
        uptime = 999 // should never happen, but must not report a negative age
        XCTAssertEqual(b.lastSample?.ageMs, 0)
    }
}
