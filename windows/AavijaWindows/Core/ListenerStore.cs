using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace Aavija.Core;

public class ListenerStore
{
    private readonly string _path;
    private readonly Dictionary<string, string> _tokens = new(); // tokenHex -> clientName
    private string? _pairedName;
    private string? _pin;
    public string? Pin { get => _pin; set => _pin = value; }

    public ListenerStore()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Aavija");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "listener.json");
        Load();
    }

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(_path));
            _pairedName = doc.RootElement.TryGetProperty("pairedName", out var pn) ? pn.GetString() : null;
            if (doc.RootElement.TryGetProperty("tokens", out var toks))
            {
                foreach (var p in toks.EnumerateObject())
                    _tokens[p.Name] = p.Value.GetString() ?? "";
            }
        }
        catch { }
    }

    public void Save()
    {
        var obj = new { pairedName = _pairedName, tokens = _tokens };
        File.WriteAllText(_path, JsonSerializer.Serialize(obj));
    }

    public bool IsPairedToken(string tokenHex) => _tokens.ContainsKey(tokenHex);

    public string MintToken(string clientName)
    {
        var token = PairingCrypto.ToHex(PairingCrypto.RandomToken());
        _tokens[token] = clientName;
        _pairedName = clientName;
        Save();
        return token;
    }

    public string? PairedName => _pairedName;
}
