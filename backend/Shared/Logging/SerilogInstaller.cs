using Microsoft.AspNetCore.Builder;
using Microsoft.EntityFrameworkCore;
using Serilog;
using Serilog.Enrichers.Span;
using Serilog.Exceptions;
using Serilog.Exceptions.Core;
using Serilog.Exceptions.EntityFrameworkCore.Destructurers;
using Serilog.Exceptions.Filters;

namespace Shared.Logging;

public static class SerilogInstaller
{
    public static ConfigureHostBuilder ConfigureSerilog(this ConfigureHostBuilder hostBuilder,
        SerilogConfiguration config)
    {
        // Reading any property on a DbContext after its scope is disposed throws
        // ObjectDisposedException, which crashes Serilog.Exceptions when an exception's
        // object graph reaches a context (e.g. via DbUpdateException.Entries[].Context).
        var destructuringOptions = new DestructuringOptionsBuilder()
            .WithDefaultDestructurers()
            .WithDestructurers([new DbUpdateExceptionDestructurer()])
            .WithFilter(new IgnoreDbContextPropertyFilter());

        hostBuilder.UseSerilog((_, configuration) =>
        {
            configuration
                .Enrich.FromLogContext()
                .Enrich.WithExceptionDetails(destructuringOptions)
                .Enrich.WithSpan()
                .WriteTo.Console(
                    outputTemplate:
                    "[{Timestamp:HH:mm:ss} {Level:u3}] {TraceId} {TenantId} {SourceContext} {Message:lj}{NewLine}{Exception}");

            if (config.EnableFileLogging)
            {
                configuration
                    .WriteTo.File("Logs/log-.log",
                        rollingInterval: RollingInterval.Hour,
                        retainedFileCountLimit: 14,
                        shared: true,
                        flushToDiskInterval: TimeSpan.FromSeconds(5));
            }
        }, writeToProviders: true);

        return hostBuilder;
    }

    private sealed class IgnoreDbContextPropertyFilter : IExceptionPropertyFilter
    {
        public bool ShouldPropertyBeFiltered(Exception exception, string propertyName, object? value)
            => value is DbContext;
    }
}
