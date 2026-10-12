import CommonCrypto
import CryptoKit
import Foundation
import Security

/// The Mac half of Aavija's PIN-pairing (Phase 3b).
///
/// This MIRRORS the phone's `PairingCrypto` byte-for-byte. The two sides must
/// agree on the hash (SHA-256), the KDF (PBKDF2-HMAC-SHA256), the iteration
/// count, and the exact byte layout — or pairing silently fails. Every constant
/// here has a twin on the Android side; change one, change both.
///
/// ### The idea in one line
/// The Mac proves it knows the 6-digit PIN by returning
/// `HMAC(PBKDF2(pin, salt||fp), salt||fp)`, where `fp` is the fingerprint of
/// the phone certificate THIS Mac saw on the wire. A relay that swapped in its
/// own certificate gets a different `fp`, so its proof won't match — that's the
/// man-in-the-middle defense.
enum Pairing {

    /// MUST match Android's `PairingCrypto.PBKDF2_ITERATIONS`.
    static let pbkdf2Iterations: UInt32 = 210_000

    /// A random 6-digit PIN, zero-padded (e.g. "042913"). Shown to the user,
    /// typed into the phone, and used exactly ONCE to bootstrap a session token.
    static func generatePin() -> String {
        var bytes = [UInt8](repeating: 0, count: 4)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let n = bytes.withUnsafeBytes { $0.load(as: UInt32.self) } % 1_000_000
        return String(format: "%06d", n)
    }

    /// PBKDF2-HMAC-SHA256. Identical to the phone's `deriveKey`.
    static func deriveKey(pin: String, salt: Data) -> Data {
        var derived = [UInt8](repeating: 0, count: 32)
        let password = pin.utf8CString // [CChar], null-terminated
        let saltBytes = Array(salt)

        let status: Int32 = password.withUnsafeBufferPointer { passwordBuf in
            saltBytes.withUnsafeBufferPointer { saltBuf in
                derived.withUnsafeMutableBytes { derivedBuf -> Int32 in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBuf.baseAddress,
                        passwordBuf.count - 1, // drop the null terminator
                        saltBuf.baseAddress,
                        saltBuf.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        pbkdf2Iterations,
                        derivedBuf.bindMemory(to: UInt8.self).baseAddress,
                        derivedBuf.count
                    )
                }
            }
        }
        precondition(status == kCCSuccess, "PBKDF2 failed: \(status)")
        return Data(derived)
    }

    /// HMAC-SHA256. Identical to the phone's `hmac`.
    static func hmac(_ key: Data, _ message: Data) -> Data {
        let symmetricKey = SymmetricKey(data: key)
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: symmetricKey)
        return Data(mac)
    }

    /// The pairing proof. `salt` is the phone's challenge bytes; `fingerprint`
    /// is the hex SHA-256 of the phone certificate THIS Mac saw on the wire.
    static func proof(pin: String, salt: Data, fingerprint: String) -> Data {
        let combined = salt + Data(fingerprint.utf8)
        let key = deriveKey(pin: pin, salt: combined)
        return hmac(key, combined)
    }

    /// Lowercase hex, matching the phone's `toHex`.
    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    /// Decode even-length hex. Throws on anything malformed so a bad value is
    /// REJECTED instead of silently turning into wrong bytes.
    static func fromHex(_ hex: String) throws -> Data {
        guard hex.count % 2 == 0 else { throw PairingError.badHex }
        var bytes = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else {
                throw PairingError.badHex
            }
            bytes.append(byte)
            index = next
        }
        return bytes
    }

    /// SHA-256 of raw certificate bytes as lowercase hex — the fingerprint.
    /// Matches the phone's `PairingCrypto.fingerprint`.
    static func fingerprint(ofCertificateData data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Pull the peer (phone) certificate's fingerprint out of a live TLS
    /// handshake. Called from the TLS verify block. Returns the LEAF cert's
    /// fingerprint (the phone's own cert), or nil if it wasn't accessible.
    static func peerFingerprint(from metadata: sec_protocol_metadata_t) -> String? {
        var result: String?
        var taken = false
        // The handler runs once per cert in the chain; we want the first one.
        sec_protocol_metadata_access_peer_certificate_chain(metadata) { cert in
            guard !taken else { return }
            taken = true
            // sec_certificate_copy_ref hands back an Unmanaged reference, so we
            // must take ownership explicitly (.takeRetainedValue()).
            let secCert = sec_certificate_copy_ref(cert).takeRetainedValue()
            let certData = SecCertificateCopyData(secCert) as Data
            result = fingerprint(ofCertificateData: certData)
        }
        return result
    }

    enum PairingError: Error {
        case badHex
    }
}

/// Where the Mac remembers a paired phone: the PINNED certificate fingerprint
/// and the SESSION TOKEN the phone minted. Both are needed on every future
/// connection — the fingerprint to recognize the phone, the token to prove
/// we're a paired Mac.
///
/// Backed by UserDefaults for this v1. NOTE: the token is a secret, so the
/// production-grade home is the Keychain — flagged here as a known, deliberate
/// simplification for a local-only student app (someone with filesystem access
/// to your own Mac is a different threat than someone on the Wi-Fi).
final class PairingStore {

    private let defaults: UserDefaults
    private let fpKey = "aavija.pinnedFingerprint"
    private let tokenKey = "aavija.sessionToken"
    private let nameKey = "aavija.pairedPhoneName"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The phone cert fingerprint we trust. nil = not yet paired.
    var pinnedFingerprint: String? {
        get { defaults.string(forKey: fpKey) }
        set { defaults.set(newValue, forKey: fpKey) }
    }

    /// The session token the phone gave us. nil = not yet paired.
    var sessionToken: Data? {
        get { defaults.data(forKey: tokenKey) }
        set { defaults.set(newValue, forKey: tokenKey) }
    }

    /// The paired phone's Bonjour (mDNS) name, so the panel can NAME it even
    /// when the phone is offline and there's nothing to match against.
    var pairedPhoneName: String? {
        get { defaults.string(forKey: nameKey) }
        set { defaults.set(newValue, forKey: nameKey) }
    }

    var isPaired: Bool {
        pinnedFingerprint != nil && sessionToken != nil
    }

    func clear() {
        pinnedFingerprint = nil
        sessionToken = nil
        pairedPhoneName = nil
    }
}
