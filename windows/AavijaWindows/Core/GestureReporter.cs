using System;

namespace Aavija.Core;

public class GestureReporter
{
    private long _nextId;
    private string? _lastType;
    private long _lastAtMs;
    private long _lastId;
    private readonly Func<long> _clock;
    private readonly long _windowMs;

    public GestureReporter(Func<long> clock, long windowMs = 10000)
    {
        _clock = clock; _windowMs = windowMs;
    }

    public void Record(GestureEventType type, long atMs = -1)
    {
        if (atMs < 0) atMs = _clock();
        lock (this)
        {
            _nextId++;
            _lastType = type == GestureEventType.Grab ? "grab" : "release";
            _lastAtMs = atMs;
            _lastId = _nextId;
        }
    }

    public GestureOffer? Offer(long nowMs = -1)
    {
        if (nowMs < 0) nowMs = _clock();
        lock (this)
        {
            if (_lastType is null) return null;
            var age = nowMs - _lastAtMs;
            if (age < 0 || age > _windowMs) return null;
            return new GestureOffer(_lastType, _lastId, age);
        }
    }

    public void Reset()
    {
        lock (this) { _lastType = null; _lastAtMs = 0; _lastId = 0; }
    }
}
