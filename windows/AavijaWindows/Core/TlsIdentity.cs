using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Aavija.Core;

public static class TlsIdentity
{
    public static X509Certificate2 CreateSelfSigned(out string fingerprint)
    {
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest("CN=Aavija", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(true, false, 0, true));
        var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        fingerprint = Convert.ToHexString(SHA256.HashData(cert.GetCertHash())).ToLowerInvariant();
        return cert;
    }
}
