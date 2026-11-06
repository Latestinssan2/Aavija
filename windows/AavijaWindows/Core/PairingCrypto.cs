using System.Security.Cryptography;
using System.Text;

namespace Aavija.Core;

public static class PairingCrypto
{
    public const int Pbkdf2Iterations = 210000;

    public static byte[] DeriveKey(string pin, byte[] salt)
    {
        using var pbkdf2 = new Rfc2898DeriveBytes(pin, salt, Pbkdf2Iterations, HashAlgorithmName.SHA256);
        return pbkdf2.GetBytes(32);
    }

    public static byte[] Hmac(byte[] key, byte[] message)
    {
        using var mac = new HMACSHA256(key);
        return mac.ComputeHash(message);
    }

    public static byte[] ComputeProof(string pin, byte[] salt) =>
        Hmac(DeriveKey(pin, salt), salt);

    public static string Fingerprint(byte[] certBytes) =>
        Convert.ToHexString(SHA256.HashData(certBytes)).ToLowerInvariant();

    public static byte[] RandomToken()
    {
        var b = new byte[32];
        RandomNumberGenerator.Fill(b);
        return b;
    }

    public static string ToHex(byte[] bytes) => Convert.ToHexString(bytes).ToLowerInvariant();

    public static byte[] FromHex(string hex) => Convert.FromHexString(hex);
}
