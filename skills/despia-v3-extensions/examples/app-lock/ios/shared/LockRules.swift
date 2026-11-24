import Foundation

enum LockFailure: Error, LocalizedError {
    case invalid(String), unavailable(String), missing(String), platform(String)
    var errorDescription: String? {
        switch self { case .invalid(let message), .unavailable(let message), .missing(let message), .platform(let message): return message }
    }
}


enum LockRules {
    static func identifier(_ identifier: String) throws -> String {
        guard !identifier.isEmpty, identifier.unicodeScalars.count <= 128 else { throw LockFailure.invalid("Identifiers must contain 1 to 128 Unicode code points") }
        return identifier
    }
    static func checkDomainUnion(_ domains: [[String]]) throws {
        guard Set(domains.flatMap { $0 }).count <= 50 else { throw LockFailure.platform("Apple limits the effective explicit website filter to 50 domains across active groups") }
    }
    static func domain(_ input: String) throws -> String {
        var domain = input.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if domain.hasSuffix(".") { domain.removeLast() }
        let labels = domain.split(separator: ".", omittingEmptySubsequences: false)
        guard domain.utf8.count <= 253, labels.count >= 2,
              labels.allSatisfy({ label in !label.isEmpty && label.utf8.count <= 63 && label.first != "-" && label.last != "-" && label.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0 == 45 } }) else {
            throw LockFailure.invalid("Use a DNS hostname, without a scheme, path, port or wildcard; encode international names as punycode")
        }
        return domain
    }
}
