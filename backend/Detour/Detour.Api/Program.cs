using Detour.Api;
using Detour.Database;
using Detour.Database.Configuration;
using Microsoft.EntityFrameworkCore;
using Shared.Configuration;
using Shared.Database;

var builder = WebApplication.CreateBuilder(args);

// Explicit user-secrets load so a local `dotnet run` works regardless of environment; a no-op
// in a container. Skipped under Testing so a developer's secrets store cannot override the
// committed test configuration — which would pass locally and fail in CI, or worse, the reverse.
if (!builder.Environment.IsEnvironment("Testing"))
    builder.Configuration.AddUserSecrets(typeof(Program).Assembly, optional: true, reloadOnChange: true);

// '<Key>_FILE' -> read the value from that path. Added last so it sees every source above it.
// Lets a deployment mount the database password and the client secret as files instead of
// putting them in the environment, where every child process can read them.
builder.Configuration.AddFileBackedSecrets();

var startup = new Startup(builder.Configuration);
startup.ConfigureLogging(builder.Host);
startup.ConfigureServices(builder.Services);

var app = builder.Build();

// Single service, single database: applying migrations at startup means the schema can never
// lag the code that expects it. A deployment that migrates out of band sets Database:SkipMigrations.
{
    var databaseSettings = app.Services.GetRequiredService<DatabaseSettings>();
    if (!databaseSettings.SkipMigrations)
    {
        using var scope = app.Services.CreateScope();
        var factory = scope.ServiceProvider.GetRequiredService<ICustomDbContextFactory<DetourDbContext>>();
        await using var db = factory.CreateNew();
        await db.Database.MigrateAsync();
    }
}

startup.Configure(app);

await app.RunAsync();

// Exposed for WebApplicationFactory<Program> integration tests.
public partial class Program;
