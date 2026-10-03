/*
 * Клиент API справочника БИК ЦБ РФ Atlorium — банк по БИК, корсчёт, SWIFT, реквизиты.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе).
 * Начиная с Java 11 файл запускается напрямую, без компиляции и без зависимостей:
 *
 *     java Main.java
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {

    /**
     * Публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ (не реальными
     * данными) — чтобы можно было встроить и протестировать интеграцию до оплаты.
     * Ответы детерминированы: один и тот же запрос всегда даёт один и тот же результат,
     * поэтому на них можно писать стабильные тесты.
     */
    static final String SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1";

    static final String API_KEY = envOr("ATLORIUM_API_KEY", SANDBOX_KEY);
    static final String BASE_URL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com");

    /** Коды справочника ЦБ РФ (формат ED807 / УФЭБС). */
    static final String PARTICIPANT_ACTIVE = "PSAC"; // участник действующий
    static final String ACCOUNT_ACTIVE = "ACAC";     // счёт действующий

    /**
     * Расшифровки самых частых кодов ограничений. Полный перечень публикует ЦБ РФ
     * в альбоме УФЭБС; неизвестный код мы показываем как есть — любое ограничение
     * в справочнике уже само по себе повод не отправлять платёж.
     */
    static final Map<String, String> RESTRICTION_REASONS = Map.of(
            "LWDL", "отзыв (аннулирование) лицензии",
            "MRTR", "мораторий на удовлетворение требований кредиторов");

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
    static class AtloriumException extends RuntimeException {
        private static final Map<Integer, String> REASONS = Map.of(
                400, "Неверный формат БИК (ожидается ровно 9 цифр)",
                401, "API-ключ отсутствует, просрочен или недействителен",
                402, "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
                404, "Участник с таким БИК не найден в справочнике ЦБ РФ",
                429, "Превышен лимит запросов — повторите позже",
                503, "Справочник БИК временно недоступен (за сбой на своей стороне мы не списываем деньги)");

        final int status;

        AtloriumException(int status, String body) {
            super("HTTP " + status + ": "
                    + REASONS.getOrDefault(status, "Неизвестная ошибка")
                    + ". Ответ сервера: " + body.substring(0, Math.min(200, body.length())));
            this.status = status;
        }
    }

    static String get(String path, String query) throws IOException, InterruptedException {
        String url = BASE_URL + path + (query.isEmpty() ? "" : "?" + query);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() != 200) {
            throw new AtloriumException(response.statusCode(), body);
        }
        return body;
    }

    /** Реквизиты участника справочника по точному БИК (ровно 9 цифр). */
    static String getBank(String bik) throws IOException, InterruptedException {
        return get("/api/cbr/" + URLEncoder.encode(bik, StandardCharsets.UTF_8), "");
    }

    /**
     * Поиск участников по наименованию, городу, SWIFT или началу БИК.
     *
     * Если query состоит только из цифр — трактуется как БИК (поиск по началу кода),
     * иначе ищется как подстрока в наименовании, населённом пункте и SWIFT.
     */
    static String searchBanks(String query, int limit) throws IOException, InterruptedException {
        String params = "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=" + limit;
        return get("/api/cbr/search", params);
    }

    /** Статистика справочника: размер, дата актуальности, готовность. */
    static String getStats() throws IOException, InterruptedException {
        return get("/api/cbr/stats", "");
    }

    // ── Разбор JSON ──────────────────────────────────────────────────────────
    // Пример намеренно оставлен без внешних зависимостей, чтобы запускаться одной
    // командой `java Main.java`. Поэтому здесь лежит минималистичный разбор JSON:
    // объект раскладывается на поля ВЕРХНЕГО уровня, вложенные объекты и массивы
    // возвращаются одной строкой и при необходимости разбираются повторно.
    //
    // Разбирать такой ответ регулярками нельзя: поле "status" встречается и у банка,
    // и внутри каждого счёта, и наивный поиск по /"status":"(\w+)"/ вернул бы статус
    // счёта вместо статуса участника. Отсюда и учёт вложенности ниже.
    //
    // В рабочем проекте берите Jackson или Gson и маппьте ответ в полноценную
    // запись — этот разбор существует только ради отсутствия pom.xml.

    /** Поля верхнего уровня объекта: имя → сырой текст значения. */
    static Map<String, String> fields(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = json.indexOf('{');
        if (i < 0) {
            return result;
        }
        for (i++; i < json.length(); ) {
            while (i < json.length()
                    && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) != '"') {
                break;
            }
            int keyEnd = endOfString(json, i);
            String key = json.substring(i + 1, keyEnd);

            int colon = json.indexOf(':', keyEnd);
            if (colon < 0) {
                break;
            }
            i = colon + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            int valueEnd = endOfValue(json, i);
            result.put(key, json.substring(i, valueEnd));
            i = valueEnd;
        }
        return result;
    }

    /** Индекс закрывающей кавычки строки, открытой в позиции {@code start}. */
    static int endOfString(String json, int start) {
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char symbol = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (symbol == '\\') {
                escaped = true;
            } else if (symbol == '"') {
                return i;
            }
        }
        return json.length() - 1;
    }

    /** Индекс первого символа ПОСЛЕ значения, начинающегося в позиции {@code start}. */
    static int endOfValue(String json, int start) {
        char first = json.charAt(start);
        if (first == '"') {
            return endOfString(json, start) + 1;
        }
        if (first == '{' || first == '[') {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < json.length(); i++) {
                char symbol = json.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (symbol == '\\') {
                        escaped = true;
                    } else if (symbol == '"') {
                        inString = false;
                    }
                } else if (symbol == '"') {
                    inString = true;
                } else if (symbol == '{' || symbol == '[') {
                    depth++;
                } else if (symbol == '}' || symbol == ']') {
                    if (--depth == 0) {
                        return i + 1;
                    }
                }
            }
            return json.length();
        }
        int i = start;
        while (i < json.length() && ",}]".indexOf(json.charAt(i)) < 0) {
            i++;
        }
        return i;
    }

    /** Строковое поле верхнего уровня. {@code null}, если поля нет или в нём JSON null. */
    static String str(String json, String field) {
        String raw = fields(json).get(field);
        if (raw == null || !raw.startsWith("\"")) {
            return null;
        }
        return raw.substring(1, raw.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** Целочисленное поле верхнего уровня. */
    static Long number(String json, String field) {
        String raw = fields(json).get(field);
        return (raw != null && raw.matches("-?\\d+")) ? Long.valueOf(raw) : null;
    }

    /** Элементы массива объектов из поля верхнего уровня. */
    static List<String> objects(String json, String field) {
        List<String> items = new ArrayList<>();
        String raw = fields(json).get(field);
        if (raw == null || !raw.startsWith("[")) {
            return items;
        }

        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char symbol = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (symbol == '\\') {
                    escaped = true;
                } else if (symbol == '"') {
                    inString = false;
                }
            } else if (symbol == '"') {
                inString = true;
            } else if (symbol == '{') {
                if (depth++ == 0) {
                    start = i;
                }
            } else if (symbol == '}') {
                if (--depth == 0 && start >= 0) {
                    items.add(raw.substring(start, i + 1));
                }
            }
        }
        return items;
    }

    // ── Контрольный ключ номера счёта (алгоритм ЦБ РФ) ────────────────────────
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

    static final int[] WEIGHTS = {7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1};

    /** Проверяет контрольный ключ 20-значного номера счёта по БИК банка. */
    static boolean checkAccountControlKey(String bik, String account) {
        if (bik == null || !bik.matches("\\d{9}") || account == null || !account.matches("\\d{20}")) {
            return false;
        }

        // Корсчёт банка в Банке России против счёта, открытого в самом банке.
        String prefix = account.startsWith("301")
                ? "0" + bik.substring(4, 6)
                : bik.substring(6, 9);

        String digits = prefix + account;
        int checksum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            checksum += WEIGHTS[i] * (digits.charAt(i) - '0');
        }
        return checksum % 10 == 0;
    }

    // ── Применение данных: проверка платёжных реквизитов ──────────────────────
    // Карточка банка сама по себе — просто JSON. Ценность появляется, когда из неё
    // делают вывод. Ниже — набор проверок, которые делают перед отправкой платежа:
    // существует ли банк, действует ли он, не отозвана ли лицензия, нет ли
    // ограничений и не опечатался ли пользователь в номере счёта.

    record Verdict(List<String> risks, List<String> notes) {
        boolean isRisky() {
            return !risks.isEmpty();
        }
    }

    static String describeRestriction(String code) {
        String reason = RESTRICTION_REASONS.get(code);
        return reason == null ? code : code + " — " + reason;
    }

    /** Выносит вердикт по платёжным реквизитам: банк + (опционально) номер счёта. */
    static Verdict validatePaymentDetails(String bank, String account) {
        List<String> risks = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        String bik = str(bank, "bik");

        // Главный стоп-фактор: участник исключён из справочника ЦБ РФ.
        String status = str(bank, "status");
        if (!PARTICIPANT_ACTIVE.equals(status)) {
            risks.add("Участник недействующий: статус " + (status == null ? "не указан" : status));
        }

        // Ограничения на уровне участника: отзыв лицензии, мораторий и т.п.
        for (String restriction : objects(bank, "restrictions")) {
            String date = str(restriction, "date");
            risks.add("Ограничение участника: " + describeRestriction(str(restriction, "code"))
                    + (date == null ? "" : " (с " + date + ")"));
        }

        // Без корсчёта банк не может принимать платежи через платёжную систему ЦБ.
        String corrAccount = str(bank, "corrAccount");
        if (corrAccount == null || corrAccount.isBlank()) {
            risks.add("У участника нет корреспондентского счёта");
        }

        // Ограничения на уровне счетов участника: арест, приостановление операций.
        for (String bankAccount : objects(bank, "accounts")) {
            String number = str(bankAccount, "account");
            String accountStatus = str(bankAccount, "status");
            if (!ACCOUNT_ACTIVE.equals(accountStatus)) {
                risks.add("Счёт " + number + " недействующий: статус "
                        + (accountStatus == null ? "не указан" : accountStatus));
            }
            for (String restriction : objects(bankAccount, "restrictions")) {
                risks.add("Ограничение по счёту " + number + ": "
                        + describeRestriction(str(restriction, "code")));
            }
        }

        // Контрольный ключ номера счёта получателя — ловит опечатку до платежа.
        if (account == null || account.isBlank()) {
            notes.add("Номер счёта не передан — проверка контрольного ключа пропущена");
        } else if (!account.matches("\\d{20}")) {
            risks.add("Номер счёта должен состоять ровно из 20 цифр");
        } else if (!checkAccountControlKey(bik, account)) {
            risks.add("Контрольный ключ счёта " + account + " не сходится с БИК " + bik
                    + " — в номере опечатка");
        } else {
            notes.add("Контрольный ключ счёта " + account + " верен");
        }

        String swift = str(bank, "swift");
        if (swift != null) {
            notes.add("SWIFT: " + swift);
        }

        return new Verdict(risks, notes);
    }

    static String orDash(String value) {
        return (value == null || value.isBlank()) ? "—" : value;
    }

    public static void main(String[] args) throws Exception {
        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("Демо-ключ: ответы сгенерированы (моки), не реальные данные.\n");
        }

        String bik = args.length > 0 ? args[0] : "044525225";
        // Номер счёта получателя — второй аргумент. По умолчанию корректный
        // (контрольный ключ сходится) счёт для БИК 044525225.
        String account = args.length > 1 ? args[1] : "40702810638000000000";

        String bank;
        String stats;
        try {
            bank = getBank(bik);
            stats = getStats();
        } catch (AtloriumException error) {
            if (error.status == 404) {
                System.out.println("БИК " + bik + ": в справочнике ЦБ РФ не найден.");
                return;
            }
            System.err.println("Ошибка: " + error.getMessage());
            System.exit(1);
            return;
        }

        System.out.println(str(bank, "name"));
        System.out.println("  БИК " + str(bank, "bik")
                + " · рег. № " + orDash(str(bank, "registrationNumber")));
        System.out.println("  Корсчёт: " + orDash(str(bank, "corrAccount")));

        String swift = str(bank, "swift");
        if (swift != null) {
            System.out.println("  SWIFT: " + swift);
        }

        StringBuilder location = new StringBuilder();
        for (String part : List.of("postalIndex", "localityType", "locality")) {
            String value = str(bank, part);
            if (value != null && !value.isBlank()) {
                location.append(location.isEmpty() ? "" : " ").append(value);
            }
        }
        System.out.println("  Адрес: " + location + ", " + orDash(str(bank, "address")));
        System.out.println("  Регион: " + orDash(str(bank, "regionCode"))
                + " · Статус: " + str(bank, "status"));
        System.out.println("  В справочнике с " + str(bank, "dateIn"));

        Verdict verdict = validatePaymentDetails(bank, account);
        System.out.println("\nПлатёж на счёт " + account + " (БИК " + bik + "):");

        if (verdict.isRisky()) {
            System.out.println("РИСКИ:");
            verdict.risks().forEach(risk -> System.out.println("  [!] " + risk));
        } else {
            System.out.println("Стоп-факторов не обнаружено — реквизиты можно использовать.");
        }
        verdict.notes().forEach(note -> System.out.println("  [i] " + note));

        System.out.println("\nСправочник ЦБ РФ актуален на " + str(stats, "directoryDate")
                + ", участников: " + number(stats, "totalEntries") + ".");
    }
}
