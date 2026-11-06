using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Zeroconf;

namespace Aavija.Core;

public record PhonePeer(string Id, string Name, string Host, int Port);

public class PhoneDiscovery
{
    public List<PhonePeer> Peers { get; } = new();
    public event Action? Changed;

    public async Task StartAsync()
    {
        // Browse for phones advertising _gestureshare._tcp.
        var results = await ZeroconfResolver.ResolveAsync("_gestureshare._tcp.local.");
        foreach (var r in results)
        {
            foreach (var svc in r.Services.Values)
            {
                Peers.Add(new PhonePeer(r.DisplayName, r.DisplayName, r.IPAddress, svc.Port));
            }
        }
        Changed?.Invoke();
    }

    public void Stop() { Peers.Clear(); }
}
