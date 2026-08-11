namespace Shared.Database;

public class PaginatedResult<T>(int page, int pageSize, int totalItems, List<T> items) where T : class
{
    public int Page { get; set; } = page;
    public int PageSize { get; set; } = Math.Max(pageSize, 1);
    public int TotalPages => (int)Math.Ceiling((double)TotalItems / PageSize);
    public int TotalItems { get; set; } = totalItems;
    public List<T> Items { get; set; } = items;
}