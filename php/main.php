<?php

/**
 * Клиент API справочника БИК ЦБ РФ Atlorium — банк по БИК, корсчёт, SWIFT, реквизиты.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   php main.php
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

declare(strict_types=1);

/**
 * Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
 * данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
 * Ответы детерминированы: один и тот же запрос всегда даёт один и тот же результат,
 * поэтому на них можно писать стабильные тесты.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const TIMEOUT = 30;

/** Коды справочника ЦБ РФ (формат ED807 / УФЭБС). */
const PARTICIPANT_ACTIVE = 'PSAC'; // участник действующий
const ACCOUNT_ACTIVE = 'ACAC';     // счёт действующий

/**
 * Расшифровки самых частых кодов ограничений. Полный перечень публикует ЦБ РФ
 * в альбоме УФЭБС; неизвестный код мы показываем как есть — любое ограничение
 * в справочнике уже само по себе повод не отправлять платёж.
 */
const RESTRICTION_REASONS = [
    'LWDL' => 'отзыв (аннулирование) лицензии',
    'MRTR' => 'мораторий на удовлетворение требований кредиторов',
];

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
final class AtloriumError extends RuntimeException
{
    private const REASONS = [
        400 => 'Неверный формат БИК (ожидается ровно 9 цифр)',
        401 => 'API-ключ отсутствует, просрочен или недействителен',
        402 => 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
        404 => 'Участник с таким БИК не найден в справочнике ЦБ РФ',
        429 => 'Превышен лимит запросов — повторите позже',
        503 => 'Справочник БИК временно недоступен (за сбой на своей стороне мы не списываем деньги)',
    ];

    public function __construct(public readonly int $status, string $body)
    {
        $reason = self::REASONS[$status] ?? 'Неизвестная ошибка';
        parent::__construct(sprintf(
            'HTTP %d: %s. Ответ сервера: %s',
            $status,
            $reason,
            mb_substr($body, 0, 200)
        ));
    }
}

final class CbrClient
{
    private string $apiKey;
    private string $baseUrl;

    public function __construct(?string $apiKey = null, ?string $baseUrl = null)
    {
        $this->apiKey = $apiKey ?? (getenv('ATLORIUM_API_KEY') ?: SANDBOX_KEY);
        $this->baseUrl = $baseUrl ?? (getenv('ATLORIUM_BASE_URL') ?: 'https://atlorium.com');
    }

    public function isSandbox(): bool
    {
        return $this->apiKey === SANDBOX_KEY;
    }

    /**
     * @param array<string, string> $params
     * @return array<string, mixed>
     */
    private function get(string $path, array $params = []): array
    {
        $url = $this->baseUrl . $path;
        if ($params !== []) {
            $url .= '?' . http_build_query($params);
        }

        $curl = curl_init($url);
        curl_setopt_array($curl, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => TIMEOUT,
            CURLOPT_HTTPHEADER => [
                'Authorization: Bearer ' . $this->apiKey,
                'Accept: application/json',
            ],
        ]);

        $body = curl_exec($curl);
        if ($body === false) {
            $error = curl_error($curl);
            curl_close($curl);
            throw new RuntimeException("Сетевая ошибка: {$error}");
        }

        $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        curl_close($curl);

        if ($status !== 200) {
            throw new AtloriumError($status, (string) $body);
        }

        return json_decode((string) $body, true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * Реквизиты участника справочника по точному БИК (ровно 9 цифр).
     *
     * @return array<string, mixed>
     */
    public function getBank(string $bik): array
    {
        return $this->get('/api/cbr/' . rawurlencode($bik));
    }

    /**
     * Поиск участников по наименованию, городу, SWIFT или началу БИК.
     *
     * Если $query состоит только из цифр — трактуется как БИК (поиск по началу кода),
     * иначе ищется как подстрока в наименовании, населённом пункте и SWIFT.
     *
     * @return array<string, mixed>
     */
    public function searchBanks(string $query, int $limit = 20): array
    {
        return $this->get('/api/cbr/search', [
            'query' => $query,
            'limit' => (string) $limit,
        ]);
    }

    /**
     * Статистика справочника: размер, дата актуальности, готовность.
     *
     * @return array<string, mixed>
     */
    public function getStats(): array
    {
        return $this->get('/api/cbr/stats');
    }
}

// ── Контрольный ключ номера счёта (алгоритм ЦБ РФ) ────────────────────────────
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
//      последовательности 7,1,3,7,1,3,… (она же и есть WEIGHTS ниже).
//   3. Сумма произведений берётся по модулю 10. Счёт корректен, если остаток
//      равен нулю.
//
// Это проверка от опечатки, а не подтверждение существования счёта: ключ
// сходится у любого правильно составленного номера, в том числе у никому
// не принадлежащего.

const WEIGHTS = [7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1];

/** Проверяет контрольный ключ 20-значного номера счёта по БИК банка. */
function checkAccountControlKey(string $bik, string $account): bool
{
    if (preg_match('/^\d{9}$/', $bik) !== 1 || preg_match('/^\d{20}$/', $account) !== 1) {
        return false;
    }

    // Корсчёт банка в Банке России против счёта, открытого в самом банке.
    $prefix = str_starts_with($account, '301')
        ? '0' . substr($bik, 4, 2)
        : substr($bik, 6, 3);

    $digits = $prefix . $account;
    $checksum = 0;
    foreach (WEIGHTS as $index => $weight) {
        $checksum += $weight * (int) $digits[$index];
    }

    return $checksum % 10 === 0;
}

// ── Применение данных: проверка платёжных реквизитов ──────────────────────────
// Карточка банка сама по себе — просто JSON. Ценность появляется, когда из неё
// делают вывод. Ниже — набор проверок, которые делают перед отправкой платежа:
// существует ли банк, действует ли он, не отозвана ли лицензия, нет ли
// ограничений и не опечатался ли пользователь в номере счёта.

function describeRestriction(string $code): string
{
    $reason = RESTRICTION_REASONS[$code] ?? null;

    return $reason === null ? $code : "{$code} — {$reason}";
}

/**
 * Выносит вердикт по платёжным реквизитам: банк + (опционально) номер счёта.
 *
 * @param array<string, mixed> $bank
 * @return array{risks: list<string>, notes: list<string>}
 */
function validatePaymentDetails(array $bank, ?string $account = null): array
{
    $risks = [];
    $notes = [];

    $bik = (string) ($bank['bik'] ?? '');

    // Главный стоп-фактор: участник исключён из справочника ЦБ РФ.
    $status = $bank['status'] ?? null;
    if ($status !== PARTICIPANT_ACTIVE) {
        $risks[] = 'Участник недействующий: статус ' . ($status ?? 'не указан');
    }

    // Ограничения на уровне участника: отзыв лицензии, мораторий и т.п.
    foreach ($bank['restrictions'] ?? [] as $restriction) {
        $since = empty($restriction['date']) ? '' : " (с {$restriction['date']})";
        $risks[] = 'Ограничение участника: ' . describeRestriction($restriction['code']) . $since;
    }

    // Без корсчёта банк не может принимать платежи через платёжную систему ЦБ.
    if (empty($bank['corrAccount'])) {
        $risks[] = 'У участника нет корреспондентского счёта';
    }

    // Ограничения на уровне счетов участника: арест, приостановление операций.
    foreach ($bank['accounts'] ?? [] as $bankAccount) {
        if (($bankAccount['status'] ?? null) !== ACCOUNT_ACTIVE) {
            $risks[] = "Счёт {$bankAccount['account']} недействующий: статус "
                . ($bankAccount['status'] ?? 'не указан');
        }
        foreach ($bankAccount['restrictions'] ?? [] as $restriction) {
            $risks[] = "Ограничение по счёту {$bankAccount['account']}: "
                . describeRestriction($restriction['code']);
        }
    }

    // Контрольный ключ номера счёта получателя — ловит опечатку до платежа.
    if ($account === null || $account === '') {
        $notes[] = 'Номер счёта не передан — проверка контрольного ключа пропущена';
    } elseif (preg_match('/^\d{20}$/', $account) !== 1) {
        $risks[] = 'Номер счёта должен состоять ровно из 20 цифр';
    } elseif (!checkAccountControlKey($bik, $account)) {
        $risks[] = "Контрольный ключ счёта {$account} не сходится с БИК {$bik} — в номере опечатка";
    } else {
        $notes[] = "Контрольный ключ счёта {$account} верен";
    }

    if (!empty($bank['swift'])) {
        $notes[] = "SWIFT: {$bank['swift']}";
    }

    return ['risks' => $risks, 'notes' => $notes];
}

// ── Демонстрация ─────────────────────────────────────────────────────────────

function dash(?string $value): string
{
    return ($value === null || $value === '') ? '—' : $value;
}

$client = new CbrClient();

if ($client->isSandbox()) {
    echo "Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n\n";
}

$bik = $argv[1] ?? '044525225';
// Номер счёта получателя — второй аргумент. По умолчанию корректный
// (контрольный ключ сходится) счёт для БИК 044525225.
$account = $argv[2] ?? '40702810638000000000';

try {
    $bank = $client->getBank($bik);
    $stats = $client->getStats();
} catch (AtloriumError $error) {
    if ($error->status === 404) {
        echo "БИК {$bik}: в справочнике ЦБ РФ не найден.\n";
        exit(0);
    }
    fwrite(STDERR, "Ошибка: {$error->getMessage()}\n");
    exit(1);
}

echo "{$bank['name']}\n";
echo '  БИК ' . $bank['bik'] . ' · рег. № ' . dash($bank['registrationNumber']) . "\n";
echo '  Корсчёт: ' . dash($bank['corrAccount']) . "\n";

if (!empty($bank['swift'])) {
    echo "  SWIFT: {$bank['swift']}\n";
}

$location = implode(' ', array_filter([
    $bank['postalIndex'],
    $bank['localityType'],
    $bank['locality'],
]));
echo '  Адрес: ' . $location . ', ' . dash($bank['address']) . "\n";
echo '  Регион: ' . dash($bank['regionCode']) . ' · Статус: ' . $bank['status'] . "\n";
echo "  В справочнике с {$bank['dateIn']}\n";

$verdict = validatePaymentDetails($bank, $account);
echo "\nПлатёж на счёт {$account} (БИК {$bik}):\n";

if ($verdict['risks'] !== []) {
    echo "РИСКИ:\n";
    foreach ($verdict['risks'] as $risk) {
        echo "  [!] {$risk}\n";
    }
} else {
    echo "Стоп-факторов не обнаружено — реквизиты можно использовать.\n";
}

foreach ($verdict['notes'] as $note) {
    echo "  [i] {$note}\n";
}

echo "\nСправочник ЦБ РФ актуален на {$stats['directoryDate']}, участников: {$stats['totalEntries']}.\n";
