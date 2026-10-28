using System.Text.Json.Nodes;

namespace WolAgent.Tests;

/// <summary>protocol/vectors.json okuyucu (Kotlin testleriyle ortak kaynak).</summary>
public static class Vectors
{
    private static readonly Lazy<JsonObject> Root = new(() =>
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var path = Path.Combine(dir.FullName, "protocol", "vectors.json");
            if (File.Exists(path)) return JsonNode.Parse(File.ReadAllText(path))!.AsObject();
        }
        throw new FileNotFoundException("protocol/vectors.json bulunamadı.");
    });

    public static JsonObject Json => Root.Value;

    public static JsonObject Pairing => Json["pairing"]!.AsObject();

    public static byte[] SessionKey => B64(Pairing, "sessionKey");

    public static IEnumerable<object[]> Cases(string section) =>
        Json[section]!.AsArray().Select(n => new object[] { n!["name"]!.GetValue<string>() });

    public static JsonObject Case(string section, string name) =>
        Json[section]!.AsArray().Select(n => n!.AsObject()).First(o => o["name"]!.GetValue<string>() == name);

    public static string Str(JsonObject o, string key) => o[key]!.GetValue<string>();

    public static byte[] B64(JsonObject o, string key) => Convert.FromBase64String(Str(o, key));
}
