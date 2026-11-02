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

    [Fact]
    public void Marking_a_report_filed_records_the_issue_once()
    {
        var report = ProblemReport.Create(null, Valid()).Value;

        Assert.False(report.MarkFiled("https://github.com/Detour-app/Detour/issues/900").IsFailure);
        var filedAt = report.FiledAt;
        Assert.False(report.MarkFiled("https://github.com/Detour-app/Detour/issues/901").IsFailure);

        Assert.NotNull(filedAt);
        Assert.Equal(filedAt, report.FiledAt);
        Assert.Equal("https://github.com/Detour-app/Detour/issues/900", report.FiledIssue);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("  ")]
    [InlineData("900")]
    [InlineData("http://github.com/Detour-app/Detour/issues/900")]
    public void A_filed_issue_is_an_https_url(string? issue)
    {
        var report = ProblemReport.Create(null, Valid()).Value;

        Assert.True(report.MarkFiled(issue).IsFailure);
        Assert.Null(report.FiledAt);
    }

    [Fact]
    public void A_filed_issue_is_at_most_512_characters()
    {
        var url = "https://github.com/";
        var report = ProblemReport.Create(null, Valid()).Value;

        Assert.True(report.MarkFiled(url + new string('a', 512 - url.Length + 1)).IsFailure);
        Assert.False(report.MarkFiled(url + new string('a', 512 - url.Length)).IsFailure);
    }

    [Fact]
    public void The_reporter_is_stable_per_account_and_differs_across_accounts()
    {
        var key = new byte[32];
        var account = Guid.NewGuid();

        var reporter = ProblemReport.Reporter(account, key);

        Assert.Equal(reporter, ProblemReport.Reporter(account, key));
        Assert.NotEqual(reporter, ProblemReport.Reporter(Guid.NewGuid(), key));
        Assert.NotEqual(reporter, ProblemReport.Reporter(account, [1, 2, 3]));
        Assert.Matches("^[0-9a-f]{64}$", reporter!);
        Assert.DoesNotContain(account.ToString("N"), reporter);
    }

    [Fact]
    public void An_anonymous_report_has_no_reporter()
    {
        Assert.Null(ProblemReport.Reporter(null, new byte[32]));
    }
}
