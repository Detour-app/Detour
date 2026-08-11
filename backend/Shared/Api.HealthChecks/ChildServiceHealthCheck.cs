using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace Shared.Api.HealthChecks;

public class ChildServiceHealthCheck(HttpClient httpClient) : IHealthCheck
{
    public const string ChildReportKey = "ChildHealthReport";

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task<HealthCheckResult> CheckHealthAsync(
        HealthCheckContext context, CancellationToken cancellationToken = default)
    {
        string? responseBody = null;
        try
        {
            using var response = await httpClient.GetAsync((string?)null, cancellationToken);
            responseBody = await response.Content.ReadAsStringAsync(cancellationToken);

            if (!response.IsSuccessStatusCode && response.StatusCode != System.Net.HttpStatusCode.ServiceUnavailable)
            {
                var preview = Truncate(responseBody);
                return new HealthCheckResult(context.Registration.FailureStatus,
                    $"Child service returned HTTP {(int)response.StatusCode}: {preview}");
            }

            var childReport = JsonSerializer.Deserialize<HealthCheckReport>(responseBody, JsonOptions);

            if (childReport is null)
                return new HealthCheckResult(context.Registration.FailureStatus, "Failed to deserialize child health report.");

            var status = Enum.TryParse<HealthStatus>(childReport.Status, ignoreCase: true, out var parsed)
                ? parsed
                : context.Registration.FailureStatus;

            var data = new Dictionary<string, object> { [ChildReportKey] = childReport };
            return new HealthCheckResult(status, description: null, exception: null, data: data);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            var description = ex is TaskCanceledException
                ? $"Child service health check timed out: {ex.Message}"
                : $"Child service health check failed: {ex.Message}";

            return new HealthCheckResult(context.Registration.FailureStatus, description);
        }
        catch (JsonException)
        {
            var preview = Truncate(responseBody);
            return new HealthCheckResult(context.Registration.FailureStatus,
                $"Child service returned non-JSON response: {preview}");
        }
    }

    private static string Truncate(string? value, int maxLength = 200) =>
        value is null ? "(empty)" : value.Length > maxLength ? value[..maxLength] + "..." : value;
}

public static class ChildServiceHealthCheckExtensions
{
    private static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(10);

    public static IHealthChecksBuilder AddChildService(
        this IHealthChecksBuilder builder,
        string name,
        Uri healthUrl,
        HealthStatus failureStatus = HealthStatus.Unhealthy,
        string[]? tags = null,
        Action<HttpClient>? configureClient = null,
        Action<IHttpClientBuilder>? configureHttpClientBuilder = null,
        TimeSpan? timeout = null)
    {
        var clientName = $"HealthCheck_{name}";
        var httpClientBuilder = builder.Services.AddHttpClient(clientName, client =>
        {
            client.BaseAddress = healthUrl;
            client.Timeout = timeout ?? DefaultTimeout;
            configureClient?.Invoke(client);
        });
        configureHttpClientBuilder?.Invoke(httpClientBuilder);

        builder.Add(new HealthCheckRegistration(
            name,
            sp =>
            {
                var factory = sp.GetRequiredService<IHttpClientFactory>();
                return new ChildServiceHealthCheck(factory.CreateClient(clientName));
            },
            failureStatus,
            tags));

        return builder;
    }

    public static IHealthChecksBuilder AddChildService(
        this IHealthChecksBuilder builder,
        string name,
        Func<IServiceProvider, Uri> healthUrlFactory,
        HealthStatus failureStatus = HealthStatus.Unhealthy,
        string[]? tags = null,
        Action<IServiceProvider, HttpClient>? configureClient = null,
        Action<IHttpClientBuilder>? configureHttpClientBuilder = null,
        TimeSpan? timeout = null)
    {
        var clientName = $"HealthCheck_{name}";
        var httpClientBuilder = builder.Services.AddHttpClient(clientName, (sp, client) =>
        {
            client.BaseAddress = healthUrlFactory(sp);
            client.Timeout = timeout ?? DefaultTimeout;
            configureClient?.Invoke(sp, client);
        });
        configureHttpClientBuilder?.Invoke(httpClientBuilder);

        builder.Add(new HealthCheckRegistration(
            name,
            sp =>
            {
                var factory = sp.GetRequiredService<IHttpClientFactory>();
                return new ChildServiceHealthCheck(factory.CreateClient(clientName));
            },
            failureStatus,
            tags));

        return builder;
    }
}
