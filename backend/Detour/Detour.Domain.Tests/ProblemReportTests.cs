using System.Text.Json;
using Detour.Domain.ProblemReports;

namespace Detour.Domain.Tests;

public class ProblemReportTests
{
    private static ProblemReportDetails Valid() => new(
        Kind: "crash",
        Platform: "android",
        AppVersion: "3.90.0",
        OsVersion: "Android 15",
        DeviceModel: "Pixel 8",
        OccurredAtMs: 1_761_000_000_000,
        Note: null,
        StackTrace: "java.lang.IllegalStateException\n\tat Foo.bar(Foo.kt:1)",
        Events: ["service start: boot", "trip: idle -> recording"]);

    [Fact]
    public void A_complete_report_is_stored_as_sent()
    {
        var userId = Guid.NewGuid();

        var report = ProblemReport.Create(userId, Valid()).Value;

        Assert.Equal("crash", report.Kind);
        Assert.Equal(userId, report.UserId);
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1_761_000_000_000), report.OccurredAt);
        Assert.Equal(["service start: boot", "trip: idle -> recording"],
            JsonSerializer.Deserialize<string[]>(report.Events)!);
        Assert.Null(report.FiledAt);
    }

    [Fact]
    public void A_report_without_an_account_names_nobody()
    {
        Assert.Null(ProblemReport.Create(null, Valid()).Value.UserId);
    }

    [Fact]
    public void Missing_events_are_an_empty_log()
    {
        var report = ProblemReport.Create(null, Valid() with { Events = null }).Value;

        Assert.Equal("[]", report.Events);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("Crash")]
    [InlineData("feedback")]
    public void Only_crash_and_manual_are_kinds(string? kind)
    {
        Assert.True(ProblemReport.Create(null, Valid() with { Kind = kind }).IsFailure);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("web")]
    public void Only_android_and_ios_are_platforms(string? platform)
    {
        Assert.True(ProblemReport.Create(null, Valid() with { Platform = platform }).IsFailure);
    }

    [Fact]
    public void The_device_fields_are_required()
    {
        Assert.True(ProblemReport.Create(null, Valid() with { DeviceModel = " " }).IsFailure);
        Assert.True(ProblemReport.Create(null, Valid() with { AppVersion = null }).IsFailure);
    }

    [Theory]
    [InlineData(0L)]
    [InlineData(-1L)]
    [InlineData(long.MaxValue)]
    public void The_instant_must_be_a_real_one(long occurredAtMs)
    {
        Assert.True(ProblemReport.Create(null, Valid() with { OccurredAtMs = occurredAtMs }).IsFailure);
    }

    [Fact]
    public void A_note_is_at_most_2000_characters()
    {
        Assert.False(ProblemReport.Create(null, Valid() with { Note = new string('a', 2000) }).IsFailure);
        Assert.True(ProblemReport.Create(null, Valid() with { Note = new string('a', 2001) }).IsFailure);
    }

    [Fact]
    public void A_stack_trace_is_at_most_64_KB_of_UTF8()
    {
        Assert.False(ProblemReport.Create(null, Valid() with { StackTrace = new string('a', 64 * 1024) }).IsFailure);
        // Two bytes each: the cap is on bytes, not characters.
        Assert.True(ProblemReport.Create(null, Valid() with { StackTrace = new string('é', 32 * 1024 + 1) }).IsFailure);
    }

    [Fact]
    public void The_event_log_is_at_most_256_KB_in_total()
    {
        var quarter = new string('a', 64 * 1024);

        Assert.False(ProblemReport.Create(null, Valid() with { Events = [quarter, quarter, quarter, quarter] }).IsFailure);
        Assert.True(ProblemReport.Create(null, Valid() with { Events = [quarter, quarter, quarter, quarter, "a"] }).IsFailure);
    }
}
