using System.Threading.Channels;

namespace Detour.Api.Notifications;

public interface IPushQueue
{
    /// <summary>False when the queue is full. A dropped wake-ping is not an
    ///  error — the device reconciles on its next foreground sweep.</summary>
    bool TryEnqueue(PushJob job);

    IAsyncEnumerable<PushJob> ReadAllAsync(CancellationToken cancellationToken);
}

public sealed class PushQueue : IPushQueue
{
    private readonly Channel<PushJob> _channel;

    public PushQueue(NotificationSettings settings)
    {
        // Wait + TryWrite: a full queue makes TryEnqueue return false rather than
        // block or silently discard the new job. (DropWrite would drop the
        // incoming item and still report success — not what callers need to hear.)
        _channel = Channel.CreateBounded<PushJob>(new BoundedChannelOptions(settings.QueueCapacity)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
        });
    }

    public bool TryEnqueue(PushJob job) => _channel.Writer.TryWrite(job);

    public IAsyncEnumerable<PushJob> ReadAllAsync(CancellationToken cancellationToken) =>
        _channel.Reader.ReadAllAsync(cancellationToken);
}
