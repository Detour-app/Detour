using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using Detour.Domain.Notifications;
using Microsoft.Extensions.Logging;

namespace Detour.Api.Notifications;

/// <summary>
/// Android wake-pings over FCM HTTP v1. No <c>FirebaseAdmin</c>: the bearer comes
/// from Google's credential library (which mints and caches the OAuth2 access
/// token), then this makes a plain POST to <c>messages:send</c>. v1 has no
/// multicast, so a batch is one request per token.
///
/// The bearer source is a delegate, not a hard dependency on a real credential,
/// so the send-and-classify logic — including which HTTP responses prune a token —
/// is unit-testable against a stub token and a fake HTTP handler. The installer
/// wires the Google-backed source; a gateway built with a null source or project
/// id is disabled and no-ops, the correct state before Firebase credentials are
/// placed on the box.
/// </summary>
public sealed class FcmGateway(
    string? projectId,
    Func<CancellationToken, Task<string>>? accessToken,
    IHttpClientFactory httpClientFactory,
    ILogger<FcmGateway> logger) : IPushGateway
{
    public const string HttpClientName = "fcm";
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public DevicePlatform Platform => DevicePlatform.Android;

    private bool Enabled => projectId is not null && accessToken is not null;

    public async Task<PushSendResult> SendWakeAsync(
        IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken cancellationToken)
    {
        if (!Enabled || tokens.Count == 0)
            return PushSendResult.Empty;

        string bearer;
        try
        {
            bearer = await accessToken!(cancellationToken);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // Whole-batch auth failure — the tokens themselves may be perfectly
            // good, so prune nothing and let the devices catch up on foreground.
            logger.LogWarning(ex, "FCM access-token mint failed for collapseKey {CollapseKey}", collapseKey);
            return PushSendResult.Empty;
        }

        var http = httpClientFactory.CreateClient(HttpClientName);
        var url = $"https://fcm.googleapis.com/v1/projects/{projectId}/messages:send";
        var outcomes = new List<PushTokenOutcome>(tokens.Count);

        foreach (var token in tokens)
        {
            var payload = new FcmSend(new FcmMessage(
                token,
                new Dictionary<string, string> { ["type"] = "circle_wake" },
                new FcmAndroid("high", collapseKey)));

            using var request = new HttpRequestMessage(HttpMethod.Post, url)
            {
                Content = JsonContent.Create(payload, options: Json),
            };
            request.Headers.Authorization = new("Bearer", bearer);

            try
            {
                using var response = await http.SendAsync(request, cancellationToken);
                var prune = !response.IsSuccessStatusCode
                    && await ShouldPruneAsync(response, cancellationToken);
                outcomes.Add(new PushTokenOutcome(token, response.IsSuccessStatusCode, prune));
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                // Transport failure for this one token — not a de-registration. Keep it.
                logger.LogWarning(ex, "FCM send failed for one token (collapseKey {CollapseKey})", collapseKey);
                outcomes.Add(new PushTokenOutcome(token, Delivered: false, ShouldPrune: false));
            }
        }

        return new PushSendResult(outcomes);
    }

    /// <summary>
    /// FCM v1 marks a token permanently dead with 404 <c>UNREGISTERED</c> or 400
    /// <c>INVALID_ARGUMENT</c> / <c>SENDER_ID_MISMATCH</c>, carried in
    /// <c>error.details[].errorCode</c>. Every other status (401 auth, 429 quota,
    /// 5xx) is transient or a config fault — prune nothing.
    /// </summary>
    private static async Task<bool> ShouldPruneAsync(
        HttpResponseMessage response, CancellationToken cancellationToken)
    {
        if (response.StatusCode is not (HttpStatusCode.NotFound or HttpStatusCode.BadRequest))
            return false;

        try
        {
            var error = await response.Content.ReadFromJsonAsync<FcmErrorEnvelope>(Json, cancellationToken);
            var code = error?.Error?.Details?
                .FirstOrDefault(d => d.ErrorCode is not null)?.ErrorCode;
            return code is "UNREGISTERED" or "INVALID_ARGUMENT" or "SENDER_ID_MISMATCH";
        }
        catch
        {
            // A body we cannot parse is not proof the token is dead.
            return false;
        }
    }

    private sealed record FcmSend(FcmMessage Message);

    private sealed record FcmMessage(
        string Token,
        IReadOnlyDictionary<string, string> Data,
        FcmAndroid Android);

    private sealed record FcmAndroid(
        string Priority,
        [property: JsonPropertyName("collapse_key")] string CollapseKey);

    private sealed record FcmErrorEnvelope(FcmError? Error);

    private sealed record FcmError(IReadOnlyList<FcmErrorDetail>? Details);

    private sealed record FcmErrorDetail(
        [property: JsonPropertyName("errorCode")] string? ErrorCode);
}
