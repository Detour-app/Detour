using System.Text.Json.Serialization;

namespace Detour.Api.Contracts;

/// <summary>One statement of a Digital Asset Links file, in the shape Android reads.</summary>
public record AssetLinkStatement(
    [property: JsonPropertyName("relation")] IReadOnlyList<string> Relation,
    [property: JsonPropertyName("target")] AssetLinkTarget Target);

public record AssetLinkTarget(
    [property: JsonPropertyName("namespace")] string Namespace,
    [property: JsonPropertyName("package_name")] string PackageName,
    [property: JsonPropertyName("sha256_cert_fingerprints")] IReadOnlyList<string> Sha256CertFingerprints);
