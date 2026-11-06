using System;
using System.Collections.Generic;
using System.Linq;

namespace Aavija.Core;

public record HandPoint(float X, float Y, float Z = 0f);

public enum HandPose { Open, Fist, Unknown }

public enum GestureEventType { Grab, Release }

public class HandGestureMachine
{
    private readonly int _windowSize;
    private readonly int _minStable;
    private readonly long _minHoldMs;
    private readonly Queue<HandPose> _recent = new();
    private HandPose _pose = HandPose.Unknown;
    private long? _lastEventAt;

    public HandGestureMachine(int windowSize = 5, int minStable = 4, long minHoldMs = 500)
    {
        _windowSize = windowSize; _minStable = minStable; _minHoldMs = minHoldMs;
    }

    public HandPose CurrentPose => _pose;

    public void Reset()
    {
        _recent.Clear();
        _pose = HandPose.Unknown;
        _lastEventAt = null;
    }

    public GestureEventType? OnFrame(List<HandPoint> points, long atMs)
    {
        _recent.Enqueue(Classify(points));
        while (_recent.Count > _windowSize) _recent.Dequeue();
        if (_recent.Count < _windowSize) return null;

        int fists = _recent.Count(p => p == HandPose.Fist);
        int opens = _recent.Count(p => p == HandPose.Open);
        HandPose decided = (fists >= _minStable) ? HandPose.Fist
            : (opens >= _minStable) ? HandPose.Open : HandPose.Unknown;
        if (decided == HandPose.Unknown || decided == _pose) return null;

        var previous = _pose;
        _pose = decided;
        GestureEventType? type = (previous == HandPose.Open && decided == HandPose.Fist) ? GestureEventType.Grab
            : (previous == HandPose.Fist && decided == HandPose.Open) ? GestureEventType.Release : null;
        if (type is null) return null;
        if (_lastEventAt.HasValue && atMs - _lastEventAt.Value < _minHoldMs) return null;
        _lastEventAt = atMs;
        return type;
    }

    public static HandPose Classify(List<HandPoint> points)
    {
        if (points.Count < 21) return HandPose.Unknown;
        var wrist = points[0];
        int extended = 0;
        int[] fingers = { 8, 6, 12, 10, 16, 14, 20, 18 };
        for (int i = 0; i < 4; i++)
        {
            int tip = fingers[i * 2];
            int pip = fingers[i * 2 + 1];
            float tipDist = Dist(points[tip], wrist);
            float pipDist = Dist(points[pip], wrist);
            if (pipDist <= 0.0001f) continue;
            if (tipDist > pipDist * 1.20f) extended++;
        }
        return extended >= 3 ? HandPose.Open : extended <= 1 ? HandPose.Fist : HandPose.Unknown;
    }

    private static float Dist(HandPoint a, HandPoint b)
    {
        double dx = a.X - b.X;
        double dy = a.Y - b.Y;
        return (float)Math.Sqrt(dx * dx + dy * dy);
    }
}
