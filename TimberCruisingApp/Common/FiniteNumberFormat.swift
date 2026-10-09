import Foundation

/// Display unavailable numerical results without presenting NaN as a measurement.
/// CSV uses an empty field instead; this helper is for screens and reports.
public func finiteNumberFormat(_ format: String, _ arguments: CVarArg...) -> String {
    for value in arguments {
        if let number = value as? Double, !number.isFinite { return "Unavailable" }
        if let number = value as? Float, !number.isFinite { return "Unavailable" }
    }
    return String(format: format, arguments: arguments)
}
