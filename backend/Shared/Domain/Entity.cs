namespace Shared.Domain;

public interface IEntity
{
    Guid Id { get; }
}

public interface INamedEntity : IEntity
{
    string Name { get; }
}

public interface ISoftDeletable : IEntity
{
    DateTime? DeletedAt { get; }
}

public abstract class Entity : IEntity
{
    public virtual Guid Id { get; protected init; } = Guid.CreateVersion7();
}
