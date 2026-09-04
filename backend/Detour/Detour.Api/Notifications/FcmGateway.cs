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

        var message = new MulticastMessage
        {
#pragma warning disable CS0618 // .Tokens → wire "token" (FCM registration token). .Fids → wire "fid" (Installation ID) — wrong identifier for this feature. See spec §1.1.
            Tokens = [.. tokens],
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

        var tokenList = tokens.ToList();
        var outcomes = new List<FcmTokenOutcome>(tokenList.Count);
        for (var i = 0; i < response.Responses.Count; i++)
        {
            var r = response.Responses[i];
            var prune = r.Exception?.MessagingErrorCode
                is MessagingErrorCode.Unregistered or MessagingErrorCode.InvalidArgument;
            outcomes.Add(new FcmTokenOutcome(tokenList[i], r.IsSuccess, prune));
        }

        return new FcmSendResult(outcomes);
    }
}
