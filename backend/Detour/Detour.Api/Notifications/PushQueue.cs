using System.Threading.Channels;
using Microsoft.Extensions.Logging;

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
    private readonly ILogger<PushQueue> _logger;
    private readonly int _capacity;

    public PushQueue(NotificationSettings settings, ILogger<PushQueue> logger)
    {
        _logger = logger;
        _capacity = settings.QueueCapacity;

        // Wait + TryWrite: a full queue makes TryEnqueue return false rather than
        // block or silently discard the new job. (DropWrite would drop the
        // incoming item and still report success — not what callers need to hear.)
        _channel = Channel.CreateBounded<PushJob>(new BoundedChannelOptions(settings.QueueCapacity)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
        });
    }

    public bool TryEnqueue(PushJob job)
    {
        if (_channel.Writer.TryWrite(job))
            return true;

        // Spec §1.4: a full queue drops the wake-ping — the device reconciles on its
        // next foreground sweep — but the drop is logged so a queue that is chronically
        // full is visible rather than silent.
        _logger.LogWarning(
            "Push queue full at capacity {Capacity}; dropped wake-ping for collapseKey {CollapseKey}",
            _capacity, job.CollapseKey);
        return false;
    }

    public IAsyncEnumerable<PushJob> ReadAllAsync(CancellationToken cancellationToken) =>
        _channel.Reader.ReadAllAsync(cancellationToken);
}
