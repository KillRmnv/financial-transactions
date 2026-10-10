# Тест-кейсы

Всё поведение описано в Gherkin, в `src/test/resources/features`. Этот файл — указатель: **что проверяется, какой техникой проектирования и в каком сценарии**. Чтобы найти тест-кейс, ищите объект или ситуацию в оглавлении ниже, а не по всему репозиторию.

Сценариев в feature-файлах — **133**. Плюс JUnit вне Gherkin: 10 правил архитектуры (раздел 9) и проверка контекста (раздел 10).

## Легенда техник

| Метка | Техника | Что она ловит |
|---|---|---|
| **ТЗ** | Сценарий из технического задания | Эталонные числа из таблиц задания. Не техника проектирования, а сверка с требованием |
| **BVA** | Анализ граничных значений | Ошибки вида «меньше или равно» вместо «строго меньше», off-by-one на длине и масштабе. Ловит то, что **не видит** EP: обе стороны границы представлены и обе работают |
| **EP** | Классы эквивалентности | Неверное правило целиком: один представитель на класс, потому что сломанное правило валит весь класс сразу |
| **Регресс** | Зафиксированный баг | Написан по найденной ошибке; падение означает возвращение бага |
| **Smoke** | Работоспособность | Контекст поднимается |

Колонка **Сценарий** содержит точное имя из `.feature`, чтобы по нему можно было искать в обе стороны.

---

## 1. Флаг `limit_exceeded`

Объект: правило «превышена ли сумма». Это то, что сервис оценивают, и то, где ошибка дороже всего — неверный флаг клиент видит как отказ в оплате.

### 1.1 Правило как чистая функция — 15 сценариев

`rule/limit_flag_rule.feature`. Без Spring и без базы, выполняется за ~200 мс.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Таблица истины, 9 строк: остаток ровно 0, `999.99 + 0.01`, копейка сверх, нулевая сумма | **ТЗ** + **BVA** | `The truth table, row by row` |
| «1 случай» — лимит поднят внутри месяца, месяц не сбрасывается. Остаток 900 = 2000 − 1100 | **ТЗ** | `Case 1 — the limit is raised mid-month without resetting the month` |
| «2 случай» — лимит ниже уже потраченного. В колонке «Остаток» задания арифметическая ошибка (−200 вместо −500), сверяемся с правилом | **ТЗ** | `Case 2 — a limit lower than what has already been spent` |
| Действующий лимит — последний установленный не позже транзакции, 4 строки | **BVA** | `The limit in effect is the latest one installed no later than the transaction` |

Строка `none` в последней таблице — **EP**: клиент не устанавливал лимита, ответ null, а не выдуманный fallback.

### 1.2 Тот же сценарий против реального PostgreSQL — 7 сценариев

`service/limit_flag_scenario.feature`. Здесь важно другое: правильная арифметика может всё равно не сохраниться.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| «1 случай» целиком: флаг только у 3 и 13 января | **ТЗ** | `Case 1 — only the transactions of 3 and 13 January are flagged` |
| Остаток ровно ноль не превышен | **BVA** + **Регресс** | `A remaining balance of exactly zero is not exceeded` |
| Транзакция помнит тот лимит, по которому считался её флаг | **Регресс** | `Each transaction remembers the limit its flag was computed against` |
| Новый месяц обнуляет итог (31.01 → 01.02) | **BVA** | `A new month starts a fresh running total` |
| Транзакция задним числом меряется против дефолта | **Регресс** | `Backdated transactions are measured against the standing fallback` |
| Дефолтный лимит датирован эпохой и не показывает дату установки | **Регресс** | `The automatic fallback is dated at the epoch and reports no installation date` |
| 12 параллельных posts по 100 USD против лимита 1000 | **Регресс** | `Concurrent posts for one client serialise on the limit row` |

Последний — не формальность: без блокировки строки сценарий видит 1 флаг вместо 2.

---

## 2. Месячная граница

Объект: к какому месяцу относится транзакция. Транзакции приходят с разными offset'ами, и «первая транзакция марта» обязана означать одно и то же.

`rule/month_boundary.feature`, 12 сценариев, чистая функция.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Последняя и первая миллисекунды месяца, 5 строк | **BVA** | `The last and first instants of a month` |
| Offset отправителя не решает: 31.01 23:00 −06:00 — это уже февраль по UTC, и наоборот, 4 строки | **EP** + **BVA** | `The offset the sender used does not decide the month` |
| Граница года ведёт себя как граница месяца, 2 строки | **BVA** | `A year boundary behaves like a month boundary` |
| 29 февраля | **BVA** | `A leap day belongs to February and the month after it does not` |

Строки про offset — единственный класс, где ошибка проявилась бы при сумме у лимита, то есть редко.

---

## 3. Конверсия в валюту учёта

Объект: сумма в валюте учёта и цепочка получения курса. Две независимые вещи — арифметика округления и порядок поиска курса.

### 3.1 Сервис: границы округления и курс — 18 сценариев

`service/conversion_boundaries.feature`.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| HALF_UP на полцента, 7 строк: `0.025→0.03`, `0.020` без округления, курс `1.005` | **BVA** | `Rounding is HALF_UP, so an exact half goes up` |
| Курс ≤ 0 не конвертирует ничего, 2 строки. Ноль сделал бы транзакцию бесплатной, отрицательный — перевёрнутой | **EP** | `A rate that is not positive cannot convert anything` |
| Курс не округляется до умножения: `999.99 × 1.00000001` | **Регресс** | `The rate is not rounded before the amount is multiplied` |
| Минимальный положительный курс `0.00000001` — меньше уже невозможно при scale 8 | **BVA** | `The smallest positive rate is still a rate` |
| Курс берётся на первое число месяца, а не на дату транзакции | **ТЗ** | `The rate of the month's first day is used as it is` |
| Курс за 3-е число не находится транзакцией от 5-го | **BVA** | `A later day of the same month is not the rate of this month` |
| Месяц без своего курса берёт последний более ранний | **EP** | `A month with no rate of its own falls back to the most recent earlier one` |
| Валюты без курса нигде: транзакция сохраняется без флага | **EP** | `A currency with no rate anywhere is stored rather than dropped` |
| Провайдер недоступен: теряется курс, не транзакция (п. 133 задания) | **EP** | `A provider that is down does not cost the transaction` |
| Найденный курс используется без обращения к провайдеру | **EP** | `A stored rate is found without asking the provider at all` |
| Валюта учёта курса не требует вовсе, ставка 1 | **EP** | `The ledger currency needs no rate at all` |

HALF_UP и HALF_DOWN расходятся **только** на ровно .5 — значении, которое «обычная» сумма не даёт никогда.

### 3.2 Адаптер провайдера через WireMock — 3 сценария

`api/client_api.feature`. Единственные сценарии, которые прогоняют **настоящий** `TwelveDataRatesProvider`; остальные подменяют адаптер целиком и ничего о нём не говорят.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Курс провайдера конвертирует транзакцию по сети | **ТЗ** | `A rate published by the provider converts the transaction over the wire` |
| День без своего `close` берёт `previous_close` (п. 119 задания) | **ТЗ** | `A day with no close of its own falls back to the provider's previous close` |
| Недоступный провайдер стоит курса, а не транзакции | **EP** | `An unreachable provider costs the rate, not the transaction` |

---

## 4. Валютный код

Объект: справочник валют. Код — проверяемая ссылка, а не строка, которую пишет кто угодно.

`service/currency_contract.feature` — 8 сценариев, плюс один из `api/client_api.feature`.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Схема отвергает код, не состоящий из трёх заглавных, 3 строки. `kzt` рядом с `KZT` — это вторая валюта | **BVA** + **EP** | `The schema refuses a code that is not three uppercase letters` |
| Справочник содержит KZT, RUB, USD и больше 150 кодов | **ТЗ** | `The seeded lookup holds the currencies the specification names` |
| Строчный код в запросе резолвится в seeded-валюту, а не сохраняется как есть | **Регресс** | `A lowercase code in a request resolves to the seeded currency` |
| Валюта вне справочника отвергается | **EP** | `A currency outside the lookup is refused` |
| Курс нельзя сохранить для валюты вне справочника — внешний ключ отвергает INSERT в обход приложения | **EP** | `A rate cannot be stored for a currency that is not in the lookup` |
| Валюта учёта и её масштаб приходят из конфигурации, а не константой | **ТЗ** | `The ledger currency and its scale come from configuration, not from a constant` |
| Неподдерживаемая валюта отвергается, а принятый набор публикуется — там не должно быть `"kzt"` | **EP** | `An unsupported currency is refused, and the accepted set is published` (api) |

---

## 5. Конфигурация валюты учёта

Объект: `LedgerPolicy`. Ошибочное значение здесь не бросит исключение позже — оно молча оставит валюту учёта пустой.

`rule/ledger_policy.feature`, 15 сценариев, без Spring.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Код валюты: `US`, `USDD`, `usd`, `123`, `USD`, `EUR` — 6 строк. Границы длины и классы внутри длины 3 | **BVA** + **EP** | `The ledger currency is three uppercase letters` |
| Пустой код отвергается | **BVA** | `A currency that is blank is refused` |
| Масштаб: −1, −100, 0, 2, 4 — 5 строк. Отрицательный и ноль — разные случаи | **BVA** | `The amount scale cannot be negative` |
| Округление HALF_UP: `0.025→0.03`, `−0.025→−0.03`, `2.675→2.68` | **BVA** | `Rounding is half-up at the configured scale` |
| Другой масштаб уважается, а не предполагается: scale 0 превращает 0.4 в 0 | **EP** | `A different scale is honoured rather than assumed` |
| Валюта учёта определяется конфигурацией: EUR — учётная, USD — нет | **EP** | `The ledger currency needs no rate of its own` |

Пустой код вынесен в отдельный шаг: пустую строку нельзя записать ячейкой таблицы `Examples`.

---

## 6. Валидация запроса

Объект: тело запроса. **Только интеграционные сценарии** — Bean Validation срабатывает на HTTP-границе, и сценарий, вызывающий сервис напрямую, ничего о ней не доказывает.

### 6.1 Payload транзакции и лимита — 46 сценариев

`api/request_validation.feature`.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Сумма, 10 строк: `0.00`, `0`, `0.01`, `0.99`, `100.00`, `999999999999.99`, `1000000000000.00`, `0.001`, `−0.01`, `−1000.00`. `@DecimalMin(inclusive = false)` и `@Digits(12,2)`: ноль отвергается, 12 цифр проходят, 13 — нет | **BVA** + **EP** | `The sum, by boundary and by equivalence class` |
| Сумма лимита, те же 5 границ — отдельный DTO с теми же аннотациями | **BVA** | `The sum of a limit follows the same boundaries` |
| Счёт, 7 строк: 9 / 10 / 11 цифр, буквы, дефис, пробел, плюс | **BVA** + **EP** | `The account is exactly ten digits` |
| Код валюты, 8 строк: 2 / 3 / 4 символа плюс `KZT1`, `U S`, `123`, `kzt` | **BVA** + **EP** | `The currency shortname is exactly three letters` |
| Категория, 3 строки. `gold` даёт 404, а не 400: категория разбираема, её просто нет | **EP** | `The category is one of the two the lookup holds` |
| Метка времени, 6 строк. CHECK строгий `>`: ровно на границе отвергается, секундой позже проходит | **BVA** | `The timestamp must be after 1990, and the comparison is strict` |
| Обязательное поле отсутствует, пустое, из пробелов — 3 класса одного «пустого» значения | **EP** | `A required field that is missing or blank` |
| Тело с одним полем из шести — первое отсутствующее, а не «где-то ошибка» | **EP** | `A body carrying only one field of the six` |
| Тело не является JSON | **EP** | `A body that is not JSON at all` |
| Тело без `Content-Type` → 415, до запуска Bean Validation | **EP** | `The body has to declare that it is JSON` |
| Граничное валидное значение **сохранено**, а не просто принято. 201 ничего не говорит о содержимом базы | **BVA** | `A valid boundary value is stored, not merely accepted` |

### 6.2 Ошибки контракта — 2 сценария

`api/client_api.feature`.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Невалидное тело → ProblemDetail, проверяется и тип, и конкретное поле в `errors` | **EP** | `An invalid body is answered with a ProblemDetail` |
| Неизвестная категория → ProblemDetail 404 | **EP** | `An unknown category is answered as a ProblemDetail 404` |

---

## 7. Лимиты через API

Объект: клиентская отчётность — что клиент видит после установки лимитов и трат.

`api/client_api.feature`, 3 сценария.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| «1 случай» по HTTP: вернулись ровно 3 и 13 января, с лимитом, который превышен. Сквозной автотест п. 6 задания | **ТЗ** | `Case 1 over HTTP — only 3 and 13 January come back, with the limit that was exceeded` |
| Месяц без превышений — пустой список. Пустой результат тоже контракт | **EP** | `A month with no exceeded transactions returns an empty list` |
| Лимиты счёта с остатком по месяцу: 2000 установлено, 1100 потрачено, 900 осталось | **BVA** | `The limits of an account come back with what the month has spent against them` |

---

## 8. Квота запросов

Объект: `RateLimitInterceptor`. Квота, которую невозможно исчерпать в тесте, не проверена ничем.

`quota/rate_limit.feature`, 3 сценария. Номера переопределены на 3 токена без пополнения — иначе границу не поймать детерминированно.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Три запроса проходят, четвёртый — 429 с `Retry-After`. Ровно на границе ёмкости | **BVA** | `The request past the quota is refused` |
| Квота на адрес, а не на счёт: ключ — адрес вызова, счёт в теле и недоступен перехватчику | **EP** | `The quota is per calling address, not per account` |
| Эндпоинт без квоты не ограничен: 9 запросов подряд, ни одного 429 | **EP** | `An endpoint without a quota is never limited` |

---

## 9. Соглашения архитектуры

Объект: структура кода. Не поведение, поэтому Gherkin был бы пересказом Java — остаётся JUnit.

`ArchitectureTest`, **10 правил**, по одному на строку. Ни одно из них не проверяет само себя: фикстуры с обратным примером удалены, каждая приносила больше вреда, чем пользы.

| Правило | Что ловит |
|---|---|
| `controllersMustNotAccessRepositoriesDirectly` | Обход сервисного слоя |
| `servicesMustNotDependOnControllers` | Зависимость, направленная наружу |
| `repositoriesMustNotDependOnServices` | Зависимость, направленная наружу |
| `controllersMustNotTalkToThePersistenceApiDirectly` | `EntityManager`/`JpaRepository` в веб-слое |
| `mappersMustBeMapStructInterfaces` | Рукописный конвертер вместо MapStruct |
| `exceptionHandlersMustLiveInTheExceptionPackage` | Второй обработчик исключений |
| `servicesMustNotDependOnTheWebLayerTypes` | HTTP-концерн, протекший в домен |
| `springBeansMustNotHaveMutableInstanceFields` | Потенциальная гонка в общем бине |
| `transactionalBeansMustRemainProxyable` | Тихая потеря транзакций: CGLIB не наследует final-класс |
| `beansMustBeFinalUnlessSomethingProxiesThem` | Мутабельный бин, который никто не проксирует |

---

## 10. Работоспособность

| Тест-кейс | Техника | Где |
|---|---|---|
| Контекст поднимается с Postgres и Redis из Testcontainers | **Smoke** | `FinancialTransactionsApplicationTests` |

---

## Как запустить

```bash
./mvnw test -Dtest=LimitFlagRuleFeatureTest   # чистые функции, ~200 мс, без контейнеров
./mvnw test -Dtest=ApiFeatureTest             # HTTP + адаптер провайдера через WireMock
./mvnw test -Dtest=ServiceFeatureTest         # сервисы против PostgreSQL
./mvnw test -Dtest=QuotaFeatureTest           # квота, с переопределёнными числами
./scripts/ci.sh                               # всё, включая Spotless и SpotBugs
```

Требуется Docker: три из четырёх сюитов поднимают Testcontainers.