using System.Net.ServerSentEvents;
using System.Runtime.CompilerServices;

namespace Shared.Sse;

public static class SsePolling
{
    /// <summary>
    /// Server-polls a data source on a fixed interval, computes a signature of the current state, and emits a
    /// single tick only when that signature changes (including the first observed value). Suited to "poke the
    /// client to refetch" SSE streams over state that has no in-process publisher — for example data whose
    /// changes are time-derived rather than write-driven.
    /// </summary>
    /// <param name="computeSignature">Produces a signature of the current state; a change from the previous
    /// signature emits a tick. Exceptions propagate and end the stream.</param>
    /// <param name="interval">Delay between polls.</param>
    /// <param name="tickData">Payload sent on each change tick.</param>
    /// <param name="cancellationToken">Ends the stream cleanly when cancelled (typically the request abort).</param>
    public static async IAsyncEnumerable<SseItem<string>> StreamSignatureChangesAsync(
        Func<CancellationToken, Task<string>> computeSignature,
        TimeSpan interval,
        string tickData = "{}",
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        string? previous = null;
        while (!cancellationToken.IsCancellationRequested)
        {
            var signature = await computeSignature(cancellationToken);
            if (signature != previous)
            {
                previous = signature;
                yield return new SseItem<string>(tickData);
            }

            try
            {
                await Task.Delay(interval, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                yield break;
            }
        }
    }
}
