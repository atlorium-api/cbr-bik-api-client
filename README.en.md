# CBR BIC directory — Russian bank lookup by BIC, correspondent account and details

[Русский](README.md) · **English**

[![Live API tests](https://github.com/atlorium-api/cbr-bik-api-client/actions/workflows/examples.yml/badge.svg)](https://github.com/atlorium-api/cbr-bik-api-client/actions/workflows/examples.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-Swagger-brightgreen)](https://atlorium.com/cbrAPI)

Ready-to-run examples for the **Bank of Russia BIC directory API** in six languages: **Python, TypeScript (Node.js), Go, Java, C#, PHP.**
**Look up a Russian bank by BIC** (the 9-digit Bank Identification Code) or search by name, city or SWIFT. The response carries the **correspondent account**, SWIFT codes, **bank details**, region, address, participant status and restrictions. The source is the Bank of Russia's daily directory (ED807 / UFEBS).

Every example **runs out of the box — no signup, no key, no card.** A public demo key is baked in.

```bash
git clone https://github.com/atlorium-api/cbr-bik-api-client
cd cbr-bik-api-client/python && pip install -r requirements.txt && python main.py
```

> The demo key returns **realistic mock data**, not the real directory — which is why the bank name looks generated. That is the point: you can write and test the integration before paying. Swap in a live key and the same code returns real Bank of Russia data.

---

## What it is for

Validating payment details before sending a transfer, autofilling "beneficiary bank" and "correspondent account" fields from a BIC, form validation, reconciling counterparty registries, accounting and banking integrations. One HTTP request instead of digging through the central bank's directory by hand.

The examples do not just print JSON — they **apply** it. Each ships a `validatePaymentDetails()` function that turns a BIC and an account number into a verdict: is the participant active, has the licence been revoked, are there restrictions or seizures on the accounts, and — most usefully — **does the settlement account's check digit reconcile against the bank's BIC.**

The check digit is computed with the official Bank of Russia algorithm (weights 7-1-3, sum modulo 10). It catches a typo in a 20-digit account number **before** the payment leaves.

## Quick start

Try the API without cloning anything:

```bash
curl -H "Authorization: Bearer ak_sandbox_demo_mockdata_v1" \
     "https://atlorium.com/api/cbr/044525225"
```

| Language | Run | Requires |
|----------|-----|----------|
| [Python](python/) | `pip install -r requirements.txt && python main.py` | Python 3.10+ |
| [TypeScript / Node.js](node/) | `npm install && npm start` | Node.js 20+ |
| [Go](go/) | `go run .` | Go 1.22+ |
| [Java](java/) | `java Main.java` | JDK 17+ (no dependencies) |
| [C#](csharp/) | `dotnet run` | .NET 8+ |
| [PHP](php/) | `php main.php` | PHP 8.1+ |

Pass your own BIC and account number as arguments: `python main.py 044525225 40702810638000000000`

## Authentication

The key goes in the `Authorization` header:

```
Authorization: Bearer YOUR_KEY
```

| Key | Behaviour |
|-----|-----------|
| `ak_sandbox_demo_mockdata_v1` | **Demo key.** Public, shared by everyone. Returns mocks, charges nothing, needs no account. Responses are deterministic, so you can assert on them in tests. |
| Live key | Real Bank of Russia directory data. Get one at [atlorium.com](https://atlorium.com) |

Switching to a live key requires **no code changes** — every example reads an environment variable:

```bash
export ATLORIUM_API_KEY="ak_your_live_key"
```

Every sandbox response carries the header `X-Atlorium-Sandbox: true`, so mock data can never be mistaken for real data.

## Endpoints

Base URL: `https://atlorium.com`

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/cbr/{bik}` | Participant details by exact BIC |
| `GET` | `/api/cbr/search` | Search by name, city, SWIFT or BIC prefix |
| `GET` | `/api/cbr/stats` | Directory stats: size, effective date, readiness |

### `GET /api/cbr/{bik}`

| Parameter | In | Type | Description |
|-----------|----|------|-------------|
| `bik` | path | string | **BIC** — the Russian bank identification code, exactly 9 digits. For example, `044525225` |

Returns `404` if no participant carries that BIC.

### `GET /api/cbr/search`

| Parameter | In | Type | Description |
|-----------|----|------|-------------|
| `query` | query | string | Search string. **Digits only** are treated as a BIC prefix; anything else is matched as a substring against name, locality and SWIFT |
| `limit` | query | int | Max results: 20 by default, 100 maximum |

## Response fields

Participant card (`GET /api/cbr/{bik}`, and each element of `results` in search):

| Field | Type | Meaning |
|-------|------|---------|
| `bik` | string | Participant BIC, 9 digits |
| `name` / `englishName` | string | Name of the credit institution |
| `corrAccount` | string | **Correspondent account** held at the Bank of Russia |
| `parentBic` | string | BIC of the head office (for branches) |
| `regionCode` | string | Region code |
| `localityType` / `locality` | string | Settlement type and name |
| `address` | string | Address |
| `registrationNumber` | string | Registration number in the state register |
| `dateIn` | date | Date added to the directory |
| `swift` / `swiftCodes` | string / array | Primary **SWIFT/BIC** code and all codes |
| `status` | string | **The key field for validation.** Participant status: `PSAC` — active |
| `accounts` | array | Participant accounts: `{ account, accountType, controlKey, cbrBic, status, dateIn, restrictions }` |
| `restrictions` | array | **Risk flag.** Restrictions on the participant: `{ code, date }` |

`GET /api/cbr/stats` returns `{ isReady, totalEntries, withCorrAccount, withSwift, directoryDate, loadedAtUtc }` — `directoryDate` tells you the effective date of the loaded directory.

## Error handling

| Code | Cause | What to do |
|------|-------|------------|
| `400` | Malformed BIC | A BIC is exactly 9 digits |
| `401` | Key missing, expired or invalid | Check the `Authorization` header |
| `402` | Insufficient credit balance | Top up at [atlorium.com](https://atlorium.com) |
| `404` | No participant with that BIC | Format is valid, but the directory holds no such code |
| `429` | Rate limit exceeded | Retry with backoff |
| `503` | Directory temporarily unavailable | Retry later. **You are not charged for our failures** |

All six examples map these codes to human-readable causes — see the `AtloriumError` class.

## FAQ

**Where does the data come from?** From the official Bank of Russia directory of bank identification codes (ED807 / UFEBS format), published daily. The `directoryDate` field in `/stats` tells you which day's version is loaded.

**How does the account check-digit validation work?** By the official Bank of Russia algorithm. A three-digit prefix derived from the BIC is prepended to the 20-digit account number — for a correspondent account at the Bank of Russia (numbers starting `301`), that is `0` plus digits 5–6 of the BIC; for an ordinary account at the bank itself, the last 3 digits of the BIC. The resulting 23 digits are multiplied by the repeating weights 7-1-3, the products are summed, and the account is well-formed only if that sum is divisible by 10. The implementation lives in all six examples as `checkAccountControlKey()`.

**If the check digit reconciles, does the account exist?** No — and this matters. The check digit catches a **typo**: transposed or corrupted digits will almost certainly fail it. But any correctly constructed number passes, even if no such account was ever opened. The BIC directory cannot confirm that an account exists or that it belongs to your beneficiary — it simply does not hold that data.

**How is this better than the free XML on the central bank's website?** Same data, machine-readable, plus search. The Bank of Russia publishes a large daily XML archive that you must download, parse, refresh and index yourself. Here it is one HTTP request, ready JSON, search by name / city / SWIFT, and always the current version.

**Do I need to sign up to try it?** No. The demo key is public and works without an account — but it returns mocks, not real data. Note that mock banks have randomly generated account numbers, so their correspondent accounts will *not* pass the check-digit test; run `checkAccountControlKey()` against real numbers.

## Other Atlorium APIs

Validating bank details is rarely the only task. The same account and key also give you:

- [SWIFT/BIC](https://github.com/atlorium-api/swift-bic-api-client) — ISO-9362 code parsing and pre-transfer checks
- [EGRUL/EGRIP](https://github.com/atlorium-api/egrul-api-client) — Russian company check by INN/OGRN: status, address, capital
- [Card BIN lookup](https://github.com/atlorium-api/bin-lookup-api-client) — issuer, country and card type for checkout antifraud
- [Image OCR](https://github.com/atlorium-api/image-ocr-api-client) — extract text from an image or Base64
- [AML crypto screening](https://github.com/atlorium-api/aml-crypto-screening-api-client) — risk score, sanctions, PEP
- [Russian test data generator](https://github.com/atlorium-api/test-data-generator-api-client) — fictional INN, SNILS, OGRN and accounts with valid checksums

Full catalogue: [atlorium.com](https://atlorium.com)

## Links

- **API reference (Swagger):** [atlorium.com/cbrAPI](https://atlorium.com/cbrAPI)
- **OpenAPI spec:** [cbr_en-US.json](https://atlorium.com/openapi/cbr_en-US.json)
- **Support:** support@atlorium.com

## License

[MIT](LICENSE)
