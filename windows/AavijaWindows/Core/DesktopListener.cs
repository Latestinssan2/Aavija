using System;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

namespace Aavija.Core;

public class DesktopListener : IDisposable
{
    private readonly ListenerStore _store;
    private readonly X509Certificate2 _cert;
    private readonly string _fingerprint;
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;

    public int Port { get; private set; }
    public event Action<string>? StatusChanged;

    public DesktopListener(ListenerStore store)
    {
        _store = store;
        using var rsa = System.Security.Cryptography.RSA.Create(2048);
        var req = new System.Security.Cryptography.X509Certificates.CertificateRequest("CN=Aavija", rsa, System.Security.Cryptography.HashAlgorithmName.SHA256, System.Security.Cryptography.RSASignaturePadding.Pkcs1);
        _cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        _fingerprint = PairingCrypto.Fingerprint(_cert.RawData);
    }

    public void Start(int port = 0)
    {
        Stop();
        _cts = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, port);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        StatusChanged?.Invoke($"Listener started on port {Port}");
        _ = Task.Run(() => AcceptLoopAsync(_cts.Token));
    }

    public void Stop()
    {
        try { _cts?.Cancel(); } catch { }
        try { _listener?.Stop(); } catch { }
        _listener = null;
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener is not null)
        {
            try
            {
                var client = await _listener.AcceptTcpClientAsync(ct);
                _ = Task.Run(() => HandleClientAsync(client, ct));
            }
            catch (OperationCanceledException) { break; }
            catch { }
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken ct)
    {
        using (client)
        {
            try
            {
                var stream = new SslStream(client.GetStream(), false);
                await stream.AuthenticateAsServerAsync(_cert);
                using (stream)
                {
                    var salt = Guid.NewGuid().ToByteArray();
                    // Send challenge
                    await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "challenge", salt = PairingCrypto.ToHex(salt) }));

                    var line = await ReadLineAsync(stream);
                    if (string.IsNullOrWhiteSpace(line)) return;

                    using var doc = JsonDocument.Parse(line);
                    var type = doc.RootElement.GetProperty("type").GetString();

                    if (type == "pair")
                    {
                        var name = doc.RootElement.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                        var proofHex = doc.RootElement.TryGetProperty("proof", out var p) ? p.GetString() ?? "" : "";
                        var combinedSalt = salt;
                        // verify PIN
                        var pin = _store.Pin;
                        if (string.IsNullOrEmpty(pin) || pin.Length != 6)
                        {
                            await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "rejected", reason = "no pin entered" }));
                            return;
                        }
                        byte[] proof;
                        try { proof = PairingCrypto.FromHex(proofHex); } catch { await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "rejected", reason = "proof not hex" })); return; }
                        var expected = PairingCrypto.ComputeProof(pin, combinedSalt);
                        if (!CryptographicOperations.FixedTimeEquals(expected, proof))
                        {
                            await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "rejected", reason = "wrong pin" }));
                            return;
                        }
                        var token = _store.MintToken(name);
                        await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "paired", token }));
                        StatusChanged?.Invoke($"Paired with {name}");
                        return;
                    }

                    if (type == "auth")
                    {
                        var tokenHex = doc.RootElement.TryGetProperty("token", out var t) ? t.GetString() ?? "" : "";
                        if (!_store.IsPairedToken(tokenHex))
                        {
                            await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "rejected", reason = "unknown token" }));
                            return;
                        }
                        await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "authenticated" }));
                        // Handle receive/file
                        var next = await ReadLineAsync(stream);
                        if (string.IsNullOrWhiteSpace(next)) return;
                        using var doc2 = JsonDocument.Parse(next);
                        if (doc2.RootElement.TryGetProperty("type", out var t2) && t2.GetString() == "receive")
                        {
                            // No queued file in this minimal listener.
                            await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "idle" }));
                        }
                        else if (doc2.RootElement.TryGetProperty("type", out var t3) && t3.GetString() == "file")
                        {
                            // Receive file into Downloads/Aavija
                            var filename = doc2.RootElement.GetProperty("filename").GetString()!;
                            var size = doc2.RootElement.GetProperty("size").GetInt64();
                            var sha = doc2.RootElement.GetProperty("sha256").GetString()!;
                            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Aavija");
                            Directory.CreateDirectory(dir);
                            var path = Path.Combine(dir, filename);
                            var buf = new byte[size];
                            int read = 0;
                            while (read < size)
                            {
                                int n = await stream.ReadAsync(buf, read, (int)(size - read));
                                if (n == 0) throw new IOException("eof");
                                read += n;
                            }
                            var got = PairingCrypto.ToHex(SHA256.HashData(buf));
                            if (got.Equals(sha, StringComparison.OrdinalIgnoreCase))
                            {
                                await File.WriteAllBytesAsync(path, buf);
                                await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "received" }));
                                StatusChanged?.Invoke($"Received {filename}");
                            }
                            else
                            {
                                await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "failed", reason = "hash mismatch" }));
                            }
                        }
                        return;
                    }

                    if (type == "ping")
                    {
                        // Only trusted if token present
                        var tokenHex = doc.RootElement.TryGetProperty("token", out var tp) ? tp.GetString() ?? "" : "";
                        if (_store.IsPairedToken(tokenHex))
                            await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "pong", outgoing = 0, gestureGate = false }));
                        else
                            await WriteLineAsync(stream, JsonSerializer.Serialize(new { type = "pong", outgoing = 0, gestureGate = false }));
                        return;
                    }
                }
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Listener error: {ex.Message}");
            }
        }
    }

    private static async Task<string?> ReadLineAsync(Stream stream)
    {
        var sb = new StringBuilder();
        var buf = new byte[1];
        while (true)
        {
            int n = await stream.ReadAsync(buf, 0, 1);
            if (n == 0) return null;
            if (buf[0] == (byte)'\n') return sb.ToString().TrimEnd('\r');
            sb.Append((char)buf[0]);
            if (sb.Length > 4096) return null;
        }
    }

    private static Task WriteLineAsync(Stream stream, string line) =>
        stream.WriteAsync(System.Text.Encoding.UTF8.GetBytes(line + "\n")).AsTask();

    public void Dispose() => Stop();
}
