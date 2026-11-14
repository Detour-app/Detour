using System.Security.Cryptography;
using Microsoft.AspNetCore.DataProtection;

namespace Detour.Api.Services;

/// <summary>
/// Turns a rider's HERE key into the ciphertext stored on their account and back (#802).
///
/// The operator holding the key ring can decrypt it; this protects a database dump or a
/// backup that leaves without the ring, not the key from the server itself.
/// </summary>
public interface IHereTrafficKeyProtector
{
    string Protect(string key);

    /// <summary>
    /// The key, or null when the ciphertext no longer opens — the ring was lost or replaced,
    /// or the application name changed. Never throws for that case: a rider with an
    /// unreadable key is a state to report, not a server error.
    /// </summary>
    string? TryUnprotect(string protectedKey);
}

public class HereTrafficKeyProtector(IDataProtectionProvider provider) : IHereTrafficKeyProtector
{
    /// <summary>Versioned so a later format can live beside this one rather than replace it.</summary>
    public const string Purpose = "Detour.HereTrafficKey.v1";

    private readonly IDataProtector _protector = provider.CreateProtector(Purpose);

    public string Protect(string key) => _protector.Protect(key);

    public string? TryUnprotect(string protectedKey)
    {
        try
        {
            return _protector.Unprotect(protectedKey);
        }
        catch (CryptographicException)
        {
            return null;
        }
    }
}
