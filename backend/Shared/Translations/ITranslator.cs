namespace Shared.Translations;

public interface ITranslator
{
    string Translate(string key);
    string Translate(string key, params object[] parameters);
}