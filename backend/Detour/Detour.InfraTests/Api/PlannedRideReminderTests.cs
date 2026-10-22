using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.Api.Notifications;
using Detour.Domain.PlannedRides;
using Detour.InfraTests.Database;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;

namespace Detour.InfraTests.Api;

/// <summary>
/// The planned-ride reminder (#759): an hour before the start, one wake-ping to every rider who
/// answered going or maybe. Rides are created through the real API against a real Postgres;
/// each pass runs at a chosen instant against a capturing <see cref="IPushQueue"/>, the seam
/// the dispatcher already reads from (its path to the clouds is covered in
/// <see cref="PushNotificationTests"/>).
///
/// Every test starts its ride on its own day, far in the future, so a pass here never picks up
/// another test's ride and the host's own once-a-minute pass never picks up these.
/// </summary>
[Collection(PostgresCollection.Name)]
public class PlannedRideReminderTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;
    private readonly CapturingQueue _queue = new();

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Going_and_maybe_riders_are_reminded_an_hour_before_and_nobody_else()
    {
        var alex = await _factory.SignInAsync();
        var going = await _factory.SignInAsync();
        var maybe = await _factory.SignInAsync();
        var pending = await _factory.SignInAsync();
        var declined = await _factory.SignInAsync();
        foreach (var friend in new[] { going, maybe, pending, declined })
            await Befriend(alex, friend);

        var start = UniqueStart();
        var ride = await CreateRide(alex, start);
        await Invite(alex, ride, going, maybe, pending, declined);
        await Answer(going, ride, "going");
        await Answer(maybe, ride, "maybe");
        await Answer(declined, ride, "declined");

        (await RunAt(start - TimeSpan.FromMinutes(61))).Should().Be(0, "an hour and a minute out is too early");

        (await RunAt(start - TimeSpan.FromMinutes(59))).Should().Be(1);

        var job = _queue.Jobs.Should().ContainSingle().Subject;
        job.RecipientUserIds.Should().BeEquivalentTo([going.UserId, maybe.UserId]);
        job.Wake.Should().BeEquivalentTo(PushWake.RideReminder(ride));
    }

    [Fact]
    public async Task A_ride_is_reminded_once_per_start_time()
    {
        var (alex, blake) = await FriendsAsync();
        var start = UniqueStart();
        var ride = await CreateRide(alex, start);
        await Invite(alex, ride, blake);
        await Answer(blake, ride, "going");

        await RunAt(start - TimeSpan.FromMinutes(50));
        (await RunAt(start - TimeSpan.FromMinutes(20))).Should().Be(0);

        _queue.Jobs.Should().ContainSingle();
    }

    [Fact]
    public async Task Rescheduling_moves_the_reminder_to_the_new_start()
    {
        var (alex, blake) = await FriendsAsync();
        var start = UniqueStart();
        var ride = await CreateRide(alex, start);
        await Invite(alex, ride, blake);
        await Answer(blake, ride, "going");
        await RunAt(start - TimeSpan.FromMinutes(30));

        var moved = start + TimeSpan.FromHours(3);
        (await alex.PutAsJsonAsync($"/api/planned-rides/{ride}", Body(moved))).EnsureSuccessStatusCode();

        (await RunAt(start - TimeSpan.FromMinutes(10))).Should().Be(0, "the old start is no longer the ride's");
        (await RunAt(moved - TimeSpan.FromMinutes(30))).Should().Be(1);

        _queue.Jobs.Should().HaveCount(2);
    }

    [Fact]
    public async Task A_rescheduled_ride_not_yet_reminded_is_reminded_only_for_its_new_start()
    {
        var (alex, blake) = await FriendsAsync();
        var start = UniqueStart();
        var ride = await CreateRide(alex, start);
        await Invite(alex, ride, blake);
        await Answer(blake, ride, "going");

        var moved = start + TimeSpan.FromDays(1);
        (await alex.PutAsJsonAsync($"/api/planned-rides/{ride}", Body(moved))).EnsureSuccessStatusCode();

        (await RunAt(start - TimeSpan.FromMinutes(30))).Should().Be(0);
        (await RunAt(moved - TimeSpan.FromMinutes(30))).Should().Be(1);
    }

    [Fact]
    public async Task A_deleted_ride_is_never_reminded()
    {
        var (alex, blake) = await FriendsAsync();
        var start = UniqueStart();
        var ride = await CreateRide(alex, start);
        await Invite(alex, ride, blake);
        await Answer(blake, ride, "going");

        (await alex.DeleteAsync($"/api/planned-rides/{ride}")).EnsureSuccessStatusCode();

        (await RunAt(start - TimeSpan.FromMinutes(30))).Should().Be(0);
        _queue.Jobs.Should().BeEmpty();
    }

    [Fact]
    public async Task An_unfriended_rider_is_not_reminded()
    {
        var (alex, blake) = await FriendsAsync();
        var start = UniqueStart();
        var ride = await CreateRide(alex, start);
        await Invite(alex, ride, blake);
        await Answer(blake, ride, "going");

        (await blake.DeleteAsync($"/api/friends/{alex.UserId}")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        await RunAt(start - TimeSpan.FromMinutes(30));
        _queue.Jobs.Should().BeEmpty("nobody is going any more, so there is no one to wake");
    }

    [Fact]
    public async Task A_ride_that_has_started_is_not_reminded()
    {
        var (alex, blake) = await FriendsAsync();
        var start = UniqueStart();
        var ride = await CreateRide(alex, start);
        await Invite(alex, ride, blake);
        await Answer(blake, ride, "going");

        (await RunAt(start)).Should().Be(0);
        (await RunAt(start + TimeSpan.FromMinutes(5))).Should().Be(0);
    }

    private async Task<int> RunAt(DateTimeOffset now)
    {
        await using var scope = _factory.Services.CreateAsyncScope();
        var reminders = new PlannedRideReminders(
            scope.ServiceProvider.GetRequiredService<IPlannedRideRepository>(),
            _queue,
            NullLogger<PlannedRideReminders>.Instance);
        return await reminders.RunAsync(now, CancellationToken.None);
    }

    /// <summary>A random day in the next ~800 years from 2030: two tests' two-hour windows
    ///  landing on the same day is vanishingly unlikely.</summary>
    private static DateTimeOffset UniqueStart() =>
        new DateTimeOffset(2030, 1, 1, 10, 0, 0, TimeSpan.Zero).AddDays(Random.Shared.Next(0, 300_000));

    private async Task<(SignedInClient Alex, SignedInClient Blake)> FriendsAsync()
    {
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        await Befriend(alex, blake);
        return (alex, blake);
    }

    private static object Body(DateTimeOffset start) => new
    {
        title = "Ardennes loop",
        startsAtMs = start.ToUnixTimeMilliseconds(),
        meetingPoint = new { latitude = 50.36, longitude = 5.45, label = "Total, Barvaux" },
    };

    private static async Task<Guid> CreateRide(SignedInClient owner, DateTimeOffset start)
    {
        var response = await owner.PostAsJsonAsync("/api/planned-rides", Body(start));
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    private static async Task Invite(SignedInClient owner, Guid ride, params SignedInClient[] riders) =>
        (await owner.PostAsJsonAsync(
            $"/api/planned-rides/{ride}/invitations",
            new { riderIds = riders.Select(r => r.UserId).ToArray() }))
        .EnsureSuccessStatusCode();

    private static async Task Answer(SignedInClient rider, Guid ride, string answer) =>
        (await rider.PutAsJsonAsync($"/api/planned-rides/{ride}/rsvp", new { answer })).EnsureSuccessStatusCode();

    private static async Task Befriend(SignedInClient a, SignedInClient b)
    {
        (await a.PostAsJsonAsync("/api/friends/requests", new { username = b.Username }))
            .EnsureSuccessStatusCode();
        (await b.PostAsJsonAsync($"/api/friends/requests/{a.UserId}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }

    private sealed class CapturingQueue : IPushQueue
    {
        public List<PushJob> Jobs { get; } = [];

        public bool TryEnqueue(PushJob job)
        {
            Jobs.Add(job);
            return true;
        }

        public IAsyncEnumerable<PushJob> ReadAllAsync(CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }
}
