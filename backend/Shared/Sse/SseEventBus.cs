using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Threading.Channels;
using Microsoft.Extensions.Logging;

namespace Shared.Sse;

public partial class SseEventBus(ILogger<SseEventBus> logger) : ISseEventBus
{
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<Guid, Channel<SseEvent>>> _subscriptions = new();

    public IEnumerable<string> ActiveChannels => _subscriptions.Keys;

    public async ValueTask PublishAsync(string channel, SseEvent sseEvent,
        CancellationToken cancellationToken = default)
    {
        if (!_subscriptions.TryGetValue(channel, out var subscribers))
            return;

        foreach (var (subscriberId, subscriberChannel) in subscribers)
        {
            try
            {
                if (!subscriberChannel.Writer.TryWrite(sseEvent))
                {
                    LogEventDropped(logger, channel, subscriberId);
                }
            }
            catch (ChannelClosedException)
            {
                subscribers.TryRemove(subscriberId, out _);
            }
        }
    }

    public async IAsyncEnumerable<SseEvent> SubscribeAsync(
        string channel,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var subscriberId = Guid.NewGuid();
        var subscriberChannel = Channel.CreateBounded<SseEvent>(new BoundedChannelOptions(100)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false,
        });

        // Register the subscriber as part of the same AddOrUpdate call rather than a separate
        // GetOrAdd-then-TryAdd: the two-step version made `channel` visible in ActiveChannels
        // (and thus to PublishAsync) for a brief window before the subscriber itself was in the
        // per-channel dict, letting a publish that landed in that window iterate an empty dict
        // and silently drop the event — a real race, not just test-timing noise.
        var subscribers = _subscriptions.AddOrUpdate(
            channel,
            _ =>
            {
                var created = new ConcurrentDictionary<Guid, Channel<SseEvent>>();
                created.TryAdd(subscriberId, subscriberChannel);
                return created;
            },
            (_, existing) =>
            {
                existing.TryAdd(subscriberId, subscriberChannel);
                return existing;
            });

        LogSubscriberConnected(logger, subscriberId, channel);

        try
        {
            await foreach (var sseEvent in subscriberChannel.Reader.ReadAllAsync(cancellationToken))
            {
                yield return sseEvent;
            }
        }
        finally
        {
            subscribers.TryRemove(subscriberId, out _);
            subscriberChannel.Writer.TryComplete();

            if (subscribers.IsEmpty)
                _subscriptions.TryRemove(channel, out _);

            LogSubscriberDisconnected(logger, subscriberId, channel);
        }
    }

    [LoggerMessage(Level = LogLevel.Debug,
        Message = "Dropped SSE event on channel {Channel} for subscriber {SubscriberId} (buffer full)")]
    private static partial void LogEventDropped(ILogger logger, string channel, Guid subscriberId);

    [LoggerMessage(Level = LogLevel.Debug,
        Message = "SSE subscriber {SubscriberId} connected to channel {Channel}")]
    private static partial void LogSubscriberConnected(ILogger logger, Guid subscriberId, string channel);

    [LoggerMessage(Level = LogLevel.Debug,
        Message = "SSE subscriber {SubscriberId} disconnected from channel {Channel}")]
    private static partial void LogSubscriberDisconnected(ILogger logger, Guid subscriberId, string channel);
}