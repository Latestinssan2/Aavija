using System;

namespace Aavija.Core;

// mDNS advertising of this Windows machine as a discoverable "Mac"-compatible
// target. The full Makaretu-based implementation is pending; for now this is a
// stub so the project compiles and runs. Phones can still reach this machine
// by IP address in the meantime.
public class DesktopAdvertiser : IDisposable
{
    public void Start(string name, int port, string id) { }
    public void Stop() { }
    public void Dispose() { }
}
