namespace Shared.Sse;

public interface ISseEventBus
{
    ValueTask PublishAsync(string channel, SseEvent sseEvent, CancellationToken cancellationToken = default);

    IAsyncEnumerable<SseEvent> SubscribeAsync(string channel, CancellationToken cancellationToken = default);
}