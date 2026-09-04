using System.Buffers.Text;
using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Detour.Domain.Notifications;
using Microsoft.Extensions.Logging;

namespace Detour.Api.Notifications;

/// <summary>
/// iOS wake-pings straight to APNs — no Firebase relay. Signs an ES256 provider
/// JWT from the <c>.p8</c> auth key (via <see cref="CachedJwt"/>, refreshed inside
/// Apple's once-per-20-minutes window) and POSTs one HTTP/2 request per token to
/// <c>/3/device/{token}</c>. Mapping 1:1 to Apple's own API means an iOS delivery
/// failure surfaces Apple's real reason (<c>410 Unregistered</c>,
/// <c>BadDeviceToken</c>) rather than Google's translation of it.
///
/// A deployment missing any of the four APNs settings, or whose key file cannot be
/// read, is disabled and no-ops — the same before-credentials state FCM has.
/// </summary>
public sealed class ApnsGateway : IPushGateway
{
    public const string HttpClientName = "apns";

    // The alert body is a fixed English placeholder. iOS throttles pure silent
    // pushes, so the wake carries a minimal visible alert; the Notification Service
    // Extension rewrites it from the fetched event, and the client — never this
    // backend — owns the copy. This constant is only what survives if that fetch
    // does not finish in time.
    private const string PlaceholderBody = "New circle activity";

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private readonly Config? _config;
    private readonly CachedJwt? _jwt;
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger<ApnsGateway> _logger;

    public DevicePlatform Platform => DevicePlatform.Ios;

    public ApnsGateway(
        NotificationSettings settings, IHttpClientFactory httpClientFactory, ILogger<ApnsGateway> logger)
    {
        _httpClientFactory = httpClientFactory;
        _logger = logger;
        _config = Config.TryLoad(settings, logger);

        if (_config is not null)
        {
            var config = _config;
            // 40 min: comfortably inside APNs' 60-minute token validity, comfortably
            // outside its 20-minute minimum refresh interval.
            _jwt = new CachedJwt(() => Sign(config), TimeSpan.FromMinutes(40));
        }
    }

    public async Task<PushSendResult> SendWakeAsync(
        IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken cancellationToken)
    {
        if (_config is null || _jwt is null || tokens.Count == 0)
            return PushSendResult.Empty;

        string bearer;
        try
        {
            bearer = _jwt.Current;
        }
        catch (Exception ex)
        {
            // Signing failed (a key that imported but cannot sign) — a whole-batch
            // fault, not a per-token one. Prune nothing.
            _logger.LogWarning(ex, "APNs token signing failed for collapseKey {CollapseKey}", collapseKey);
            return PushSendResult.Empty;
        }

        var http = _httpClientFactory.CreateClient(HttpClientName);
        var outcomes = new List<PushTokenOutcome>(tokens.Count);

        foreach (var token in tokens)
        {
            using var request = new HttpRequestMessage(
                HttpMethod.Post, $"{_config.Host}/3/device/{token}")
            {
                // APNs is HTTP/2 only; demand it rather than let the handler
                // negotiate down to 1.1 (which APNs refuses).
                Version = HttpVersion.Version20,
                VersionPolicy = HttpVersionPolicy.RequestVersionExact,
                Content = JsonContent.Create(WakePayload, options: Json),
            };
            request.Headers.TryAddWithoutValidation("authorization", $"bearer {bearer}");
            request.Headers.TryAddWithoutValidation("apns-topic", _config.Topic);
            request.Headers.TryAddWithoutValidation("apns-push-type", "alert");
            request.Headers.TryAddWithoutValidation("apns-priority", "10");
            request.Headers.TryAddWithoutValidation("apns-collapse-id", collapseKey);

            try
            {
                using var response = await http.SendAsync(request, cancellationToken);
                var prune = !response.IsSuccessStatusCode
                    && await ShouldPruneAsync(response, cancellationToken);
                outcomes.Add(new PushTokenOutcome(token, response.IsSuccessStatusCode, prune));
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.LogWarning(ex, "APNs send failed for one token (collapseKey {CollapseKey})", collapseKey);
                outcomes.Add(new PushTokenOutcome(token, Delivered: false, ShouldPrune: false));
            }
        }

        return new PushSendResult(outcomes);
    }

    private static readonly object WakePayload = new
    {
        aps = new Dictionary<string, object>
        {
            ["alert"] = new { body = PlaceholderBody },
            ["content-available"] = 1,
            ["mutable-content"] = 1,
        },
    };

    /// <summary>
    /// APNs marks a token permanently dead with <c>410 Unregistered</c> or a 400
    /// whose <c>reason</c> is <c>BadDeviceToken</c> / <c>DeviceTokenNotForTopic</c>.
    /// Everything else (429 TooManyRequests, 5xx) is transient — prune nothing.
    /// </summary>
    private static async Task<bool> ShouldPruneAsync(
        HttpResponseMessage response, CancellationToken cancellationToken)
    {
        if (response.StatusCode == HttpStatusCode.Gone)
            return true;

        if (response.StatusCode != HttpStatusCode.BadRequest)
            return false;

        try
        {
            var error = await response.Content.ReadFromJsonAsync<ApnsError>(Json, cancellationToken);
            return error?.Reason is "BadDeviceToken" or "DeviceTokenNotForTopic";
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// Signs the provider JWT by hand — header <c>{alg:ES256, kid}</c>, claims
    /// <c>{iss:teamId, iat}</c>, and nothing else. Deliberately not
    /// <c>Microsoft.IdentityModel</c>'s handler, which adds <c>exp</c>/<c>nbf</c>
    /// claims APNs can reject; Apple documents this exact two-claim payload.
    /// <c>ECDsa.SignData</c> returns the IEEE-P1363 (r‖s) form JOSE ES256 wants.
    /// </summary>
    private static string Sign(Config config)
    {
        var header = Base64Url.EncodeToString(
            JsonSerializer.SerializeToUtf8Bytes(new { alg = "ES256", kid = config.KeyId }));
        var claims = Base64Url.EncodeToString(
            JsonSerializer.SerializeToUtf8Bytes(
                new { iss = config.TeamId, iat = DateTimeOffset.UtcNow.ToUnixTimeSeconds() }));
        var signingInput = $"{header}.{claims}";
        var signature = config.Key.SignData(
            Encoding.ASCII.GetBytes(signingInput), HashAlgorithmName.SHA256);
        return $"{signingInput}.{Base64Url.EncodeToString(signature)}";
    }

    private sealed record ApnsError(string? Reason);

    private sealed class Config(ECDsa key, string keyId, string teamId, string topic, string host)
    {
        public ECDsa Key { get; } = key;
        public string KeyId { get; } = keyId;
        public string TeamId { get; } = teamId;
        public string Topic { get; } = topic;
        public string Host { get; } = host;

        public static Config? TryLoad(NotificationSettings settings, ILogger logger)
        {
            if (string.IsNullOrWhiteSpace(settings.ApnsKeyPath)
                || string.IsNullOrWhiteSpace(settings.ApnsKeyId)
                || string.IsNullOrWhiteSpace(settings.ApnsTeamId)
                || string.IsNullOrWhiteSpace(settings.ApnsTopic))
            {
                logger.LogWarning(
                    "APNs is not fully configured (Notifications:Apns*). iOS push wake-pings are disabled.");
                return null;
            }

            var key = ECDsa.Create();
            try
            {
                key.ImportFromPem(File.ReadAllText(settings.ApnsKeyPath));
            }
            catch (Exception ex)
            {
                // A path that is set but unreadable or malformed is an operator error,
                // not a run-time one: fail loudly at startup (the warmup constructs this)
                // rather than silently no-op every iOS send.
                key.Dispose();
                throw new InvalidOperationException(
                    $"Notifications:ApnsKeyPath '{settings.ApnsKeyPath}' could not be read as an EC private key.", ex);
            }

            var host = settings.ApnsUseSandbox
                ? "https://api.sandbox.push.apple.com"
                : "https://api.push.apple.com";

            return new Config(key, settings.ApnsKeyId!, settings.ApnsTeamId!, settings.ApnsTopic!, host);
        }
    }
}
