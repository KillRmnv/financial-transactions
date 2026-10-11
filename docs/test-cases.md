# Тест-кейсы

Всё поведение описано в Gherkin, в `src/test/resources/features`. Этот файл — указатель: **что
проверяется, какой техникой проектирования и в каком сценарии**. Чтобы найти тест-кейс, ищите
объект или ситуацию в оглавлении ниже, а не по всему репозиторию.

Сценариев в feature-файлах — **113**. Плюс JUnit вне Gherkin: правила архитектуры (раздел 13) и
проверки контекста и изоляции (раздел 14).

| Feature | Сценариев | Раздел |
| --- | --- | --- |
| `rule/limit_flag_rule` | 4 | 1.1 |
| `service/limit_flag_scenario` | 8 | 1.2 |
| `service/concurrency` | 5 | 1.3 |
| `rule/month_boundary` | 4 | 2 |
| `service/conversion_boundaries` | 14 | 3.1 |
| `api/client_api` | 16 | 3.2, 9, 10.2, 11 и один в 4 |
| `api/rates_fallback` | 8 | 3.3 |
| `service/currency_contract` | 7 | 4 |
| `service/currency_sweep` | 12 | 5 |
| `api/currency_pairs` | 11 | 6 |
| `rule/ledger_policy` | 6 | 7 |
| `service/rate_cache` | 4 | 8 |
| `api/request_validation` | 11 | 10.1 |
| `quota/rate_limit` | 3 | 12 |
| **Итого** | **113** | |

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

Объект: правило «превышена ли сумма». Это то, что сервис оценивают, и то, где ошибка дороже всего —
неверный флаг клиент видит как отказ в оплате.

### 1.1 Правило как чистая функция — 4 сценария, 9 + 4 + 2 + 5 строк

`rule/limit_flag_rule.feature`, 4 сценария. Без Spring и без базы, выполняется за ~200 мс.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Таблица истины, 9 строк: остаток ровно 0, `999.99 + 0.01`, копейка сверх, нулевая сумма | **ТЗ** + **BVA** | `The truth table, row by row` |
| «1 случай» — лимит поднят внутри месяца, месяц не сбрасывается. Остаток 900 = 2000 − 1100 | **ТЗ** | `Case 1 — the limit is raised mid-month without resetting the month` |
| «2 случай» — лимит ниже уже потраченного. В колонке «Остаток» задания арифметическая ошибка (−200 вместо −500), сверяемся с правилом | **ТЗ** | `Case 2 — a limit lower than what has already been spent` |
| Действующий лимит — последний установленный не позже транзакции, 4 строки | **BVA** | `The limit in effect is the latest one installed no later than the transaction` |

Строка `none` в последней таблице — **EP**: клиент не устанавливал лимита, ответ null, а не выдуманный
fallback.

### 1.2 Тот же сценарий против реального PostgreSQL — 8 сценариев

`service/limit_flag_scenario.feature`. Здесь важно другое: правильная арифметика может всё равно не
сохраниться.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| «1 случай» целиком: флаг только у 3 и 13 января | **ТЗ** | `Case 1 — only the transactions of 3 and 13 January are flagged` |
| Остаток ровно ноль не превышен | **BVA** + **Регресс** | `A remaining balance of exactly zero is not exceeded` |
| Транзакция помнит тот лимит, по которому считался её флаг | **Регресс** | `Each transaction remembers the limit its flag was computed against` |
| Новый месяц обнуляет итог (31.01 → 01.02) | **BVA** | `A new month starts a fresh running total` |
| Транзакция задним числом меряется против дефолта | **Регресс** | `Backdated transactions are measured against the standing fallback` |
| Дефолтный лимит датирован эпохой и не показывает дату установки | **Регресс** | `The automatic fallback is dated at the epoch and reports no installation date` |
| Транзакция старее любого лимита меряется против стоящего дефолта, а не против установленного позже | **Регресс** | `A backdated transaction is measured against the fallback, not a limit installed after it` |
| Параллельные posts одного клиента выстраиваются на строке лимита | **Регресс** | `Concurrent posts for one client serialise on the limit row` |

Последний — не формальность: без блокировки строки сценарий видит 1 флаг вместо 2.

### 1.3 Конкурентность отдельно — 5 сценариев

`service/concurrency.feature`, 5 сценариев. Вынесено из сценария флага, потому что проверяет не
правило, а поведение под нагрузкой, и требует своего стенда.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| 10 / 11 / 12 параллельных posts по 100 USD против лимита 1000 — 3 строки. Значение 11 выбрано как граница, при которой второй post вынужден ждать блокировку | **BVA** + **Регресс** | `Concurrent posts for one client serialise on the limit row` |
| 50 параллельных posts сохраняют все 50 строк и не теряют ни одной | **Регресс** | `Fifty concurrent posts store fifty transactions and lose none` |
| 20 гоняющихся первых транзакций создают ровно один автоматический лимит | **Регресс** | `Concurrent first transactions create exactly one automatic fallback` |
| Два аккаунта одновременно — два независимых прохода, блокировка не схлопывает чужие строки | **EP** | `Two accounts posting at once are two independent runs` |
| Валюта без курса сохраняется конкурентно, строки не теряются | **Регресс** | `A currency with no rate is stored concurrently without losing rows` |

**Честность этой проверки.** Сервисные утверждения вероятностны: два чтения пересекаются, только если
их окна совпали, а это вопрос планировщика. Снятие блокировки роняло **2 прогона из 4**. HTTP-сценарий
`Concurrent posts over HTTP all land, and the month's total adds up` ловит **3 из 3**, потому что круглый
запрос заметно расширяет окно. Корректная реализация детерминирована, и эта асимметрия сама по себе
сигнал.

---

## 2. Месячная граница

Объект: к какому месяцу относится транзакция. Транзакции приходят с разными offset'ами, и «первая
транзакция марта» обязана означать одно и то же.

`rule/month_boundary.feature`, 4 сценария, чистая функция.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Последняя и первая миллисекунды месяца, 5 строк | **BVA** | `The last and first instants of a month` |
| Offset отправителя не решает: 31.01 23:00 −06:00 — это уже февраль по UTC, и наоборот, 4 строки | **EP** + **BVA** | `The offset the sender used does not decide the month` |
| Граница года ведёт себя как граница месяца, 2 строки | **BVA** | `A year boundary behaves like a month boundary` |
| 29 февраля | **BVA** | `A leap day belongs to February and the month after it does not` |

Строки про offset — единственный класс, где ошибка проявилась бы при сумме у лимита, то есть редко.

---

## 3. Конверсия в валюту учёта

Объект: сумма в валюте учёта и цепочка получения курса. Две независимые вещи — арифметика округления и
порядок поиска курса.

### 3.1 Сервис: границы округления и цепочка поиска — 14 сценариев

`service/conversion_boundaries.feature`, 14 сценариев.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| HALF_UP на полцента, 7 строк: `0.025→0.03`, `0.020` без округления, курс `1.005` | **BVA** | `Rounding is HALF_UP, so an exact half goes up` |
| Курс ≤ 0 не конвертирует ничего, 2 строки. Ноль сделал бы транзакцию бесплатной, отрицательный — перевёрнутой | **EP** | `A rate that is not positive cannot convert anything` |
| Курс не округляется до умножения: `999.99 × 1.00000001` | **Регресс** | `The rate is not rounded before the amount is multiplied` |
| Минимальный положительный курс `0.00000001` — меньше уже невозможно при scale 8 | **BVA** | `The smallest positive rate is still a rate` |
| Произведение, которое колонка не вместит: сумма на границе × `10.00000001` → 400, а не падение на вставке. Граница одна на произведение, а не на сумму | **BVA** | `A product the ledger column cannot hold is refused, not written` |
| Курс ровно 10 держит сумму на границе внутри колонки — ровно та величина, ради которой граница на цифру ниже потолка колонки | **BVA** | `A rate of exactly ten keeps a sum at the boundary inside the column` |
| Курс меньше единицы границы не достигает никогда: сумма на границе уходит вниз | **BVA** | `A currency whose rate is below one cannot overflow at any legal sum` |
| Курс берётся на первое число месяца, а не на дату транзакции | **ТЗ** | `The rate of the month's first day is used as it is` |
| Курс за 3-е число не находится транзакцией от 5-го | **BVA** | `A later day of the same month is not the rate of this month` |
| Месяц без своего курса берёт последний более ранний | **EP** | `A month with no rate of its own falls back to the most recent earlier one` |
| Валюты без курса нигде: транзакция сохраняется без флага | **EP** | `A currency with no rate anywhere is stored rather than dropped` |
| Провайдер недоступен: теряется курс, не транзакция (п. 133 задания) | **EP** | `A provider that is down does not cost the transaction` |
| Найденный курс используется без обращения к провайдеру | **EP** | `A stored rate is found without asking the provider at all` |
| Валюта учёта курса не требует вовсе, ставка 1 | **EP** | `The ledger currency needs no rate at all` |

HALF_UP и HALF_DOWN расходятся **только** на ровно .5 — значении, которое «обычная» сумма не даёт
никогда.

### 3.2 Настоящий адаптер провайдера через WireMock — 6 сценариев

`api/client_api.feature`. Единственные сценарии, которые прогоняют **настоящий**
`TwelveDataRatesProvider`; остальные подменяют адаптер целиком и ничего о нём не говорят.

Этот файл разбирается в четырёх местах документа, и 16 сценариев делятся между ними: здесь 6, в
разделе 9 — 4, в 10.2 — 2, в разделе 11 — 3, и ещё один (`An unsupported currency is refused…`)
относится к справочнику валют и живёт в разделе 4.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Курс провайдера конвертирует транзакцию по сети | **ТЗ** | `A rate published by the provider converts the transaction over the wire` |
| День без своего `close` получает цену последнего закрытия перед ним | **ТЗ** | `A day with no close of its own is priced from the last close before it` |
| Недоступный провайдер стоит курса, а не транзакции | **EP** | `An unreachable provider costs the rate, not the transaction` |
| Пара, которую провайдер публикует в обратной ориентации, делится | **Регресс** | `A pair the provider publishes the other way round is converted by division` |
| Пары нет ни в одной ориентации: транзакция сохраняется без флага, а не отклоняется | **EP** | `A pair the provider does not publish in either direction is stored without a flag` |
| 20 параллельных `POST` по HTTP: все доходят, месячный итог сходится | **Регресс** | `Concurrent posts over HTTP all land, and the month's total adds up` |

Первые два из блока про резервный курс — **находки живого API, а не заглушки**. Провайдер не отдаёт
`previous_close` вообще, и сценарий раньше проходил, потому что WireMock клал это поле в ответ
сам. Снятие деления в адаптере роняет `converted by division`.

### 3.3 Резервный провайдер — 8 сценариев

`api/rates_fallback.feature`, 8 сценариев.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Платный провайдер не дал курс — резервный ответил | **EP** | `The fallback answers when the paid provider has nothing` |
| Сначала спрашивается платный: каждая проверка стоит денег | **EP** | `The paid provider is asked first` |
| Резервный спрашивается в той же ориентации, что и первая попытка | **BVA** | `The fallback is asked for the pair in the orientation asked for` |
| Суббота: у валютных пар есть курс, и запасного закрытия брать не нужно | **EP** | `A Saturday has a rate, and no previous close to fall back on` |
| Ни у одного провайдера нет — и это не ошибка | **EP** | `Neither provider has it, and that is not an error` |
| Транзакция старше бесплатного тарифа всё равно оценивается | **Регресс** | `A transaction older than the free tier is still priced` |
| Список валют берётся у платного, когда он есть | **EP** | `The currency list is the paid provider's when it has one` |
| Список резервного используется, когда у платного пусто, и без выведенных из оборота кодов | **Регресс** | `The fallback's list is used, with no retired codes in it, when the paid one has none` |

---

## 4. Валютный код

Объект: справочник валют. Код — проверяемая ссылка, а не строка, которую пишет кто угодно.

`service/currency_contract.feature` — 7 сценариев, плюс один из `api/client_api.feature`.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Схема отвергает код, не состоящий из трёх заглавных, 3 строки. `kzt` рядом с `KZT` — это вторая валюта | **BVA** + **EP** | `The schema refuses a code that is not three uppercase letters` |
| Сид миграции содержит ровно то, что нужно для старта: валюту учёта и два кода из ТЗ | **ТЗ** | `The seeded lookup holds the currencies the specification names` |
| Каждый код в справочнике прошёл CHECK схемы | **Регресс** | `Every code in the lookup came through a check the schema enforces` |
| Строчный код в запросе резолвится в seeded-валюту, а не сохраняется как есть | **Регресс** | `A lowercase code in a request resolves to the seeded currency` |
| Валюта вне справочника отвергается | **EP** | `A currency outside the lookup is refused` |
| Курс нельзя сохранить для валюты вне справочника — внешний ключ отвергает INSERT в обход приложения | **EP** | `A rate cannot be stored for a currency that is not in the lookup` |
| Валюта учёта и её масштаб приходят из конфигурации, а не константой | **ТЗ** | `The ledger currency and its scale come from configuration, not from a constant` |
| Неподдерживаемая валюта отвергается, а принятый набор публикуется — там не должно быть `"kzt"` | **EP** | `An unsupported currency is refused, and the accepted set is published` (api) |

Сценарий про 150 кодов **заменён** намеренно. Он проверял, что список ISO 4217 вклеен в миграцию, то
был утверждением о конкретном способе наполнения. Что схема действительно гарантирует — форму кода,
и это теперь отдельная строка.

---

## 5. Наполнение справочника — 12 сценариев

Объект: импорт кодов у провайдера. Справочник — не выгрузка из миграции, а то, что сервис принимает
**сейчас**, и он растёт при старте и еженедельно.

`service/currency_sweep.feature`, 12 сценариев.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Свип добавляет код, которого в справочнике нет | **Регресс** | `The sweep adds a code the lookup does not have` |
| Уже имеющийся код не добавляется второй раз | **BVA** | `A code the lookup already has is not added twice` |
| Код не из трёх заглавных пропускается, 3 строки | **BVA** + **EP** | `A code that is not three uppercase letters is skipped` |
| Пробелы вокруг кода не мешают | **EP** | `Surrounding whitespace does not stop a code being added` |
| Провайдер не может отдать список — справочник не трогается | **Регресс** | `A provider that cannot list leaves the lookup alone` |
| Валюта, добавленная свипом, принимается в транзакции | **ТЗ** | `A currency the sweep has not added is still refused` / `A currency the sweep added is then accepted in a transaction` |
| Свип, выключенный строкой в `scheduled_job`, не ходит к провайдеру | **ТЗ** | `A sweep switched off does not call the provider` |
| Включение обратно не требует перезапуска | **ТЗ** | `Switching the sweep back on needs no restart` |
| Установка значения, которое уже стоит, — успех, а не конфликт | **EP** | `The switch survives being set to the value it already has` |
| Стартовый импорт — тот же импорт, и он идемпотентен | **Регресс** | `The startup import is the same import, and it is idempotent` |
| Провайдер недоступен — теряется импорт, а не справочник | **EP** | `A provider that cannot be asked costs nothing but the import` |

## 6. Какие валюты обходятся ежедневно — 11 сценариев

Объект: `currency_pair`. Отдельная таблица от справочника, и разница принципиальная: отсутствие в ней —
**не отказ**, а только «не прогрето».

`api/currency_pairs.feature`, 11 сценариев.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Набор по умолчанию — два кода из ТЗ | **ТЗ** | `The tracked set starts as the two the specification names` |
| Неотслеживаемая валюта всё равно принимается в транзакции | **ТЗ** | `Being untracked is not a rejection` |
| Валюту можно добавить в обход через API | **ТЗ** | `A currency that was not tracked can be added to the sweep` |
| Отслеживаемую можно остановить, а затем вернуть | **ТЗ** | `A tracked currency stops being swept`, `A currency can be brought back after being retired` |
| Установка того же значения — 200, повтор долженucceed | **EP** | `Setting the value it already has is a success, not a conflict` |
| Неотслеживаемую валюту нельзя «вывести из оборота»: 404, а не тихий INSERT | **BVA** | `An untracked currency is not even retired` |
| Код резолвится так же, как в транзакции: `EUR` и ` eur `, 2 строки | **EP** | `A code is resolved the way a transaction resolves one` |
| Код с пробелами — тот же код | **EP** | `A code padded with spaces is still the same code` |
| Код не из трёх букв отвергается, 3 строки | **BVA** + **EP** | `A code that is not three letters is refused` |
| Валюта вне справочника не отслеживается: внешний ключ делает INSERT невозможным | **EP** | `A currency outside the lookup cannot be tracked` |

---

## 7. Конфигурация валюты учёта

Объект: `LedgerPolicy`. Ошибочное значение здесь не бросит исключение позже — оно молча оставит
валюту учёта пустой.

`rule/ledger_policy.feature`, 6 сценариев, без Spring.

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

## 8. Кэш курсов — 4 сценария

Объект: `RateCache`. Кэш, который может упасть, должен упасть так, чтобы это было незаметно.

`service/rate_cache.feature`, 4 сценария.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Вторая конверсия не читает таблицу снова | **EP** | `A second conversion is answered without reading the table again` |
| Обновлённый курс виден на следующем поиске, а не по истечении TTL | **Регресс** | `A refreshed rate is visible on the next lookup, not at the end of the TTL` |
| Обновление видно и для дня, который уже конвертировали | **Регресс** | `A refresh is visible for the day that was already converted too` |
| Нечитаемая закэшированная величина выбрасывается, а не принимается за курс | **Регресс** | `An unreadable cached rate is dropped rather than believed` |

Инвалидация счётчиком поколений, а не удалением ключей, — это то, что делает последние два сценария
проходящими: ключ кэширован под день, за которым **спросили**, а ответом мог быть курс более раннего
дня.

---

## 9. Батчевый приём — 4 сценария

Объект: `POST /api/v1/transactions/batch`. Необязательное требование 1*: параллельность по валютам
клиента.

`api/client_api.feature`.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Батч по нескольким валютам сохраняет и конвертирует всё | **ТЗ** | `A batch spanning several currencies stores and converts every transaction` |
| Пара, о которой спросили трижды, стоит одного вызова провайдеру | **Регресс** | `A batch asks the provider once for a pair it is asked twice about` |
| Невалидная транзакция в батче отвергает весь батч, и не пишется ничего | **BVA** | `A batch with an invalid transaction is refused and nothing is stored` |
| Неизвестная валюта отвергает батч | **EP** | `A batch naming a currency the lookup does not have is refused` |

Сценарий про один вызов **делает провайдера медленным намеренно**: координатор схлопывает запрос,
только пока тот в полёте, а мгновенный стаб успевает ответить до следующего вопроса — и утверждение
зависело бы от загрузки машины, а не от кода.

---

## 10. Валидация запроса

Объект: тело запроса. **Только интеграционные сценарии** — Bean Validation срабатывает на
HTTP-границе, и сценарий, вызывающий сервис напрямую, ничего о ней не доказывает.

### 10.1 Payload транзакции и лимита — 11 сценариев

`api/request_validation.feature`, 11 сценариев.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Сумма, 11 строк: `0.00`, `0`, `0.01`, `0.001`, `0.00000001`, `0.000000001`, `100.00`, `99999999999.99`, `99999999999.99999999`, `999999999999.99`, `−0.01`, `−1000.00`. `@DecimalMin(inclusive = false)` и `@Digits(11, 8)`: ноль отвергается, 9 дробных цифр — нет, 11 целых проходят, 12 — нет | **BVA** + **EP** | `The sum, by boundary and by equivalence class` |
| Сумма лимита, те же 7 границ — отдельный DTO с теми же аннотациями | **BVA** | `The sum of a limit follows the same boundaries` |
| Счёт, 7 строк: 9 / 10 / 11 цифр, буквы, дефис, пробел, плюс | **BVA** + **EP** | `The account is exactly ten digits` |
| Код валюты, 8 строк: 2 / 3 / 4 символа плюс `KZT1`, `U S`, `123`, `kzt` | **BVA** + **EP** | `The currency shortname is exactly three letters` |
| Категория, 3 строки. `gold` даёт 404, а не 400: категория разбираема, её просто нет | **EP** | `The category is one of the two the lookup holds` |
| Метка времени, 6 строк. CHECK строгий `>`: ровно на границе отвергается, секундой позже проходит | **BVA** | `The timestamp must be after 1990, and the comparison is strict` |
| Обязательное поле отсутствует, пустое, из пробелов — 3 класса одного «пустого» значения | **EP** | `A required field that is missing or blank` |
| Тело с одним полем из шести — первое отсутствующее, а не «где-то ошибка» | **EP** | `A body carrying only one field of the six` |
| Тело не является JSON | **EP** | `A body that is not JSON at all` |
| Тело без `Content-Type` → 415, до запуска Bean Validation | **EP** | `The body has to declare that it is JSON` |
| Граничное валидное значение **сохранено**, а не просто принято. 201 ничего не говорит о содержимом базы | **BVA** | `A valid boundary value is stored, not merely accepted` |

### 10.2 Ошибки контракта — 2 сценария

`api/client_api.feature`.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Невалидное тело → ProblemDetail, проверяется и тип, и конкретное поле в `errors` | **EP** | `An invalid body is answered with a ProblemDetail` |
| Неизвестная категория → ProblemDetail 404 | **EP** | `An unknown category is answered as a ProblemDetail 404` |
| Батч с невалидной транзакцией — тот же формат ошибки, что и у одиночного запроса | **BVA** | `A batch with an invalid transaction is refused and nothing is stored` |

---

## 11. Лимиты через API

Объект: клиентская отчётность — что клиент видит после установки лимитов и трат.

`api/client_api.feature`, 3 сценария.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| «1 случай» по HTTP: вернулись ровно 3 и 13 января, с лимитом, который превышен. Сквозной автотест п. 6 задания | **ТЗ** | `Case 1 over HTTP — only 3 and 13 January come back, with the limit that was exceeded` |
| Месяц без превышений — пустой список. Пустой результат тоже контракт | **EP** | `A month with no exceeded transactions returns an empty list` |
| Лимиты счёта с остатком по месяцу: 2000 установлено, 1100 потрачено, 900 осталось | **BVA** | `The limits of an account come back with what the month has spent against them` |

**Суммы этого сценария восстановлены из колонки «Остаток» задания.** Колонка «сумма транзакции» там
везде 100, и из неё остатки не выводятся; 100 / 700 / 100 / 100 дают ровно два превышения, как и
требует п. 6. Первая транзакция 13 января приводит месяц ровно к 2000 при лимите 2000 — это
не превышение, то есть граница из п. 5 закрывается тем же набором данных.

---

## 12. Квота запросов

Объект: `RateLimitInterceptor`. Квота, которую невозможно исчерпать в тесте, не проверена ничем.

`quota/rate_limit.feature`, 3 сценария. Номера переопределены на 3 токена без пополнения — иначе
границу не поймать детерминированно.

| Тест-кейс | Техника | Сценарий |
|---|---|---|
| Три запроса проходят, четвёртый — 429 с `Retry-After`. Ровно на границе ёмкости | **BVA** | `The request past the quota is refused` |
| Квота на адрес, а не на счёт: ключ — адрес вызова, счёт в теле и недоступен перехватчику | **EP** | `The quota is per calling address, not per account` |
| Эндпоинт без квоты не ограничен: 9 запросов подряд, ни одного 429 | **EP** | `An endpoint without a quota is never limited` |

---

## 13. Соглашения архитектуры

Объект: структура кода. Не поведение, поэтому Gherkin был бы пересказом Java — остаётся JUnit.

`ArchitectureTest`. Ни одно из правил не проверяет само себя: фикстуры с обратным примером удалены,
каждая приносила больше вреда, чем пользы.

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

## 14. База и контекст

| Тест-кейс | Техника | Где |
|---|---|---|
| Контекст поднимается с Postgres и Redis из Testcontainers | **Smoke** | `FinancialTransactionsApplicationTests` |
| Кэш выключен свойством — конверсия всё равно происходит | **BVA** | `RateCacheDisabledTests` |
| `READ COMMITTED` держится; неподтверждённая строка и обновление не видны | **Регресс** | `TransactionIsolationTests` |

Уровень изоляции проверяется отдельным тестом намеренно: если compose-база поменяет его, это должно
ронять тест, а не молча менять смысл флага.

---

## Как запустить

```bash
./mvnw test -Dtest=LimitFlagRuleFeatureTest   # чистые функции, ~200 мс, без контейнеров
./mvnw test -Dtest=ApiFeatureTest             # HTTP + настоящий адаптер провайдера через WireMock
./mvnw test -Dtest=ServiceFeatureTest         # сервисы против PostgreSQL
./mvnw test -Dtest=QuotaFeatureTest           # квота, с переопределёнными числами
./scripts/ci.sh                               # всё, включая Spotless и SpotBugs
```

Требуется Docker: три из четырёх сюитов поднимают Testcontainers.

## Чего в наборе нет

Сознательно не покрыто, и это стоит называть, а не прятать:

- **Медленный и «мигающий» провайдер.** Стратегия повторов настроена, но проверена только на 503,
  который не проходит никогда. Настоящая задержка ответа не воспроизводится.
- **Путь `findLatestOnOrBefore` на базе с историей длиннее одного дня.** Найдено на живых данных:
  запрос без `limit 1` возвращал Hibernate три строки там, где просил одну. Тесты этого не видели,
  потому что каждый сценарий чистит `currency_rate` и кладёт один день.
- **Глубина истории провайдера.** Около года на бесплатном тарифе — это ограничение источника, и
  сценарии спецификации на датах 2022 года проходят только на заглушке. На живом провайдере их
  воспроизвести нельзя в принципе.