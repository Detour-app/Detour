namespace Shared.Api.Middlewares;

/// <summary>
/// Bypasses the automatic database transaction for the decorated target.
/// Recognised by <see cref="TransactionMiddlewareBase{T}"/> (controller / minimal-API
/// endpoints) and <see cref="TransactionHubFilter{T}"/> (SignalR hub methods).
/// </summary>
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Method, Inherited = true, AllowMultiple = false)]
public sealed class SkipTransactionAttribute : Attribute;
