using System.Net.Http.Json;
using System.Text.Json;
using Detour.Api.Configuration;
using Microsoft.Extensions.Options;

namespace Detour.Api.Services;

/// <summary>
/// Tells the realm administrator that a rider deleted their own account, so the Keycloak user
/// can be removed too (issue #700). Best effort: the app data is already gone when this runs, and
/// a webhook that is down, slow or answers an error must not turn a completed deletion into a
/// failed request.
/// </summary>
public interface IAccountDeletionNotifier
{
    Task NotifyAsync(DeletedAccount account);
}

/// <summary>
/// What the administrator needs to find the realm user. <see cref="Subject"/> is the Keycloak
/// user id; <see cref="Username"/> is for the human reading it. No email: the realm already has
/// it, and the webhook is one more place it would otherwise be copied to.
/// </summary>
public record DeletedAccount(string Subject, string Username, DateTimeOffset DeletedAt);

public sealed class AccountDeletionNotifier(
    IHttpClientFactory httpClientFactory,
    IOptions<AccountDeletionSettings> settings,
    ILogger<AccountDeletionNotifier> logger) : IAccountDeletionNotifier
{
    public const string HttpClientName = "account-deletion-webhook";

    /// <summary>Short: the rider's request waits on this after its data is already gone.</summary>
    public static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public async Task NotifyAsync(DeletedAccount account)
    {
        var url = settings.Value.WebhookUrl;
        if (string.IsNullOrWhiteSpace(url))
            return;

        try
        {
            using var response = await httpClientFactory.CreateClient(HttpClientName).PostAsJsonAsync(
                url,
                new
                {
                    @event = "account_deleted",
                    subject = account.Subject,
                    username = account.Username,
                    deletedAtMs = account.DeletedAt.ToUnixTimeMilliseconds(),
                },
                Json);

            if (!response.IsSuccessStatusCode)
                logger.LogWarning(
                    "Account deletion webhook answered {Status} for subject {Subject}; remove the realm user by hand.",
                    (int)response.StatusCode, account.Subject);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            logger.LogWarning(ex,
                "Account deletion webhook could not be reached for subject {Subject}; remove the realm user by hand.",
                account.Subject);
        }
    }
}
