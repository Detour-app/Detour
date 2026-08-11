using System.Net.ServerSentEvents;
using System.Runtime.CompilerServices;

namespace Shared.Sse;

public static class SseEventBusExtensions
{
    /// <summary>
    /// Subscribes to a channel and projects each published <see cref="SseEvent"/> onto an
    /// <see cref="SseItem{T}"/> stream suitable for <c>TypedResults.ServerSentEvents</c>.
    /// </summary>
    public static IAsyncEnumerable<SseItem<string>> SubscribeSseItemsAsync(
        this ISseEventBus eventBus,
        string channel,
        CancellationToken cancellationToken = default) =>
        eventBus.SubscribeAsync(channel, cancellationToken).ToSseItems(cancellationToken);

    /// <summary>
    /// Converts a stream of <see cref="SseEvent"/> into <see cref="SseItem{T}"/> values, treating a
    /// cancellation of <paramref name="cancellationToken"/> as a clean end of stream rather than a fault.
    /// </summary>
    public static async IAsyncEnumerable<SseItem<string>> ToSseItems(
        this IAsyncEnumerable<SseEvent> events,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        IAsyncEnumerator<SseEvent> enumerator = events.GetAsyncEnumerator(cancellationToken);
        await using (enumerator.ConfigureAwait(false))
        {
            while (true)
            {
                SseEvent sseEvent;
                try
                {
                    if (!await enumerator.MoveNextAsync())
                        break;
                    sseEvent = enumerator.Current;
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    yield break;
                }

                yield return new SseItem<string>(sseEvent.Data, sseEvent.EventType);
            }
        }
    }

    /// <summary>
    /// Yields items from <paramref name="source"/> until (and including) the first item for which
    /// <paramref name="isTerminal"/> returns true, then completes the sequence — closing the SSE
    /// response. Lets an endpoint self-close a stream on a sentinel event.
    /// </summary>
    public static async IAsyncEnumerable<SseItem<string>> CompleteAfter(
        this IAsyncEnumerable<SseItem<string>> source,
        Func<SseItem<string>, bool> isTerminal,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        await foreach (var item in source.WithCancellation(cancellationToken))
        {
            yield return item;
            if (isTerminal(item))
                yield break;
        }
    }
}
