// Клиент API справочника БИК ЦБ РФ Atlorium — банк по БИК, корсчёт, SWIFT, реквизиты.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//
//	go run .
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// SandboxKey — публичный демо-ключ. С ним API отвечает правдоподобными МОКАМИ
// (не реальными данными), чтобы можно было встроить интеграцию до оплаты.
// Ответы детерминированы — на них можно писать стабильные тесты.
const SandboxKey = "ak_sandbox_demo_mockdata_v1"

// Коды справочника ЦБ РФ (формат ED807 / УФЭБС).
const (
	ParticipantActive = "PSAC" // участник действующий
	AccountActive     = "ACAC" // счёт действующий
)

// RestrictionReasons — расшифровки самых частых кодов ограничений. Полный
// перечень публикует ЦБ РФ в альбоме УФЭБС; неизвестный код мы показываем как
// есть — любое ограничение в справочнике уже само по себе повод не отправлять
// платёж.
var RestrictionReasons = map[string]string{
	"LWDL": "отзыв (аннулирование) лицензии",
	"MRTR": "мораторий на удовлетворение требований кредиторов",
}

var (
	apiKey  = envOr("ATLORIUM_API_KEY", SandboxKey)
	baseURL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com")
	client  = &http.Client{Timeout: 30 * time.Second}
)

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// Restriction — ограничение на участнике или на конкретном счёте.
type Restriction struct {
	Code         string `json:"code"`
	Date         string `json:"date"`
	SuccessorBic string `json:"successorBic"`
}

// Account — счёт участника справочника.
type Account struct {
	Account      string        `json:"account"`
	AccountType  string        `json:"accountType"`
	ControlKey   string        `json:"controlKey"`
	CbrBic       string        `json:"cbrBic"`
	Status       string        `json:"status"`
	DateIn       string        `json:"dateIn"`
	Restrictions []Restriction `json:"restrictions"`
}

// BankInfo — карточка участника справочника БИК ЦБ РФ.
type BankInfo struct {
	Bik                string        `json:"bik"`
	Name               string        `json:"name"`
	EnglishName        string        `json:"englishName"`
	CorrAccount        string        `json:"corrAccount"`
	CountryCode        string        `json:"countryCode"`
	ParentBic          string        `json:"parentBic"`
	RegionCode         string        `json:"regionCode"`
	PostalIndex        string        `json:"postalIndex"`
	LocalityType       string        `json:"localityType"`
	Locality           string        `json:"locality"`
	Address            string        `json:"address"`
	RegistrationNumber string        `json:"registrationNumber"`
	UID                string        `json:"uid"`
	DateIn             string        `json:"dateIn"`
	Swift              string        `json:"swift"`
	SwiftCodes         []string      `json:"swiftCodes"`
	Accounts           []Account     `json:"accounts"`
	Restrictions       []Restriction `json:"restrictions"`
	ParticipantType    string        `json:"participantType"`
	ServiceCode        string        `json:"serviceCode"`
	ExchangeType       string        `json:"exchangeType"`
	Status             string        `json:"status"`
}

// SearchResponse — результат поиска по справочнику.
type SearchResponse struct {
	Query     string     `json:"query"`
	Results   []BankInfo `json:"results"`
	Count     int        `json:"count"`
	ElapsedMs int64      `json:"elapsedMs"`
}

// Stats — статистика загруженного справочника.
type Stats struct {
	IsReady         bool   `json:"isReady"`
	TotalEntries    int    `json:"totalEntries"`
	WithCorrAccount int    `json:"withCorrAccount"`
	WithSwift       int    `json:"withSwift"`
	DirectoryDate   string `json:"directoryDate"`
	LoadedAtUtc     string `json:"loadedAtUtc"`
}

// APIError раскладывает HTTP-код в человекочитаемую причину.
type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string {
	reasons := map[int]string{
		400: "неверный формат БИК (ожидается ровно 9 цифр)",
		401: "API-ключ отсутствует, просрочен или недействителен",
		402: "недостаточно кредитов на балансе — пополните на https://atlorium.com",
		404: "участник с таким БИК не найден в справочнике ЦБ РФ",
		429: "превышен лимит запросов — повторите позже",
		503: "справочник БИК временно недоступен (за сбой на своей стороне мы не списываем деньги)",
	}
	reason, ok := reasons[e.Status]
	if !ok {
		reason = "неизвестная ошибка"
	}
	return fmt.Sprintf("HTTP %d: %s. Ответ сервера: %s", e.Status, reason, e.Body)
}

func get(path string, query url.Values) ([]byte, error) {
	endpoint := baseURL + path
	if len(query) > 0 {
		endpoint += "?" + query.Encode()
	}

	request, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+apiKey)
	request.Header.Set("Accept", "application/json")

	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()

	body, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, err
	}
	if response.StatusCode != http.StatusOK {
		return nil, &APIError{Status: response.StatusCode, Body: string(body)}
	}
	return body, nil
}

// GetBank возвращает реквизиты участника справочника по точному БИК (9 цифр).
func GetBank(bik string) (*BankInfo, error) {
	body, err := get("/api/cbr/"+url.PathEscape(bik), nil)
	if err != nil {
		return nil, err
	}
	var bank BankInfo
	if err := json.Unmarshal(body, &bank); err != nil {
		return nil, err
	}
	return &bank, nil
}

// SearchBanks ищет участников по наименованию, городу, SWIFT или началу БИК.
//
// Если query состоит только из цифр — трактуется как БИК (поиск по началу кода),
// иначе ищется как подстрока в наименовании, населённом пункте и SWIFT.
func SearchBanks(query string, limit int) (*SearchResponse, error) {
	body, err := get("/api/cbr/search", url.Values{
		"query": {query},
		"limit": {strconv.Itoa(limit)},
	})
	if err != nil {
		return nil, err
	}
	var result SearchResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// GetStats возвращает статистику справочника: размер, дату актуальности, готовность.
func GetStats() (*Stats, error) {
	body, err := get("/api/cbr/stats", nil)
	if err != nil {
		return nil, err
	}
	var stats Stats
	if err := json.Unmarshal(body, &stats); err != nil {
		return nil, err
	}
	return &stats, nil
}

// ── Контрольный ключ номера счёта (алгоритм ЦБ РФ) ────────────────────────────
// Официальный порядок расчёта контрольного ключа в номере лицевого счёта
// (Положение Банка России о плане счетов, приложение «Порядок расчёта
// контрольного ключа»). Алгоритм опубликован и однозначен:
//
//  1. К 20-значному номеру счёта слева приписывается «условный номер»:
//     • для корреспондентского счёта банка в Банке России (счёт начинается
//     на 301) — «0» + 5-я и 6-я цифры БИК;
//     • для любого другого счёта, открытого в самом банке, — последние
//     3 цифры БИК.
//  2. Получается 23 цифры. Каждая умножается на весовой коэффициент из
//     последовательности 7,1,3,7,1,3,… (она же и есть weights ниже).
//  3. Сумма произведений берётся по модулю 10. Счёт корректен, если остаток
//     равен нулю.
//
// Это проверка от опечатки, а не подтверждение существования счёта: ключ
// сходится у любого правильно составленного номера, в том числе у никому
// не принадлежащего.

var weights = [23]int{7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1}

var (
	digits9  = regexp.MustCompile(`^\d{9}$`)
	digits20 = regexp.MustCompile(`^\d{20}$`)
)

// CheckAccountControlKey проверяет контрольный ключ 20-значного счёта по БИК банка.
func CheckAccountControlKey(bik, account string) bool {
	if !digits9.MatchString(bik) || !digits20.MatchString(account) {
		return false
	}

	// Корсчёт банка в Банке России против счёта, открытого в самом банке.
	prefix := bik[6:9]
	if strings.HasPrefix(account, "301") {
		prefix = "0" + bik[4:6]
	}

	digits := prefix + account
	checksum := 0
	for index, weight := range weights {
		checksum += weight * int(digits[index]-'0')
	}
	return checksum%10 == 0
}

// ── Применение данных: проверка платёжных реквизитов ──────────────────────────
// Карточка банка сама по себе — просто JSON. Ценность появляется, когда из неё
// делают вывод. Ниже — набор проверок, которые делают перед отправкой платежа:
// существует ли банк, действует ли он, не отозвана ли лицензия, нет ли
// ограничений и не опечатался ли пользователь в номере счёта.

// Verdict — результат проверки платёжных реквизитов.
type Verdict struct {
	Risks []string
	Notes []string
}

// IsRisky сообщает, найдены ли стоп-факторы.
func (v Verdict) IsRisky() bool { return len(v.Risks) > 0 }

func describeRestriction(code string) string {
	if reason, ok := RestrictionReasons[code]; ok {
		return code + " — " + reason
	}
	return code
}

// ValidatePaymentDetails выносит вердикт по платёжным реквизитам:
// банк + (опционально) номер счёта получателя.
func ValidatePaymentDetails(bank *BankInfo, account string) Verdict {
	var verdict Verdict

	// Главный стоп-фактор: участник исключён из справочника ЦБ РФ.
	if bank.Status != ParticipantActive {
		status := bank.Status
		if status == "" {
			status = "не указан"
		}
		verdict.Risks = append(verdict.Risks, "Участник недействующий: статус "+status)
	}

	// Ограничения на уровне участника: отзыв лицензии, мораторий и т.п.
	for _, restriction := range bank.Restrictions {
		line := "Ограничение участника: " + describeRestriction(restriction.Code)
		if restriction.Date != "" {
			line += " (с " + restriction.Date + ")"
		}
		verdict.Risks = append(verdict.Risks, line)
	}

	// Без корсчёта банк не может принимать платежи через платёжную систему ЦБ.
	if bank.CorrAccount == "" {
		verdict.Risks = append(verdict.Risks, "У участника нет корреспондентского счёта")
	}

	// Ограничения на уровне счетов участника: арест, приостановление операций.
	for _, bankAccount := range bank.Accounts {
		if bankAccount.Status != AccountActive {
			status := bankAccount.Status
			if status == "" {
				status = "не указан"
			}
			verdict.Risks = append(verdict.Risks,
				fmt.Sprintf("Счёт %s недействующий: статус %s", bankAccount.Account, status))
		}
		for _, restriction := range bankAccount.Restrictions {
			verdict.Risks = append(verdict.Risks,
				fmt.Sprintf("Ограничение по счёту %s: %s",
					bankAccount.Account, describeRestriction(restriction.Code)))
		}
	}

	// Контрольный ключ номера счёта получателя — ловит опечатку до платежа.
	switch {
	case account == "":
		verdict.Notes = append(verdict.Notes,
			"Номер счёта не передан — проверка контрольного ключа пропущена")
	case !digits20.MatchString(account):
		verdict.Risks = append(verdict.Risks, "Номер счёта должен состоять ровно из 20 цифр")
	case !CheckAccountControlKey(bank.Bik, account):
		verdict.Risks = append(verdict.Risks,
			fmt.Sprintf("Контрольный ключ счёта %s не сходится с БИК %s — в номере опечатка",
				account, bank.Bik))
	default:
		verdict.Notes = append(verdict.Notes,
			fmt.Sprintf("Контрольный ключ счёта %s верен", account))
	}

	if bank.Swift != "" {
		verdict.Notes = append(verdict.Notes, "SWIFT: "+bank.Swift)
	}

	return verdict
}

func orDash(value string) string {
	if value == "" {
		return "—"
	}
	return value
}

func main() {
	if apiKey == SandboxKey {
		fmt.Println("Демо-ключ: ответы сгенерированы (моки), не реальные данные.")
		fmt.Println()
	}

	bik := "044525225"
	if len(os.Args) > 1 {
		bik = os.Args[1]
	}
	// Номер счёта получателя — второй аргумент. По умолчанию корректный
	// (контрольный ключ сходится) счёт для БИК 044525225.
	account := "40702810638000000000"
	if len(os.Args) > 2 {
		account = os.Args[2]
	}

	bank, err := GetBank(bik)
	if err != nil {
		var apiError *APIError
		if errors.As(err, &apiError) && apiError.Status == http.StatusNotFound {
			fmt.Printf("БИК %s: в справочнике ЦБ РФ не найден.\n", bik)
			return
		}
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		os.Exit(1)
	}

	stats, err := GetStats()
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		os.Exit(1)
	}

	fmt.Println(bank.Name)
	fmt.Printf("  БИК %s · рег. № %s\n", bank.Bik, orDash(bank.RegistrationNumber))
	fmt.Printf("  Корсчёт: %s\n", orDash(bank.CorrAccount))
	if bank.Swift != "" {
		fmt.Printf("  SWIFT: %s\n", bank.Swift)
	}

	location := strings.Join(nonEmpty(bank.PostalIndex, bank.LocalityType, bank.Locality), " ")
	fmt.Printf("  Адрес: %s, %s\n", location, orDash(bank.Address))
	fmt.Printf("  Регион: %s · Статус: %s\n", orDash(bank.RegionCode), bank.Status)
	fmt.Printf("  В справочнике с %s\n", bank.DateIn)

	verdict := ValidatePaymentDetails(bank, account)
	fmt.Printf("\nПлатёж на счёт %s (БИК %s):\n", account, bik)

	if verdict.IsRisky() {
		fmt.Println("РИСКИ:")
		for _, risk := range verdict.Risks {
			fmt.Println("  [!]", risk)
		}
	} else {
		fmt.Println("Стоп-факторов не обнаружено — реквизиты можно использовать.")
	}
	for _, note := range verdict.Notes {
		fmt.Println("  [i]", note)
	}

	fmt.Printf("\nСправочник ЦБ РФ актуален на %s, участников: %d.\n",
		stats.DirectoryDate, stats.TotalEntries)
}

func nonEmpty(values ...string) []string {
	result := make([]string, 0, len(values))
	for _, value := range values {
		if value != "" {
			result = append(result, value)
		}
	}
	return result
}
