/**
 * Клиент API справочника БИК ЦБ РФ Atlorium — банк по БИК, корсчёт, SWIFT, реквизиты.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   npm install
 *   npm start
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

/**
 * Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
 * данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
 * Ответы детерминированы: один и тот же запрос всегда даёт один и тот же результат,
 * поэтому на них можно писать стабильные тесты.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const API_KEY = process.env.ATLORIUM_API_KEY ?? SANDBOX_KEY;
const BASE_URL = process.env.ATLORIUM_BASE_URL ?? 'https://atlorium.com';

const TIMEOUT_MS = 30_000;

/** Коды справочника ЦБ РФ (формат ED807 / УФЭБС). */
const PARTICIPANT_ACTIVE = 'PSAC'; // участник действующий
const ACCOUNT_ACTIVE = 'ACAC'; // счёт действующий

/**
 * Расшифровки самых частых кодов ограничений. Полный перечень публикует ЦБ РФ
 * в альбоме УФЭБС; неизвестный код мы показываем как есть — любое ограничение
 * в справочнике уже само по себе повод не отправлять платёж.
 */
const RESTRICTION_REASONS: Record<string, string> = {
  LWDL: 'отзыв (аннулирование) лицензии',
  MRTR: 'мораторий на удовлетворение требований кредиторов',
};

/** Ограничение — на участнике или на конкретном счёте. */
export interface Restriction {
  code: string;
  date: string | null;
  successorBic?: string | null;
}

/** Счёт участника справочника. */
export interface BankAccount {
  account: string;
  accountType: string | null;
  controlKey: string | null;
  cbrBic: string | null;
  status: string | null;
  dateIn: string | null;
  restrictions: Restriction[];
}

/** Карточка участника справочника БИК ЦБ РФ. */
export interface BankInfo {
  bik: string;
  name: string;
  englishName: string | null;
  corrAccount: string | null;
  countryCode: string | null;
  parentBic: string | null;
  regionCode: string | null;
  postalIndex: string | null;
  localityType: string | null;
  locality: string | null;
  address: string | null;
  registrationNumber: string | null;
  uid: string | null;
  dateIn: string | null;
  swift: string | null;
  swiftCodes: string[];
  accounts: BankAccount[];
  restrictions: Restriction[];
  participantType: string | null;
  serviceCode: string | null;
  exchangeType: string | null;
  status: string | null;
}

export interface SearchResponse {
  query: string;
  results: BankInfo[];
  count: number;
  elapsedMs: number;
}

export interface Stats {
  isReady: boolean;
  totalEntries: number;
  withCorrAccount: number;
  withSwift: number;
  directoryDate: string | null;
  loadedAtUtc: string | null;
}

const ERROR_REASONS: Record<number, string> = {
  400: 'Неверный формат БИК (ожидается ровно 9 цифр)',
  401: 'API-ключ отсутствует, просрочен или недействителен',
  402: 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
  404: 'Участник с таким БИК не найден в справочнике ЦБ РФ',
  429: 'Превышен лимит запросов — повторите позже',
  503: 'Справочник БИК временно недоступен (за сбой на своей стороне мы не списываем деньги)',
};

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
export class AtloriumError extends Error {
  constructor(readonly status: number, body: string) {
    const reason = ERROR_REASONS[status] ?? 'Неизвестная ошибка';
    super(`HTTP ${status}: ${reason}. Ответ сервера: ${body.slice(0, 200)}`);
    this.name = 'AtloriumError';
  }
}

async function request(path: string, params: Record<string, string> = {}): Promise<Response> {
  const url = new URL(path, BASE_URL);
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }

  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${API_KEY}`,
      Accept: 'application/json',
    },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });

  if (!response.ok) {
    throw new AtloriumError(response.status, await response.text());
  }
  return response;
}

/** Реквизиты участника справочника по точному БИК (ровно 9 цифр). */
export async function getBank(bik: string): Promise<BankInfo> {
  const response = await request(`/api/cbr/${encodeURIComponent(bik)}`);
  return response.json() as Promise<BankInfo>;
}

/**
 * Поиск участников по наименованию, городу, SWIFT или началу БИК.
 *
 * Если `query` состоит только из цифр — трактуется как БИК (поиск по началу кода),
 * иначе ищется как подстрока в наименовании, населённом пункте и SWIFT.
 */
export async function searchBanks(query: string, limit = 20): Promise<SearchResponse> {
  const response = await request('/api/cbr/search', { query, limit: String(limit) });
  return response.json() as Promise<SearchResponse>;
}

/** Статистика справочника: размер, дата актуальности, готовность. */
export async function getStats(): Promise<Stats> {
  const response = await request('/api/cbr/stats');
  return response.json() as Promise<Stats>;
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

const WEIGHTS = [7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1] as const;

const DIGITS_9 = /^\d{9}$/;
const DIGITS_20 = /^\d{20}$/;

/** Проверяет контрольный ключ 20-значного номера счёта по БИК банка. */
export function checkAccountControlKey(bik: string, account: string): boolean {
  if (!DIGITS_9.test(bik) || !DIGITS_20.test(account)) {
    return false;
  }

  // Корсчёт банка в Банке России против счёта, открытого в самом банке.
  const prefix = account.startsWith('301') ? `0${bik.slice(4, 6)}` : bik.slice(6, 9);

  const digits = `${prefix}${account}`;
  const checksum = WEIGHTS.reduce((sum, weight, index) => sum + weight * Number(digits[index]), 0);
  return checksum % 10 === 0;
}

// ── Применение данных: проверка платёжных реквизитов ──────────────────────────
// Карточка банка сама по себе — просто JSON. Ценность появляется, когда из неё
// делают вывод. Ниже — набор проверок, которые делают перед отправкой платежа:
// существует ли банк, действует ли он, не отозвана ли лицензия, нет ли
// ограничений и не опечатался ли пользователь в номере счёта.

export interface Verdict {
  risks: string[];
  notes: string[];
}

function describeRestriction(code: string): string {
  const reason = RESTRICTION_REASONS[code];
  return reason ? `${code} — ${reason}` : code;
}

/** Выносит вердикт по платёжным реквизитам: банк + (опционально) номер счёта. */
export function validatePaymentDetails(bank: BankInfo, account?: string): Verdict {
  const risks: string[] = [];
  const notes: string[] = [];

  // Главный стоп-фактор: участник исключён из справочника ЦБ РФ.
  if (bank.status !== PARTICIPANT_ACTIVE) {
    risks.push(`Участник недействующий: статус ${bank.status ?? 'не указан'}`);
  }

  // Ограничения на уровне участника: отзыв лицензии, мораторий и т.п.
  for (const restriction of bank.restrictions ?? []) {
    const since = restriction.date ? ` (с ${restriction.date})` : '';
    risks.push(`Ограничение участника: ${describeRestriction(restriction.code)}${since}`);
  }

  // Без корсчёта банк не может принимать платежи через платёжную систему ЦБ.
  if (!bank.corrAccount) {
    risks.push('У участника нет корреспондентского счёта');
  }

  // Ограничения на уровне счетов участника: арест, приостановление операций.
  for (const bankAccount of bank.accounts ?? []) {
    if (bankAccount.status !== ACCOUNT_ACTIVE) {
      risks.push(
        `Счёт ${bankAccount.account} недействующий: статус ${bankAccount.status ?? 'не указан'}`,
      );
    }
    for (const restriction of bankAccount.restrictions ?? []) {
      risks.push(
        `Ограничение по счёту ${bankAccount.account}: ${describeRestriction(restriction.code)}`,
      );
    }
  }

  // Контрольный ключ номера счёта получателя — ловит опечатку до платежа.
  if (account) {
    if (!DIGITS_20.test(account)) {
      risks.push('Номер счёта должен состоять ровно из 20 цифр');
    } else if (!checkAccountControlKey(bank.bik, account)) {
      risks.push(
        `Контрольный ключ счёта ${account} не сходится с БИК ${bank.bik} — в номере опечатка`,
      );
    } else {
      notes.push(`Контрольный ключ счёта ${account} верен`);
    }
  } else {
    notes.push('Номер счёта не передан — проверка контрольного ключа пропущена');
  }

  if (bank.swift) {
    notes.push(`SWIFT: ${bank.swift}`);
  }

  return { risks, notes };
}

async function main(): Promise<void> {
  if (API_KEY === SANDBOX_KEY) {
    console.log('Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n');
  }

  const bik = process.argv[2] ?? '044525225';
  // Номер счёта получателя — второй аргумент. По умолчанию корректный
  // (контрольный ключ сходится) счёт для БИК 044525225.
  const account = process.argv[3] ?? '40702810638000000000';

  let bank: BankInfo;
  let stats: Stats;
  try {
    bank = await getBank(bik);
    stats = await getStats();
  } catch (error) {
    if (error instanceof AtloriumError && error.status === 404) {
      console.log(`БИК ${bik}: в справочнике ЦБ РФ не найден.`);
      return;
    }
    throw error;
  }

  console.log(bank.name);
  console.log(`  БИК ${bank.bik} · рег. № ${bank.registrationNumber ?? '—'}`);
  console.log(`  Корсчёт: ${bank.corrAccount ?? '—'}`);
  if (bank.swift) {
    console.log(`  SWIFT: ${bank.swift}`);
  }

  const location = [bank.postalIndex, bank.localityType, bank.locality].filter(Boolean).join(' ');
  console.log(`  Адрес: ${location}, ${bank.address ?? '—'}`);
  console.log(`  Регион: ${bank.regionCode ?? '—'} · Статус: ${bank.status}`);
  console.log(`  В справочнике с ${bank.dateIn}`);

  const verdict = validatePaymentDetails(bank, account);
  console.log(`\nПлатёж на счёт ${account} (БИК ${bik}):`);

  if (verdict.risks.length > 0) {
    console.log('РИСКИ:');
    verdict.risks.forEach((risk) => console.log(`  [!] ${risk}`));
  } else {
    console.log('Стоп-факторов не обнаружено — реквизиты можно использовать.');
  }
  verdict.notes.forEach((note) => console.log(`  [i] ${note}`));

  console.log(
    `\nСправочник ЦБ РФ актуален на ${stats.directoryDate}, участников: ${stats.totalEntries}.`,
  );
}

// Запуск только когда файл выполняется напрямую, а не импортируется.
if (process.argv[1]?.includes('index')) {
  main().catch((error: unknown) => {
    console.error('Ошибка:', error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
