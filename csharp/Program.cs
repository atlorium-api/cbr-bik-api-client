// Клиент API справочника БИК ЦБ РФ Atlorium — банк по БИК, корсчёт, SWIFT, реквизиты.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//     dotnet run
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.

using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

// Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
// данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
// Ответы детерминированы: один и тот же запрос всегда даёт один и тот же результат,
// поэтому на них можно писать стабильные тесты.
const string SandboxKey = "ak_sandbox_demo_mockdata_v1";

var apiKey = Environment.GetEnvironmentVariable("ATLORIUM_API_KEY") ?? SandboxKey;
var baseUrl = Environment.GetEnvironmentVariable("ATLORIUM_BASE_URL") ?? "https://atlorium.com";

using var http = new HttpClient
{
    BaseAddress = new Uri(baseUrl),
    Timeout = TimeSpan.FromSeconds(30),
};
http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

var client = new CbrClient(http);

if (apiKey == SandboxKey)
{
    Console.WriteLine("Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n");
}

var bik = args.Length > 0 ? args[0] : "044525225";
// Номер счёта получателя — второй аргумент. По умолчанию корректный
// (контрольный ключ сходится) счёт для БИК 044525225.
var account = args.Length > 1 ? args[1] : "40702810638000000000";

BankInfo bank;
Stats stats;
try
{
    bank = await client.GetBankAsync(bik);
    stats = await client.GetStatsAsync();
}
catch (AtloriumException error) when (error.Status == HttpStatusCode.NotFound)
{
    Console.WriteLine($"БИК {bik}: в справочнике ЦБ РФ не найден.");
    return 0;
}
catch (AtloriumException error)
{
    Console.Error.WriteLine($"Ошибка: {error.Message}");
    return 1;
}

Console.WriteLine(bank.Name);
Console.WriteLine($"  БИК {bank.Bik} · рег. № {Dash(bank.RegistrationNumber)}");
Console.WriteLine($"  Корсчёт: {Dash(bank.CorrAccount)}");

if (bank.Swift is { Length: > 0 })
{
    Console.WriteLine($"  SWIFT: {bank.Swift}");
}

var location = string.Join(' ', new[] { bank.PostalIndex, bank.LocalityType, bank.Locality }
    .Where(part => !string.IsNullOrWhiteSpace(part)));
Console.WriteLine($"  Адрес: {location}, {Dash(bank.Address)}");
Console.WriteLine($"  Регион: {Dash(bank.RegionCode)} · Статус: {bank.Status}");
Console.WriteLine($"  В справочнике с {bank.DateIn}");

var verdict = PaymentDetailsValidator.Validate(bank, account);
Console.WriteLine($"\nПлатёж на счёт {account} (БИК {bik}):");

if (verdict.IsRisky)
{
    Console.WriteLine("РИСКИ:");
    foreach (var risk in verdict.Risks)
    {
        Console.WriteLine($"  [!] {risk}");
    }
}
else
{
    Console.WriteLine("Стоп-факторов не обнаружено — реквизиты можно использовать.");
}

foreach (var note in verdict.Notes)
{
    Console.WriteLine($"  [i] {note}");
}

Console.WriteLine($"\nСправочник ЦБ РФ актуален на {stats.DirectoryDate}, участников: {stats.TotalEntries}.");
return 0;

static string Dash(string? value) => string.IsNullOrWhiteSpace(value) ? "—" : value;

// ── Клиент ───────────────────────────────────────────────────────────────────

/// <summary>Ошибка API: HTTP-код разложен в человекочитаемую причину.</summary>
public sealed class AtloriumException(HttpStatusCode status, string body)
    : Exception($"HTTP {(int)status}: {Explain(status)}. Ответ сервера: {body[..Math.Min(200, body.Length)]}")
{
    public HttpStatusCode Status { get; } = status;

    private static string Explain(HttpStatusCode status) => (int)status switch
    {
        400 => "Неверный формат БИК (ожидается ровно 9 цифр)",
        401 => "API-ключ отсутствует, просрочен или недействителен",
        402 => "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        404 => "Участник с таким БИК не найден в справочнике ЦБ РФ",
        429 => "Превышен лимит запросов — повторите позже",
        503 => "Справочник БИК временно недоступен (за сбой на своей стороне мы не списываем деньги)",
        _ => "Неизвестная ошибка",
    };
}

public sealed class CbrClient(HttpClient http)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    /// <summary>Реквизиты участника справочника по точному БИК (ровно 9 цифр).</summary>
    public async Task<BankInfo> GetBankAsync(string bik)
        => await GetAsync<BankInfo>($"/api/cbr/{Uri.EscapeDataString(bik)}");

    /// <summary>
    /// Поиск участников по наименованию, городу, SWIFT или началу БИК.
    /// Если <paramref name="query"/> состоит только из цифр — трактуется как БИК
    /// (поиск по началу кода), иначе ищется как подстрока в наименовании,
    /// населённом пункте и SWIFT.
    /// </summary>
    public async Task<SearchResponse> SearchBanksAsync(string query, int limit = 20)
        => await GetAsync<SearchResponse>($"/api/cbr/search?query={Uri.EscapeDataString(query)}&limit={limit}");

    /// <summary>Статистика справочника: размер, дата актуальности, готовность.</summary>
    public async Task<Stats> GetStatsAsync() => await GetAsync<Stats>("/api/cbr/stats");

    private async Task<T> GetAsync<T>(string path)
    {
        using var response = await http.GetAsync(path);
        var body = await response.Content.ReadAsStringAsync();

        if (!response.IsSuccessStatusCode)
        {
            throw new AtloriumException(response.StatusCode, body);
        }

        return JsonSerializer.Deserialize<T>(body, JsonOptions)
               ?? throw new InvalidOperationException("Пустой ответ API.");
    }
}

// ── Модель ответа ────────────────────────────────────────────────────────────

/// <summary>Ограничение — на участнике или на конкретном счёте.</summary>
public sealed record Restriction
{
    public string Code { get; init; } = "";
    public string? Date { get; init; }
    public string? SuccessorBic { get; init; }
}

/// <summary>Счёт участника справочника.</summary>
public sealed record BankAccount
{
    public string Account { get; init; } = "";
    public string? AccountType { get; init; }
    public string? ControlKey { get; init; }
    public string? CbrBic { get; init; }
    public string? Status { get; init; }
    public string? DateIn { get; init; }
    public IReadOnlyList<Restriction> Restrictions { get; init; } = [];
}

/// <summary>Карточка участника справочника БИК ЦБ РФ.</summary>
public sealed record BankInfo
{
    public string Bik { get; init; } = "";
    public string Name { get; init; } = "";
    public string? EnglishName { get; init; }
    public string? CorrAccount { get; init; }
    public string? CountryCode { get; init; }
    public string? ParentBic { get; init; }
    public string? RegionCode { get; init; }
    public string? PostalIndex { get; init; }
    public string? LocalityType { get; init; }
    public string? Locality { get; init; }
    public string? Address { get; init; }
    public string? RegistrationNumber { get; init; }
    public string? Uid { get; init; }
    public string? DateIn { get; init; }
    public string? Swift { get; init; }
    public IReadOnlyList<string> SwiftCodes { get; init; } = [];
    public IReadOnlyList<BankAccount> Accounts { get; init; } = [];
    public IReadOnlyList<Restriction> Restrictions { get; init; } = [];
    public string? ParticipantType { get; init; }
    public string? ServiceCode { get; init; }
    public string? ExchangeType { get; init; }
    public string? Status { get; init; }
}

public sealed record SearchResponse
{
    public string Query { get; init; } = "";
    public IReadOnlyList<BankInfo> Results { get; init; } = [];
    public int Count { get; init; }
    public long ElapsedMs { get; init; }
}

public sealed record Stats
{
    public bool IsReady { get; init; }
    public int TotalEntries { get; init; }
    public int WithCorrAccount { get; init; }
    public int WithSwift { get; init; }
    public string? DirectoryDate { get; init; }
    public string? LoadedAtUtc { get; init; }
}

// ── Применение данных: проверка платёжных реквизитов ──────────────────────────
// Карточка банка сама по себе — просто JSON. Ценность появляется, когда из неё
// делают вывод. Ниже — набор проверок, которые делают перед отправкой платежа:
// существует ли банк, действует ли он, не отозвана ли лицензия, нет ли
// ограничений и не опечатался ли пользователь в номере счёта.

public sealed record Verdict(IReadOnlyList<string> Risks, IReadOnlyList<string> Notes)
{
    public bool IsRisky => Risks.Count > 0;
}

public static class PaymentDetailsValidator
{
    /// <summary>Коды справочника ЦБ РФ (формат ED807 / УФЭБС).</summary>
    private const string ParticipantActive = "PSAC"; // участник действующий
    private const string AccountActive = "ACAC";     // счёт действующий

    /// <summary>
    /// Расшифровки самых частых кодов ограничений. Полный перечень публикует ЦБ РФ
    /// в альбоме УФЭБС; неизвестный код мы показываем как есть — любое ограничение
    /// в справочнике уже само по себе повод не отправлять платёж.
    /// </summary>
    private static readonly Dictionary<string, string> RestrictionReasons = new()
    {
        ["LWDL"] = "отзыв (аннулирование) лицензии",
        ["MRTR"] = "мораторий на удовлетворение требований кредиторов",
    };

    // ── Контрольный ключ номера счёта (алгоритм ЦБ РФ) ───────────────────────
    // Официальный порядок расчёта контрольного ключа в номере лицевого счёта
    // (Положение Банка России о плане счетов, приложение «Порядок расчёта
    // контрольного ключа»). Алгоритм опубликован и однозначен:
    //
    //   1. К 20-значному номеру счёта слева приписывается «условный номер»:
    //      • для корреспондентского счёта банка в Банке России (счёт начинается
    //        на 301) — «0» + 5-я и 6-я цифры БИК;
    //      • для любого другого счёта, открытого в самом банке, — последние
    //        3 цифры БИК.
    //   2. Получается 23 цифры. Каждая умножается на весовой коэффициент из
    //      последовательности 7,1,3,7,1,3,… (она же и есть Weights ниже).
    //   3. Сумма произведений берётся по модулю 10. Счёт корректен, если остаток
    //      равен нулю.
    //
    // Это проверка от опечатки, а не подтверждение существования счёта: ключ
    // сходится у любого правильно составленного номера, в том числе у никому
    // не принадлежащего.
    private static readonly int[] Weights =
        [7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1];

    /// <summary>Проверяет контрольный ключ 20-значного номера счёта по БИК банка.</summary>
    public static bool CheckAccountControlKey(string bik, string account)
    {
        if (!IsDigits(bik, 9) || !IsDigits(account, 20))
        {
            return false;
        }

        // Корсчёт банка в Банке России против счёта, открытого в самом банке.
        var prefix = account.StartsWith("301", StringComparison.Ordinal)
            ? $"0{bik.Substring(4, 2)}"
            : bik.Substring(6, 3);

        var digits = prefix + account;
        var checksum = Weights.Select((weight, index) => weight * (digits[index] - '0')).Sum();
        return checksum % 10 == 0;
    }

    private static bool IsDigits(string value, int length)
        => value.Length == length && value.All(char.IsAsciiDigit);

    private static string Describe(string code)
        => RestrictionReasons.TryGetValue(code, out var reason) ? $"{code} — {reason}" : code;

    /// <summary>Выносит вердикт по платёжным реквизитам: банк + (опционально) номер счёта.</summary>
    public static Verdict Validate(BankInfo bank, string? account = null)
    {
        var risks = new List<string>();
        var notes = new List<string>();

        // Главный стоп-фактор: участник исключён из справочника ЦБ РФ.
        if (bank.Status != ParticipantActive)
        {
            risks.Add($"Участник недействующий: статус {bank.Status ?? "не указан"}");
        }

        // Ограничения на уровне участника: отзыв лицензии, мораторий и т.п.
        foreach (var restriction in bank.Restrictions)
        {
            var since = restriction.Date is { Length: > 0 } date ? $" (с {date})" : "";
            risks.Add($"Ограничение участника: {Describe(restriction.Code)}{since}");
        }

        // Без корсчёта банк не может принимать платежи через платёжную систему ЦБ.
        if (string.IsNullOrWhiteSpace(bank.CorrAccount))
        {
            risks.Add("У участника нет корреспондентского счёта");
        }

        // Ограничения на уровне счетов участника: арест, приостановление операций.
        foreach (var bankAccount in bank.Accounts)
        {
            if (bankAccount.Status != AccountActive)
            {
                risks.Add($"Счёт {bankAccount.Account} недействующий: статус {bankAccount.Status ?? "не указан"}");
            }

            foreach (var restriction in bankAccount.Restrictions)
            {
                risks.Add($"Ограничение по счёту {bankAccount.Account}: {Describe(restriction.Code)}");
            }
        }

        // Контрольный ключ номера счёта получателя — ловит опечатку до платежа.
        if (string.IsNullOrWhiteSpace(account))
        {
            notes.Add("Номер счёта не передан — проверка контрольного ключа пропущена");
        }
        else if (!IsDigits(account, 20))
        {
            risks.Add("Номер счёта должен состоять ровно из 20 цифр");
        }
        else if (!CheckAccountControlKey(bank.Bik, account))
        {
            risks.Add($"Контрольный ключ счёта {account} не сходится с БИК {bank.Bik} — в номере опечатка");
        }
        else
        {
            notes.Add($"Контрольный ключ счёта {account} верен");
        }

        if (bank.Swift is { Length: > 0 })
        {
            notes.Add($"SWIFT: {bank.Swift}");
        }

        return new Verdict(risks, notes);
    }
}
