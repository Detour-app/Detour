using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Detour.InfraTests.Api;

/// <summary>
/// What the API's logs may hold (#981). A dashboard key can only travel as <c>?key=</c> from an
/// embedded frame, and Hosting's per-request lines carry the query string, so they must stay
/// below the level Serilog writes. Captured through a provider registered the way any other
/// <see cref="ILoggerProvider"/> is — the same path the OTLP exporter receives events on
/// (<c>writeToProviders</c>).
/// </summary>
[Collection(PostgresCollection.Name)]
public class RequestLoggingTests(PostgresFixture postgres) : IAsyncLifetime
{
    private readonly CapturingLoggerProvider _logs = new();
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres,
            services => services.AddSingleton<ILoggerProvider>(_logs));
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task A_dashboard_key_in_the_query_is_not_logged()
    {
        var rider = _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}", $"rider{Guid.NewGuid():N}"[..16], null, "detour-user"));
        (await rider.GetAsync("/api/me")).EnsureSuccessStatusCode();
        var issued = await (await rider.PostAsJsonAsync("/api/me/api-keys", new { label = "frame" }))
            .EnsureSuccessStatusCode().Content.ReadFromJsonAsync<JsonElement>();
        var key = issued.GetProperty("key").GetString()!;

        var response = await _factory.CreateClient()
            .GetAsync($"/api/dashboard/stats?key={Uri.EscapeDataString(key)}");
        response.StatusCode.Should().Be(HttpStatusCode.OK);

        // Positive control: the capture sees what Serilog writes, so an empty match below
        // means "filtered", not "never captured". Warning is above the override.
        _factory.Services.GetRequiredService<ILoggerFactory>()
            .CreateLogger("Microsoft.AspNetCore.Hosting.Diagnostics")
            .LogWarning("control line");
        _logs.Messages.Should().Contain(m => m.Contains("control line"));

        _logs.Messages.Should().NotContain(m => m.Contains(key));
        _logs.Messages.Should().NotContain(m => m.Contains(Uri.EscapeDataString(key)));
    }

    private sealed class CapturingLoggerProvider : ILoggerProvider
    {
        private readonly ConcurrentQueue<string> _messages = new();

        public IReadOnlyCollection<string> Messages => [.. _messages];

        public ILogger CreateLogger(string categoryName) => new Logger(_messages);

        public void Dispose()
        {
        }

        private sealed class Logger(ConcurrentQueue<string> messages) : ILogger
        {
            public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

            public bool IsEnabled(LogLevel logLevel) => true;

            public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception,
                Func<TState, Exception?, string> formatter) =>
                messages.Enqueue(formatter(state, exception));
        }
    }
}
