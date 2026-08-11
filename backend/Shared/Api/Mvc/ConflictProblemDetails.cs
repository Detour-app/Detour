using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Shared.Api.Mvc;

/// <summary>
/// Builds the <see cref="ProblemDetails"/> returned when a request is refused because the
/// target resource is still referenced by other entities (e.g. a delete blocked by links).
/// All properties are fixed except the human-readable <c>detail</c> and, for
/// <see cref="ForLinkedEntities{TEntity}"/>, the linked-entity list surfaced under the
/// <c>linkedEntities</c> extension.
/// </summary>
public static class ConflictProblemDetails
{
    public static ProblemDetails ForLinkedEntities<TEntity>(
        string detail,
        IReadOnlyCollection<TEntity> linkedEntities) => new()
        {
            Type = ProblemTypes.Conflict,
            Title = "Conflict",
            Status = StatusCodes.Status409Conflict,
            Detail = detail,
            Extensions = { ["linkedEntities"] = linkedEntities }
        };

    /// <summary>
    /// Same conflict shape, for refusals that have a reason to explain but no list of
    /// referencing entities to enumerate. Omits the <c>linkedEntities</c> extension rather
    /// than sending an empty one, so clients can't read "nothing is linked" from a refusal.
    /// </summary>
    public static ProblemDetails ForReason(string detail) => new()
    {
        Type = ProblemTypes.Conflict,
        Title = "Conflict",
        Status = StatusCodes.Status409Conflict,
        Detail = detail
    };
}
