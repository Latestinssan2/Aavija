import CommonCrypto
import Foundation

/// Writes a received file (from phone → Mac) to ~/Downloads/GestureShare.
/// Matches the phone's safety rules: reject path tricks (already handled), write only to that folder,
/// verify SHA-256, delete partial file on mismatch.
struct FileReceiver {
    static let subdir = "GestureShare"

    enum Result {
        case ok(path: String)
        case failed(reason: String)
    }

    static func write(name: String, size: Int, sha256: String, data: Data) -> Result {
        guard !name.isEmpty, name.count <= 255 else {
            return .failed(reason: "unsafe filename")
        }
        guard name != "." && name != ".." else {
            return .failed(reason: "unsafe filename")
        }
        guard !name.contains("/") && !name.contains("\\") else {
            return .failed(reason: "unsafe filename")
        }
        for c in name.unicodeScalars {
            if c.value < 0x20 {
                return .failed(reason: "unsafe filename")
            }
        }
        guard sha256.count == 64 else {
            return .failed(reason: "bad sha256")
        }
        guard size >= 0 && size <= 200 * 1024 * 1024 else {
            return .failed(reason: "bad size")
        }

        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let dir = downloads.appendingPathComponent(subdir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: nil)
        let dest = dir.appendingPathComponent(name)

        // compute sha256
        let computed = computeSHA256(data)
        if computed.lowercased() != sha256.lowercased() {
            return .failed(reason: "hash mismatch")
        }
        do {
            try data.write(to: dest, options: [.atomic])
            return .ok(path: dest.path)
        } catch {
            return .failed(reason: "cannot write: \(error.localizedDescription)")
        }
    }

    private static func computeSHA256(_ data: Data) -> String {
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &digest)
        }
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}