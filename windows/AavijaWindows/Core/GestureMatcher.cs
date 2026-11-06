namespace Aavija.Core;

public class GestureMatcher
{
    public enum Kind { Grab, Release }
    public enum Action { PullFromPeer, SendToPeer }

    public const long WindowMs = 10000;

    private long? localGrabAt;
    private bool localGrabUsed;
    private long? localReleaseAt;
    private bool localReleaseUsed;

    private (long Id, long At)? remoteGrab;
    private bool remoteGrabUsed;
    private (long Id, long At)? remoteRelease;
    private bool remoteReleaseUsed;

    public void Reset()
    {
        localGrabAt = null; localGrabUsed = false;
        localReleaseAt = null; localReleaseUsed = false;
        remoteGrab = null; remoteGrabUsed = false;
        remoteRelease = null; remoteReleaseUsed = false;
    }

    public Action? OnLocal(Kind kind, long atMs)
    {
        if (kind == Kind.Grab)
        {
            localGrabAt = atMs;
            localGrabUsed = false;
            return null;
        }
        localReleaseAt = atMs;
        localReleaseUsed = false;
        if (remoteGrab is null || remoteGrabUsed) return null;
        var age = atMs - remoteGrab.Value.At;
        if (age < 0 || age > WindowMs) return null;
        remoteGrabUsed = true;
        localReleaseUsed = true;
        return Action.PullFromPeer;
    }

    public bool IsNew(GestureOffer offer) => offer.Type switch
    {
        "grab" => remoteGrab?.Id != offer.Id,
        "release" => remoteRelease?.Id != offer.Id,
        _ => false,
    };

    public Action? OnRemote(GestureOffer offer, long nowMs)
    {
        var happenedAt = nowMs - offer.AgeMs;
        if (offer.Type == "grab")
        {
            if (remoteGrab?.Id == offer.Id) return null;
            remoteGrab = (offer.Id, happenedAt);
            remoteGrabUsed = false;
            if (localReleaseAt is null || localReleaseUsed) return null;
            var age = localReleaseAt.Value - happenedAt;
            if (age < 0 || age > WindowMs) return null;
            localReleaseUsed = true;
            remoteGrabUsed = true;
            return Action.PullFromPeer;
        }
        if (offer.Type == "release")
        {
            if (remoteRelease?.Id == offer.Id) return null;
            remoteRelease = (offer.Id, happenedAt);
            remoteReleaseUsed = false;
            if (localGrabAt is null || localGrabUsed) return null;
            var age = happenedAt - localGrabAt.Value;
            if (age < 0 || age > WindowMs) return null;
            localGrabUsed = true;
            remoteReleaseUsed = true;
            return Action.SendToPeer;
        }
        return null;
    }
}
