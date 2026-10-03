"""
Клиент API справочника БИК ЦБ РФ Atlorium — банк по БИК, корсчёт, SWIFT, реквизиты.

Запуск (работает сразу, без регистрации — на демо-ключе):
    pip install -r requirements.txt
    python main.py

Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
ATLORIUM_API_KEY. Код при этом не меняется.
"""

import os
import sys
from dataclasses import dataclass

import requests

# Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
# данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
# Ответы детерминированы: один и тот же запрос всегда даёт один и тот же результат,
# поэтому на них можно писать стабильные тесты.
SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1"

API_KEY = os.environ.get("ATLORIUM_API_KEY", SANDBOX_KEY)
BASE_URL = os.environ.get("ATLORIUM_BASE_URL", "https://atlorium.com")

TIMEOUT = 30

# Коды справочника ЦБ РФ (формат ED807 / УФЭБС).
PARTICIPANT_ACTIVE = "PSAC"  # участник действующий
ACCOUNT_ACTIVE = "ACAC"  # счёт действующий

# Расшифровки самых частых кодов ограничений. Полный перечень публикует ЦБ РФ
# в альбоме УФЭБС; неизвестный код мы показываем как есть — любое ограничение
# в справочнике уже само по себе повод не отправлять платёж.
RESTRICTION_REASONS = {
    "LWDL": "отзыв (аннулирование) лицензии",
    "MRTR": "мораторий на удовлетворение требований кредиторов",
}


class AtloriumError(RuntimeError):
    """Ошибка API. Код HTTP разложен в человекочитаемую причину."""

    REASONS = {
        400: "Неверный формат БИК (ожидается ровно 9 цифр)",
        401: "API-ключ отсутствует, просрочен или недействителен",
        402: "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        404: "Участник с таким БИК не найден в справочнике ЦБ РФ",
        429: "Превышен лимит запросов — повторите позже",
        503: "Справочник БИК временно недоступен (за сбой на своей стороне мы не списываем деньги)",
    }

    def __init__(self, status: int, body: str):
        reason = self.REASONS.get(status, "Неизвестная ошибка")
        super().__init__(f"HTTP {status}: {reason}. Ответ сервера: {body[:200]}")
        self.status = status


def _get(path: str, params: dict | None = None) -> requests.Response:
    response = requests.get(
        f"{BASE_URL}{path}",
        params=params,
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Accept": "application/json",
        },
        timeout=TIMEOUT,
    )
    if not response.ok:
        raise AtloriumError(response.status_code, response.text)
    return response


def get_bank(bik: str) -> dict:
    """Реквизиты участника справочника по точному БИК (ровно 9 цифр)."""
    return _get(f"/api/cbr/{bik}").json()


def search_banks(query: str, limit: int = 20) -> dict:
    """Поиск участников по наименованию, городу, SWIFT или началу БИК.

    Если query состоит только из цифр — трактуется как БИК (поиск по началу кода),
    иначе ищется как подстрока в наименовании, населённом пункте и SWIFT.
    """
    return _get("/api/cbr/search", {"query": query, "limit": limit}).json()


def get_stats() -> dict:
    """Статистика справочника: размер, дата актуальности, готовность."""
    return _get("/api/cbr/stats").json()


# ── Контрольный ключ номера счёта (алгоритм ЦБ РФ) ────────────────────────────
# Официальный порядок расчёта контрольного ключа в номере лицевого счёта
# (Положение Банка России о плане счетов, приложение «Порядок расчёта
# контрольного ключа»). Алгоритм опубликован и однозначен:
#
#   1. К 20-значному номеру счёта слева приписывается «условный номер»:
#      • для корреспондентского счёта банка в Банке России (счёт начинается
#        на 301) — «0» + 5-я и 6-я цифры БИК;
#      • для любого другого счёта, открытого в самом банке, — последние
#        3 цифры БИК.
#   2. Получается 23 цифры. Каждая умножается на весовой коэффициент из
#      последовательности 7,1,3,7,1,3,… (она же и есть WEIGHTS ниже).
#   3. Сумма произведений берётся по модулю 10. Счёт корректен, если остаток
#      равен нулю.
#
# Это проверка от опечатки, а не подтверждение существования счёта: ключ
# сходится у любого правильно составленного номера, в том числе у никому
# не принадлежащего.

WEIGHTS = (7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1)


def check_account_control_key(bik: str, account: str) -> bool:
    """Проверяет контрольный ключ 20-значного номера счёта по БИК банка."""
    if len(bik) != 9 or not bik.isdigit():
        return False
    if len(account) != 20 or not account.isdigit():
        return False

    # Корсчёт банка в Банке России против счёта, открытого в самом банке.
    prefix = "0" + bik[4:6] if account.startswith("301") else bik[6:9]

    digits = prefix + account
    checksum = sum(weight * int(digit) for weight, digit in zip(WEIGHTS, digits))
    return checksum % 10 == 0


# ── Применение данных: проверка платёжных реквизитов ──────────────────────────
# Карточка банка сама по себе — просто JSON. Ценность появляется, когда из неё
# делают вывод. Ниже — набор проверок, которые делают перед отправкой платежа:
# существует ли банк, действует ли он, не отозвана ли лицензия, нет ли
# ограничений и не опечатался ли пользователь в номере счёта.


@dataclass
class Verdict:
    risks: list[str]
    notes: list[str]

    @property
    def is_risky(self) -> bool:
        return bool(self.risks)


def describe_restriction(code: str) -> str:
    reason = RESTRICTION_REASONS.get(code)
    return f"{code} — {reason}" if reason else code


def validate_payment_details(bank: dict, account: str | None = None) -> Verdict:
    """Выносит вердикт по платёжным реквизитам: банк + (опционально) номер счёта."""
    risks: list[str] = []
    notes: list[str] = []

    bik = bank.get("bik", "")

    # Главный стоп-фактор: участник исключён из справочника ЦБ РФ.
    status = bank.get("status")
    if status != PARTICIPANT_ACTIVE:
        risks.append(f"Участник недействующий: статус {status or 'не указан'}")

    # Ограничения на уровне участника: отзыв лицензии, мораторий и т.п.
    for restriction in bank.get("restrictions") or []:
        risks.append(
            f"Ограничение участника: {describe_restriction(restriction['code'])}"
            + (f" (с {restriction['date']})" if restriction.get("date") else "")
        )

    # Без корсчёта банк не может принимать платежи через платёжную систему ЦБ.
    corr_account = bank.get("corrAccount")
    if not corr_account:
        risks.append("У участника нет корреспондентского счёта")

    # Ограничения на уровне счетов участника: арест, приостановление операций.
    for bank_account in bank.get("accounts") or []:
        if bank_account.get("status") != ACCOUNT_ACTIVE:
            risks.append(
                f"Счёт {bank_account['account']} недействующий: "
                f"статус {bank_account.get('status') or 'не указан'}"
            )
        for restriction in bank_account.get("restrictions") or []:
            risks.append(
                f"Ограничение по счёту {bank_account['account']}: "
                f"{describe_restriction(restriction['code'])}"
            )

    # Контрольный ключ номера счёта получателя — ловит опечатку до платежа.
    if account:
        if len(account) != 20 or not account.isdigit():
            risks.append("Номер счёта должен состоять ровно из 20 цифр")
        elif not check_account_control_key(bik, account):
            risks.append(
                f"Контрольный ключ счёта {account} не сходится с БИК {bik} — "
                "в номере опечатка"
            )
        else:
            notes.append(f"Контрольный ключ счёта {account} верен")
    else:
        notes.append("Номер счёта не передан — проверка контрольного ключа пропущена")

    if bank.get("swift"):
        notes.append(f"SWIFT: {bank['swift']}")

    return Verdict(risks, notes)


def main() -> int:
    if API_KEY == SANDBOX_KEY:
        print("Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n")

    bik = sys.argv[1] if len(sys.argv) > 1 else "044525225"
    # Номер счёта получателя — второй аргумент. По умолчанию корректный
    # (контрольный ключ сходится) счёт для БИК 044525225.
    account = sys.argv[2] if len(sys.argv) > 2 else "40702810638000000000"

    try:
        bank = get_bank(bik)
        stats = get_stats()
    except AtloriumError as error:
        if error.status == 404:
            print(f"БИК {bik}: в справочнике ЦБ РФ не найден.")
            return 0
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1

    print(bank["name"])
    print(f"  БИК {bank['bik']} · рег. № {bank.get('registrationNumber') or '—'}")
    print(f"  Корсчёт: {bank.get('corrAccount') or '—'}")
    if bank.get("swift"):
        print(f"  SWIFT: {bank['swift']}")

    location = " ".join(
        part
        for part in (bank.get("postalIndex"), bank.get("localityType"), bank.get("locality"))
        if part
    )
    print(f"  Адрес: {location}, {bank.get('address') or '—'}")
    print(f"  Регион: {bank.get('regionCode') or '—'} · Статус: {bank.get('status')}")
    print(f"  В справочнике с {bank.get('dateIn')}")

    verdict = validate_payment_details(bank, account)
    print(f"\nПлатёж на счёт {account} (БИК {bik}):")

    if verdict.is_risky:
        print("РИСКИ:")
        for risk in verdict.risks:
            print(f"  [!] {risk}")
    else:
        print("Стоп-факторов не обнаружено — реквизиты можно использовать.")

    for note in verdict.notes:
        print(f"  [i] {note}")

    print(f"\nСправочник ЦБ РФ актуален на {stats.get('directoryDate')}, "
          f"участников: {stats.get('totalEntries')}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
