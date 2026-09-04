using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;
using Microsoft.Extensions.Logging;

namespace Detour.Api.Notifications;

public sealed class FcmGateway : IFcmGateway
{
    private const string AppName = "detour";

    private readonly FirebaseMessaging? _messaging;
    private readonly ILogger<FcmGateway> _logger;

    public FcmGateway(NotificationSettings settings, ILogger<FcmGateway> logger)
    {
        _logger = logger;

        if (string.IsNullOrWhiteSpace(settings.FirebaseCredentialsPath))
        {
            _logger.LogWarning(
                "Notifications:FirebaseCredentialsPath is not set. Push wake-pings are disabled.");
            return;
        }

        // ponytail: GoogleCredential.FromFile is [Obsolete] in Google.Apis.Auth 1.73
        // (advisory only); the replacement CredentialFactory API is async-only and
        // this runs in a constructor. Switch if the warning ever becomes an error.
#pragma warning disable CS0618
        var credential = GoogleCredential.FromFile(settings.FirebaseCredentialsPath);
#pragma warning restore CS0618
        var app = FirebaseApp.GetInstance(AppName) ?? FirebaseApp.Create(
            new AppOptions { Credential = credential },
            AppName);

        _messaging = FirebaseMessaging.GetMessaging(app);
    }

    public async Task<FcmSendResult> SendWakeAsync(
        IReadOnlyCollection<string> tokens,
        string collapseKey,
        CancellationToken cancellationToken)
    {
        if (_messaging is null || tokens.Count == 0)
            return FcmSendResult.Empty;

        // One stable, indexable copy — the outcome loop pairs it with the response
        // by position. The dispatcher already passes an array, so this is usually a
        // cast, not a copy.
        var tokenList = tokens as IReadOnlyList<string> ?? [.. tokens];
        var outcomes = new List<FcmTokenOutcome>(tokenList.Count);

        // FCM's multicast ceiling is 500 tokens per call; above it
        // SendEachForMulticastAsync throws ArgumentException (not
        // FirebaseMessagingException, so it would escape the catch below). Chunk.
        foreach (var chunk in tokenList.Chunk(500))
        {
            var message = new MulticastMessage
            {
#pragma warning disable CS0618 // .Tokens → wire "token" (FCM registration token). .Fids → wire "fid" (Installation ID) — wrong identifier for this feature. See spec §1.1.
                Tokens = chunk,
#pragma warning restore CS0618
                Data = new Dictionary<string, string> { ["type"] = "circle_wake" },
                Android = new AndroidConfig
                {
                    Priority = Priority.High,
                    CollapseKey = collapseKey,
                },
                Apns = new ApnsConfig
                {
                    Headers = new Dictionary<string, string>
                    {
                        ["apns-priority"] = "10",
                        ["apns-collapse-id"] = collapseKey,
                        ["apns-push-type"] = "alert",
                    },
                    Aps = new Aps
                    {
                        // A minimal visible fallback: iOS throttles pure background
                        // pushes, and the Notification Service Extension replaces
                        // this body once it has fetched (Stage 3). Never localised
                        // here — the client owns copy.
                        Alert = new ApsAlert { Body = "New circle activity" },
                        ContentAvailable = true,
                        MutableContent = true,
                    },
                },
            };

            BatchResponse response;
            try
            {
                response = await _messaging.SendEachForMulticastAsync(message, cancellationToken);
            }
            catch (FirebaseMessagingException ex)
            {
                // Whole-batch failure (auth, quota, transport). Nothing to prune —
                // the tokens may be perfectly good. The event is not re-queued; the
                // device catches up on its next foreground sweep.
                _logger.LogWarning(ex, "FCM multicast failed for collapseKey {CollapseKey}", collapseKey);
                return FcmSendResult.Empty;
            }

            if (response.Responses.Count != chunk.Length)
            {
                _logger.LogError(
                    "FCM returned {Got} responses for {Sent} tokens (collapseKey {CollapseKey}); pruning nothing",
                    response.Responses.Count, chunk.Length, collapseKey);
                return FcmSendResult.Empty;
            }

            for (var i = 0; i < response.Responses.Count; i++)
            {
                var r = response.Responses[i];
                var prune = r.Exception?.MessagingErrorCode
                    is MessagingErrorCode.Unregistered
                    or MessagingErrorCode.InvalidArgument
                    or MessagingErrorCode.SenderIdMismatch;
                outcomes.Add(new FcmTokenOutcome(chunk[i], r.IsSuccess, prune));
            }
        }

        // A multi-token batch where every single token is prunable means the message
        // itself is malformed, not that every device de-registered at once. Prune
        // nothing rather than wipe every recipient.
        if (outcomes.Count > 1 && outcomes.All(o => o.ShouldPrune))
        {
            _logger.LogError(
                "FCM rejected every token in the batch (collapseKey {CollapseKey}) — treating as a message fault, pruning nothing",
                collapseKey);
            return FcmSendResult.Empty;
        }

        return new FcmSendResult(outcomes);
    }
}
