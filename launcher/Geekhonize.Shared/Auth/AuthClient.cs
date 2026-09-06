using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Geekhonize.Shared.Auth;

/// <summary>Auth REST 客户端（直连 auth.geekhonize.top）。与 MC 客户端 GeoHttp / 网页门户同一套 API。</summary>
public sealed class AuthClient
{
    private readonly HttpClient _http;
    public string Endpoint { get; set; }

    public AuthClient(string endpoint = "https://auth.geekhonize.top")
    {
        Endpoint = endpoint.TrimEnd('/');
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
    }

    public record User(
        [property: JsonPropertyName("id")] long Id,
        [property: JsonPropertyName("username")] string Username,
        [property: JsonPropertyName("display_name")] string? DisplayName,
        [property: JsonPropertyName("email")] string? Email,
        [property: JsonPropertyName("email_verified")] bool EmailVerified,
        [property: JsonPropertyName("roles")] string[] Roles);

    public record LoginResult(
        bool Ok, string? AccessToken, string? Message, User? User, string? DevCode);

    private static readonly JsonSerializerOptions Opts = new(JsonSerializerDefaults.Web);

    private async Task<JsonDocument> PostAsync(string path, object? body, string? token = null)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, Endpoint + path);
        if (token != null) req.Headers.Authorization = new("Bearer", token);
        if (body != null) req.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
        var resp = await _http.SendAsync(req);
        var text = await resp.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(text);
        if (doc.RootElement.TryGetProperty("ok", out var ok) && ok.GetBoolean())
            return JsonDocument.Parse(text);
        var msg = doc.RootElement.TryGetProperty("msg", out var m) ? m.GetString() : ("HTTP " + (int)resp.StatusCode);
        throw new AuthApiException((int)resp.StatusCode, msg ?? "请求失败");
    }

    private static LoginResult ToLogin(JsonDocument doc)
    {
        var root = doc.RootElement;
        if (!root.TryGetProperty("ok", out var ok) || !ok.GetBoolean())
            return new LoginResult(false, null, SafeStr(root, "msg"), null, null);
        var tok = SafeStr(root, "access_token");
        var dev = SafeStr(root, "dev_code");
        User? user = null;
        if (root.TryGetProperty("user", out var ue))
            user = JsonSerializer.Deserialize<User>(ue.GetRawText(), Opts);
        return new LoginResult(true, tok, null, user, dev);
    }

    public async Task<LoginResult> LoginAsync(string username, string password, string app = "breakfront")
    {
        using var doc = await PostAsync("/api/v1/auth/login", new { username, password, app });
        return ToLogin(doc);
    }

    public async Task<string> SendCodeAsync(string email, string purpose)
    {
        using var doc = await PostAsync("/api/v1/auth/send_code", new { email, purpose });
        string dev = "";
        if (doc.RootElement.TryGetProperty("data", out var d)
            && d.TryGetProperty("dev_code", out var dc)) dev = dc.GetString() ?? "";
        return dev;
    }

    public async Task<LoginResult> RegisterAsync(string username, string email, string code, string password, string app = "breakfront")
    {
        using var doc = await PostAsync("/api/v1/auth/register",
            new { username, email, code, password, app });
        return ToLogin(doc);
    }

    public async Task<LoginResult> MeAsync(string token)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, Endpoint + "/api/v1/auth/me");
        req.Headers.Authorization = new("Bearer", token);
        var resp = await _http.SendAsync(req);
        var text = await resp.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(text);
        var root = doc.RootElement;
        if (!root.TryGetProperty("ok", out var ok) || !ok.GetBoolean())
            return new LoginResult(false, null, SafeStr(root, "msg"), null, null);
        var d = root.GetProperty("data");
        var user = new User(
            d.GetProperty("uid").GetInt64(),
            d.GetProperty("username").GetString() ?? "",
            d.TryGetProperty("display_name", out var dn) && dn.ValueKind == System.Text.Json.JsonValueKind.String ? dn.GetString() : null,
            d.TryGetProperty("email", out var em) && em.ValueKind == System.Text.Json.JsonValueKind.String ? em.GetString() : null,
            d.TryGetProperty("email_verified", out var ev) && ev.GetBoolean(),
            d.GetProperty("roles").EnumerateArray().Select(r => r.GetString() ?? "").ToArray());
        return new LoginResult(true, token, null, user, null);
    }

    private static string SafeStr(JsonElement e, string k)
        => e.TryGetProperty(k, out var v) && v.ValueKind == System.Text.Json.JsonValueKind.String ? v.GetString() ?? "" : "";
}

public sealed class AuthApiException : Exception
{
    public int Status { get; }
    public AuthApiException(int status, string message) : base(message) => Status = status;
}
