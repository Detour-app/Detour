using Detour.Domain;
using Detour.Domain.ProblemReports;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Shared.Database;

namespace Detour.Database.EntityConfigurations;

public class ProblemReportConfiguration : IEntityTypeConfiguration<ProblemReport>
{
    public void Configure(EntityTypeBuilder<ProblemReport> builder)
    {
        builder.ToTable("problem_reports");

        builder.HasKey(r => r.Id);
        builder.Property(r => r.Id).ValueGeneratedNever();

        builder.Property(r => r.ReceivedAt);
        builder.Property(r => r.OccurredAt);
        builder.Property(r => r.Kind).HasMaxLength(16);
        builder.Property(r => r.Platform).HasMaxLength(16);
        builder.Property(r => r.AppVersion).HasMaxLength(DetourLimits.ProblemReportDeviceFieldMaxLength);
        builder.Property(r => r.OsVersion).HasMaxLength(DetourLimits.ProblemReportDeviceFieldMaxLength);
        builder.Property(r => r.DeviceModel).HasMaxLength(DetourLimits.ProblemReportDeviceFieldMaxLength);
        builder.Property(r => r.Note).HasMaxLength(DetourLimits.ProblemReportNoteMaxLength);
        builder.Property(r => r.StackTrace);
        builder.Property(r => r.Events).HasColumnType(ColumnTypes.Jsonb);
        builder.Property(r => r.FiledAt);
        builder.Property(r => r.FiledIssue).HasMaxLength(DetourLimits.ProblemReportFiledIssueMaxLength);

        // Deleting an account takes its reports with it (BACKEND_SPEC §13). An anonymous report
        // has no account to go with, and leaves by retention alone.
        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(r => r.UserId)
            .IsRequired(false)
            .OnDelete(DeleteBehavior.Cascade);

        // Retention reads it on every insert, and the admin list (#820) pages by it, oldest first.
        builder.HasIndex(r => r.ReceivedAt);
    }
}
