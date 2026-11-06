using System.IO;
using System.Linq;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;

namespace Aavija.Core;

public class PhoneClient
{
    private readonly PairingStore _store;
    private readonly Func<string> _ourName;
    public event Action<string>? StatusChanged;

    public PhoneClient(PairingStore store, Func<string> ourName)
    {
        _store = store;
        _ourName = ourName;
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
        stream.WriteAsync(Encoding.UTF8.GetBytes(line + "\n")).AsTask();

    private async Task<(TcpClient Client, SslStream Stream, string Fingerprint)> ConnectAsync(PhonePeer peer)
    {
        var client = new TcpClient();
        await client.ConnectAsync(peer.Host, peer.Port);
        var ssl = new SslStream(client.GetStream(), false, (_, _, _, _) => true);
        await ssl.AuthenticateAsClientAsync(peer.Host);
        var cert = new X509Certificate2(ssl.RemoteCertificate!);
        var fp = PairingCrypto.Fingerprint(cert.RawData);
        return (client, ssl, fp);
    }

    public async Task<(bool Ok, string? Token)> PairAsync(PhonePeer peer, string pin, string requestId)
    {
        try
        {
            var (client, stream, fp) = await ConnectAsync(peer);
            using (client) using (stream)
            {
                var salt = Protocol.ParseChallengeSalt(await ReadLineAsync(stream));
                if (salt is null) return (false, null);
                var combined = salt.Concat(Convert.FromHexString(fp)).ToArray();
                var proof = PairingCrypto.ComputeProof(pin, combined);
                await WriteLineAsync(stream, Protocol.PairMessage(_ourName(), PairingCrypto.ToHex(proof), requestId));
                var verdict = Protocol.ParseVerdict(await ReadLineAsync(stream));
                if (verdict?.IsPaired == true && verdict.TokenHex is not null)
                {
                    _store.Upsert(new PairedPeer(peer.Id, peer.Name, verdict.TokenHex, fp, peer.Host, peer.Port));
                    StatusChanged?.Invoke($"Paired with {peer.Name}");
                    return (true, verdict.TokenHex);
                }
                StatusChanged?.Invoke($"Pairing failed: {verdict?.Reason ?? verdict?.Type}");
                return (false, null);
            }
        }
        catch (Exception ex)
        {
            StatusChanged?.Invoke($"Pairing error: {ex.Message}");
            return (false, null);
        }
    }

    public async Task PushFileAsync(PhonePeer peer, string filePath)
    {
        var saved = _store.Find(peer.Id);
        if (saved is null) { StatusChanged?.Invoke("Not paired with this phone."); return; }
        var bytes = await File.ReadAllBytesAsync(filePath);
        var sha = PairingCrypto.ToHex(SHA256.HashData(bytes));
        var name = Path.GetFileName(filePath);

        try
        {
            var (client, stream, _) = await ConnectAsync(peer);
            using (client) using (stream)
            {
                await ReadLineAsync(stream); // challenge
                await WriteLineAsync(stream, Protocol.AuthMessage(saved.TokenHex));
                var auth = Protocol.ParseVerdict(await ReadLineAsync(stream));
                if (auth?.IsAuthenticated != true) { StatusChanged?.Invoke("Auth failed."); return; }

                var header = JsonSerializer.Serialize(new { type = "file", filename = name, size = bytes.Length, sha256 = sha });
                await WriteLineAsync(stream, header);
                await stream.WriteAsync(bytes);
                await stream.FlushAsync();
                var reply = Protocol.ParseVerdict(await ReadLineAsync(stream));
                StatusChanged?.Invoke(reply?.Type == "received" ? $"Sent {name} ✓" : $"Send failed: {reply?.Reason}");
            }
        }
        catch (Exception ex) { StatusChanged?.Invoke($"Send error: {ex.Message}"); }
    }

    public async Task<string?> PullFileAsync(PhonePeer peer, string saveDir)
    {
        var saved = _store.Find(peer.Id);
        if (saved is null) { StatusChanged?.Invoke("Not paired with this phone."); return null; }

        try
        {
            var (client, stream, _) = await ConnectAsync(peer);
            using (client) using (stream)
            {
                await ReadLineAsync(stream); // challenge
                await WriteLineAsync(stream, Protocol.AuthMessage(saved.TokenHex));
                var auth = Protocol.ParseVerdict(await ReadLineAsync(stream));
                if (auth?.IsAuthenticated != true) { StatusChanged?.Invoke("Auth failed."); return null; }

                await WriteLineAsync(stream, Protocol.ReceiveRequest());
                var headerLine = await ReadLineAsync(stream);
                if (string.IsNullOrWhiteSpace(headerLine)) return null;

                using var doc = JsonDocument.Parse(headerLine);
                if (doc.RootElement.TryGetProperty("type", out var t) && t.GetString() == "idle")
                {
                    StatusChanged?.Invoke("Nothing queued on the phone.");
                    return null;
                }
                var filename = doc.RootElement.GetProperty("filename").GetString()!;
                var size = doc.RootElement.GetProperty("size").GetInt64();
                var sha = doc.RootElement.GetProperty("sha256").GetString()!;

                var buffer = new byte[size];
                int read = 0;
                while (read < size)
                {
                    int n = await stream.ReadAsync(buffer, read, (int)(size - read));
                    if (n == 0) throw new IOException("Unexpected end of stream");
                    read += n;
                }
                var got = PairingCrypto.ToHex(SHA256.HashData(buffer));
                if (!got.Equals(sha, StringComparison.OrdinalIgnoreCase))
                {
                    StatusChanged?.Invoke("Hash mismatch — file discarded.");
                    return null;
                }
                Directory.CreateDirectory(saveDir);
                var path = Path.Combine(saveDir, filename);
                await File.WriteAllBytesAsync(path, buffer);
                StatusChanged?.Invoke($"Saved {filename}");
                return path;
            }
        }
        catch (Exception ex) { StatusChanged?.Invoke($"Receive error: {ex.Message}"); return null; }
    }
}
