using System;
using System.IO;
using System.Text.Json;

namespace Aavija.Core;

public record PairedPeer(string Id, string Name, string TokenHex, string Fingerprint, string Host, int Port);

public class PairingStore
{
    private readonly string _path;
    private List<PairedPeer> _peers = new();
    public string? ActivePeerId { get; set; }

    public PairingStore()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Aavija");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "peers.json");
        Load();
    }

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var doc = JsonDocument.Parse(File.ReadAllText(_path));
            ActivePeerId = doc.RootElement.GetProperty("active").GetString();
            foreach (var p in doc.RootElement.GetProperty("peers").EnumerateArray())
            {
                _peers.Add(new PairedPeer(
                    p.GetProperty("id").GetString()!,
                    p.GetProperty("name").GetString()!,
                    p.GetProperty("token").GetString()!,
                    p.GetProperty("fp").GetString()!,
                    p.GetProperty("host").GetString()!,
                    p.GetProperty("port").GetInt32()));
            }
        }
        catch { }
    }

    public void Save()
    {
        var obj = new { active = ActivePeerId, peers = _peers };
        File.WriteAllText(_path, JsonSerializer.Serialize(obj));
    }

    public IEnumerable<PairedPeer> Peers => _peers;
    public PairedPeer? Find(string id) => _peers.FirstOrDefault(p => p.Id == id);
    public void Upsert(PairedPeer peer)
    {
        _peers.RemoveAll(p => p.Id == peer.Id);
        _peers.Add(peer);
        ActivePeerId = peer.Id;
        Save();
    }
    public void Forget(string id)
    {
        _peers.RemoveAll(p => p.Id == id);
        if (ActivePeerId == id) ActivePeerId = _peers.FirstOrDefault()?.Id;
        Save();
    }
}
