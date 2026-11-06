using System.Text.Json;
using System.Text.Json.Nodes;

namespace Aavija.Core;

public static class Protocol
{
    public const string Platform = "windows";

    public static string PairMessage(string name, string proofHex, string requestId) =>
        new JsonObject
        {
            ["type"] = "pair",
            ["name"] = name,
            ["proof"] = proofHex,
            ["platform"] = "android", // phones expect "android" for non-Mac clients
            ["requestId"] = requestId,
        }.ToJsonString();

    public static string AuthMessage(string tokenHex) =>
        new JsonObject { ["type"] = "auth", ["token"] = tokenHex, ["platform"] = "android" }.ToJsonString();

    public static string PingMessage(string tokenHex) =>
        new JsonObject { ["type"] = "ping", ["token"] = tokenHex, ["platform"] = "android" }.ToJsonString();

    public static string ReceiveRequest() =>
        new JsonObject { ["type"] = "receive", ["platform"] = "android" }.ToJsonString();

    public static string CancelMessage(string requestId) =>
        new JsonObject { ["type"] = "cancel", ["requestId"] = requestId, ["platform"] = "android" }.ToJsonString();

    public static byte[]? ParseChallengeSalt(string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return null;
        try
        {
            using var doc = JsonDocument.Parse(line);
            if (doc.RootElement.GetProperty("type").GetString() != "challenge") return null;
            var hex = doc.RootElement.GetProperty("salt").GetString();
            if (string.IsNullOrEmpty(hex)) return null;
            return Convert.FromHexString(hex);
        }
        catch { return null; }
    }

    public record Verdict(string Type, string? TokenHex, string? Reason, long RetryAfterMs)
    {
        public bool IsPaired => Type == "paired";
        public bool IsAuthenticated => Type == "authenticated";
    }

    public static Verdict? ParseVerdict(string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return null;
        try
        {
            using var doc = JsonDocument.Parse(line);
            var type = doc.RootElement.GetProperty("type").GetString();
            if (string.IsNullOrEmpty(type)) return null;
            string? token = doc.RootElement.TryGetProperty("token", out var t) ? t.GetString() : null;
            string? reason = doc.RootElement.TryGetProperty("reason", out var r) ? r.GetString() : null;
            long retry = doc.RootElement.TryGetProperty("retryAfterMs", out var x) ? x.GetInt64() : 0;
            return new Verdict(type!, string.IsNullOrEmpty(token) ? null : token, string.IsNullOrEmpty(reason) ? null : reason, retry);
        }
        catch { return null; }
    }

    public record Pong(int Outgoing, bool GestureGate, GestureOffer? Gesture);

    public static Pong? ParsePong(string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return null;
        try
        {
            using var doc = JsonDocument.Parse(line);
            if (doc.RootElement.GetProperty("type").GetString() != "pong") return null;
            int outgoing = doc.RootElement.TryGetProperty("outgoing", out var o) ? o.GetInt32() : 0;
            bool gate = doc.RootElement.TryGetProperty("gestureGate", out var g) && g.GetBoolean();
            GestureOffer? offer = null;
            if (doc.RootElement.TryGetProperty("gesture", out var gg))
            {
                var kind = gg.GetString();
                if (kind == "grab" || kind == "release")
                {
                    long age = doc.RootElement.TryGetProperty("gestureAgeMs", out var a) ? a.GetInt64() : -1;
                    if (age >= 0 && age <= 10000)
                    {
                        long id = doc.RootElement.TryGetProperty("gestureId", out var i) ? i.GetInt64() : 0;
                        offer = new GestureOffer(kind, id, age);
                    }
                }
            }
            return new Pong(outgoing, gate, offer);
        }
        catch { return null; }
    }
}

public record GestureOffer(string Type, long Id, long AgeMs);
