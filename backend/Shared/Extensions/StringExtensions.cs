using System.Text;

namespace Shared.Extensions;

public static class StringExtensions
{
    public static string ToSnakeCase(this string input)
    {
        if (string.IsNullOrEmpty(input))
            return input;

        var result = new StringBuilder();

        for (int i = 0; i < input.Length; i++)
        {
            if (char.IsWhiteSpace(input[i]))
            {
                result.Append('_');
            }
            else if (char.IsUpper(input[i]) && i > 0)
            {
                result.Append('_').Append(char.ToLower(input[i]));
            }
            else
            {
                result.Append(char.ToLower(input[i]));
            }
        }

        return result.ToString().Trim();
    }

    public static string FromSnakeCase(this string input)
    {
        if (string.IsNullOrEmpty(input))
            return input;

        var parts = SplitOnUnderscores(input);

        if (parts.Count == 0)
            return string.Empty;

        return AssembleTokens(parts);
    }

    private static List<string> SplitOnUnderscores(string input)
    {
        var parts = new List<string>();
        int start = 0;
        for (int i = 0; i < input.Length; i++)
        {
            if (input[i] == '_')
            {
                if (i > start)
                    parts.Add(input.AsSpan(start, i - start).ToString());
                start = i + 1;
            }
        }

        if (start < input.Length)
            parts.Add(input.AsSpan(start).ToString());

        return parts;
    }

    private static string CapitalizeToken(string p)
    {
        if (p.Length == 0) return string.Empty;
        var sb = new StringBuilder(p.Length);
        sb.Append(char.ToUpperInvariant(p[0]));
        for (int k = 1; k < p.Length; k++)
            sb.Append(char.ToLowerInvariant(p[k]));
        return sb.ToString();
    }

    private static string AssembleTokens(List<string> parts)
    {
        var result = new StringBuilder();
        int i = 0;
        while (i < parts.Count)
        {
            if (parts[i].Length == 1)
            {
                i = AppendAcronymSegment(parts, i, result);
            }
            else
            {
                AppendSeparator(result);
                result.Append(CapitalizeToken(parts[i]));
                i++;
            }
        }

        return result.ToString();
    }

    private static int AppendAcronymSegment(List<string> parts, int start, StringBuilder result)
    {
        int j = start;
        var acronym = new StringBuilder();
        while (j < parts.Count && parts[j].Length == 1)
        {
            acronym.Append(char.ToUpperInvariant(parts[j][0]));
            j++;
        }

        AppendSeparator(result);

        if (j < parts.Count && parts[j].Length > 1)
        {
            result.Append(acronym);
            result.Append(CapitalizeToken(parts[j]));
            return j + 1;
        }

        result.Append(acronym);
        return j;
    }

    private static void AppendSeparator(StringBuilder result)
    {
        if (result.Length > 0)
            result.Append(' ');
    }
}