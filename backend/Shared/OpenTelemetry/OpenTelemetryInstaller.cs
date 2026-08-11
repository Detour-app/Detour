using Microsoft.Extensions.DependencyInjection;
using OpenTelemetry.Exporter;
using OpenTelemetry.Logs;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

namespace Shared.OpenTelemetry;

public class OpenTelemetrySettings
{
    public required string ServiceName { get; set; }
    public string OtlpEndpoint { get; set; } = "http://localhost:8401";

    /// <summary>
    /// Stable identity of this process within <see cref="ServiceName"/>. The SDK's own default is a
    /// fresh GUID per process, which starts a new metric time series on every restart.
    /// <para>
    /// Left unset it falls back to the machine name. Under Docker that is the container hostname,
    /// which survives a process crash-restart and <c>compose restart</c> but NOT a container
    /// recreate — <c>up -d</c> after an image-tag bump mints a new one. Deployments that want the
    /// series to survive a rollout must pin it: either set this value, or give the service a fixed
    /// <c>hostname:</c> in its compose file.
    /// </para>
    /// </summary>
    public string? ServiceInstanceId { get; set; }
}

public static class OpenTelemetryInstaller
{
    /// <summary>Traces raised by the backend's own code, rather than by instrumentation.</summary>
    public const string ActivitySourceName = "Detour";

    /// <summary>Meter for the backend's own counters and histograms.</summary>
    public const string MeterName = "Detour";

    public static IServiceCollection SetupOpenTelemetry(
        this IServiceCollection services,
        OpenTelemetrySettings otelSettings)
    {
        services.AddOpenTelemetry()
            .ConfigureResource(resource => ConfigureResource(resource, otelSettings))
            .WithTracing(tracing => tracing
                // SSE endpoints are intentionally traced so their logs carry trace_id;
                // per-endpoint .DisableHttpMetrics() keeps the HTTP histograms clean.
                .AddAspNetCoreInstrumentation()
                .AddHttpClientInstrumentation()
                .AddSource(ActivitySourceName)
                .AddSource("Api")
                .AddOtlpExporter(options =>
                {
                    options.Endpoint = new Uri(otelSettings.OtlpEndpoint);
                    options.Protocol = OtlpExportProtocol.Grpc;
                }))
            .WithMetrics(metrics => metrics
                .AddAspNetCoreInstrumentation()
                .AddHttpClientInstrumentation()
                .AddRuntimeInstrumentation()
                .AddProcessInstrumentation()
                .AddMeter(MeterName)
                .AddOtlpExporter(options =>
                {
                    options.Endpoint = new Uri(otelSettings.OtlpEndpoint);
                    options.Protocol = OtlpExportProtocol.Grpc;
                }))
            .WithLogging(logging => logging
                .AddOtlpExporter(options =>
                {
                    options.Endpoint = new Uri(otelSettings.OtlpEndpoint);
                    options.Protocol = OtlpExportProtocol.Grpc;
                }));

        return services;
    }

    /// <summary>
    /// Stamps the resource that identifies this process to the collector. The instance id is always
    /// supplied explicitly, so the SDK never falls back to auto-generating a per-process GUID.
    /// </summary>
    internal static void ConfigureResource(ResourceBuilder resource, OpenTelemetrySettings otelSettings) =>
        resource.AddService(
            serviceName: otelSettings.ServiceName,
            serviceInstanceId: string.IsNullOrWhiteSpace(otelSettings.ServiceInstanceId)
                ? Environment.MachineName
                : otelSettings.ServiceInstanceId);
}
