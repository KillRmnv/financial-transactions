-- Схема финансовых транзакций.
--
-- Справочник валют.
--
-- Код валюты — это зафиксированный контракт, а не свободная строка: CHECK не даёт записать 'kzt'
-- рядом с 'KZT', поэтому опечатка не может стать второй валютой. Регистр проверяется на уровне
-- схемы, а не на аккуратность разработчика.
create table currency
(
    id           smallserial primary key,
    code         varchar(3) not null,
    display_name varchar(64) not null,
    created_at   timestamptz  not null default now(),
    constraint ux_currency_code unique (code),
    constraint ck_currency_code_uppercase check (code = upper(code)),
    constraint ck_currency_code_shape check (code ~ '^[A-Z]{3}$')
);

insert into currency (code, display_name)
values ('AED', 'UAE Dirham'),
       ('AFN', 'Afghan Afghani'),
       ('ALL', 'Albanian Lek'),
       ('AMD', 'Armenian Dram'),
       ('ANG', 'Netherlands Antillean Guilder'),
       ('AOA', 'Angolan Kwanza'),
       ('ARS', 'Argentine Peso'),
       ('AUD', 'Australian Dollar'),
       ('AWG', 'Aruban Florin'),
       ('AZN', 'Azerbaijani Manat'),
       ('BAM', 'Bosnia-Herzegovina Convertible Mark'),
       ('BBD', 'Barbadian Dollar'),
       ('BDT', 'Bangladeshi Taka'),
       ('BGN', 'Bulgarian Lev'),
       ('BHD', 'Bahraini Dinar'),
       ('BIF', 'Burundian Franc'),
       ('BMD', 'Bermudian Dollar'),
       ('BND', 'Brunei Dollar'),
       ('BOB', 'Bolivian Boliviano'),
       ('BRL', 'Brazilian Real'),
       ('BSD', 'Bahamian Dollar'),
       ('BTN', 'Bhutanese Ngultrum'),
       ('BWP', 'Botswanan Pula'),
       ('BYN', 'Belarusian Ruble'),
       ('BZD', 'Belize Dollar'),
       ('CAD', 'Canadian Dollar'),
       ('CDF', 'Congolese Franc'),
       ('CHF', 'Swiss Franc'),
       ('CLP', 'Chilean Peso'),
       ('CNY', 'Chinese Yuan'),
       ('COP', 'Colombian Peso'),
       ('CRC', 'Costa Rican Colon'),
       ('CUP', 'Cuban Peso'),
       ('CVE', 'Cape Verdean Escudo'),
       ('CZK', 'Czech Koruna'),
       ('DJF', 'Djiboutian Franc'),
       ('DKK', 'Danish Krone'),
       ('DOP', 'Dominican Peso'),
       ('DZD', 'Algerian Dinar'),
       ('EGP', 'Egyptian Pound'),
       ('ERN', 'Eritrean Nakfa'),
       ('ETB', 'Ethiopian Birr'),
       ('EUR', 'Euro'),
       ('FJD', 'Fijian Dollar'),
       ('FKP', 'Falkland Islands Pound'),
       ('GBP', 'British Pound'),
       ('GEL', 'Georgian Lari'),
       ('GHS', 'Ghanaian Cedi'),
       ('GIP', 'Gibraltar Pound'),
       ('GMD', 'Gambian Dalasi'),
       ('GNF', 'Guinean Franc'),
       ('GTQ', 'Guatemalan Quetzal'),
       ('GYD', 'Guyanaese Dollar'),
       ('HKD', 'Hong Kong Dollar'),
       ('HNL', 'Honduran Lempira'),
       ('HRK', 'Croatian Kuna'),
       ('HTG', 'Haitian Gourde'),
       ('HUF', 'Hungarian Forint'),
       ('IDR', 'Indonesian Rupiah'),
       ('ILS', 'Israeli New Shekel'),
       ('INR', 'Indian Rupee'),
       ('IQD', 'Iraqi Dinar'),
       ('IRR', 'Iranian Rial'),
       ('ISK', 'Icelandic Krona'),
       ('JMD', 'Jamaican Dollar'),
       ('JOD', 'Jordanian Dinar'),
       ('JPY', 'Japanese Yen'),
       ('KES', 'Kenyan Shilling'),
       ('KGS', 'Kyrgystani Som'),
       ('KHR', 'Cambodian Riel'),
       ('KMF', 'Comorian Franc'),
       ('KPW', 'North Korean Won'),
       ('KRW', 'South Korean Won'),
       ('KWD', 'Kuwaiti Dinar'),
       ('KYD', 'Cayman Islands Dollar'),
       ('KZT', 'Kazakhstani Tenge'),
       ('LAK', 'Laotian Kip'),
       ('LBP', 'Lebanese Pound'),
       ('LKR', 'Sri Lankan Rupee'),
       ('LRD', 'Liberian Dollar'),
       ('LSL', 'Lesotho Loti'),
       ('LYD', 'Libyan Dinar'),
       ('MAD', 'Moroccan Dirham'),
       ('MDL', 'Moldovan Leu'),
       ('MGA', 'Malagasy Ariary'),
       ('MKD', 'Macedonian Denar'),
       ('MMK', 'Myanmar Kyat'),
       ('MNT', 'Mongolian Tugrik'),
       ('MOP', 'Macanese Pataca'),
       ('MRU', 'Mauritanian Ouguiya'),
       ('MUR', 'Mauritian Rupee'),
       ('MVR', 'Maldivian Rufiyaa'),
       ('MWK', 'Malawian Kwacha'),
       ('MXN', 'Mexican Peso'),
       ('MYR', 'Malaysian Ringgit'),
       ('MZN', 'Mozambican Metical'),
       ('NAD', 'Namibian Dollar'),
       ('NGN', 'Nigerian Naira'),
       ('NIO', 'Nicaraguan Cordoba'),
       ('NOK', 'Norwegian Krone'),
       ('NPR', 'Nepalese Rupee'),
       ('NZD', 'New Zealand Dollar'),
       ('OMR', 'Omani Rial'),
       ('PAB', 'Panamanian Balboa'),
       ('PEN', 'Peruvian Sol'),
       ('PGK', 'Papua New Guinean Kina'),
       ('PHP', 'Philippine Peso'),
       ('PKR', 'Pakistani Rupee'),
       ('PLN', 'Polish Zloty'),
       ('PYG', 'Paraguayan Guarani'),
       ('QAR', 'Qatari Rial'),
       ('RON', 'Romanian Leu'),
       ('RSD', 'Serbian Dinar'),
       ('RUB', 'Russian Ruble'),
       ('RWF', 'Rwandan Franc'),
       ('SAR', 'Saudi Riyal'),
       ('SBD', 'Solomon Islands Dollar'),
       ('SCR', 'Seychellois Rupee'),
       ('SDG', 'Sudanese Pound'),
       ('SEK', 'Swedish Krona'),
       ('SGD', 'Singapore Dollar'),
       ('SHP', 'St. Helena Pound'),
       ('SLE', 'Sierra Leonean Leone'),
       ('SOS', 'Somali Shilling'),
       ('SRD', 'Surinamese Dollar'),
       ('SSP', 'South Sudanese Pound'),
       ('STN', 'Sao Tome and Principe Dobra'),
       ('SVC', 'Salvadoran Colon'),
       ('SYP', 'Syrian Pound'),
       ('SZL', 'Swazi Lilangeni'),
       ('THB', 'Thai Baht'),
       ('TJS', 'Tajikistani Somoni'),
       ('TMT', 'Turkmenistani Manat'),
       ('TND', 'Tunisian Dinar'),
       ('TOP', 'Tongan Paanga'),
       ('TRY', 'Turkish Lira'),
       ('TTD', 'Trinidad and Tobago Dollar'),
       ('TWD', 'New Taiwan Dollar'),
       ('TZS', 'Tanzanian Shilling'),
       ('UAH', 'Ukrainian Hryvnia'),
       ('UGX', 'Ugandan Shilling'),
       ('USD', 'US Dollar'),
       ('UYU', 'Uruguayan Peso'),
       ('UZS', 'Uzbekistani Som'),
       ('VES', 'Venezuelan Bolivar'),
       ('VND', 'Vietnamese Dong'),
       ('VUV', 'Vanuatu Vatu'),
       ('WST', 'Samoan Tala'),
       ('XAF', 'Central African CFA Franc'),
       ('XCD', 'East Caribbean Dollar'),
       ('XOF', 'West African CFA Franc'),
       ('XPF', 'CFP Franc'),
       ('YER', 'Yemeni Rial'),
       ('ZAR', 'South African Rand'),
       ('ZMW', 'Zambian Kwacha'),
       ('ZWG', 'Zimbabwe Gold');

-- Справочник категорий расходов: внешний ключ вместо enum-колонки, и перечень виден в базе.
create table expense_category
(
    id           smallserial primary key,
    code         varchar(32) not null,
    display_name varchar(128) not null,
    constraint ux_expense_category_code unique (code)
);

insert into expense_category (code, display_name)
values ('product', 'Product'),
       ('service', 'Service');

-- Служебная таблица валют, курсы по которым ежедневно опрашиваются заранее.
--
-- Набор живёт в базе, а не в application.yml: оператор должен уметь добавить или убрать валюту без
-- релиза. За каждую проверку внешнего API платят, поэтому «крутить заранее» — операционное решение,
-- и оно принадлежит оператору, а не коду.
--
-- Колонки quote здесь нет и не должно быть: валюта учёта одна (app.ledger.currency-code), а курс в
-- currency_rate хранится без цитаты — она подразумевается. Хранить копию единственного значения
-- синглтона значило бы завести второе место истины, которое может с ним разойтись, и проверять
-- расхождение пришлось бы в коде. Quote подставляется в момент запроса, поэтому при переводе учёта на
-- другую валюту эту таблицу трогать не нужно вовсе.
--
-- Валюта вне этого списка не запрещена: ExchangeRateService возьмёт её курс по требованию, в первом
-- же запросе. Список решает только, что уже прогрето.
create table currency_pair
(
    id            bigserial primary key,
    base_currency varchar(3) not null references currency (code),
    active        boolean     not null default true,
    created_at    timestamptz  not null default now(),
    constraint ux_currency_pair unique (base_currency)
);


insert into currency_pair (base_currency)
values ('KZT'),
       ('RUB');

create index ix_currency_pair_active on currency_pair (active) where active;

-- Месячные лимиты расходов. Неизменяемы: только добавление, обновление запрещено спецификацией,
-- поэтому у сущности нет ни сеттера sum, ни сеттера created_at.
--
-- account_from — varchar(10), а не ссылка на таблицу счетов и это описание формата поля. Нормализация потребовала бы знать про счёт
-- то, чего в задаче нет (владелец, валюта счёта, статус).
create table expense_limit
(
    id          bigserial primary key,
    account_from varchar(10) not null,
    category_id  smallint      not null references expense_category (id),
    -- Лимит всегда в валюте учёта; ссылка делает это фактом схемы, а не строкой в коде.
    currency_id  smallint      not null references currency (id),
    amount       numeric(14, 2) not null,
    -- Лимит по умолчанию датируется эпохой: он действует, пока его не заменит настоящий. Транзакция
    -- может прийти с датой раньше, чем сервис узнал о клиенте, и «сейчас» в качестве даты
    -- установки оставило бы её вовсе без лимита для сравнения.
    created_at   timestamptz not null,
    -- Помечает автоматически созданный лимит 1000 USD: он датирован эпохой, и показывать клиенту
    -- «установлено 1 января 1970» хуже, чем не показывать ничего.
    is_default   boolean      not null default false,
    constraint ck_expense_limit_amount_positive check (amount > 0),
    constraint ck_expense_limit_account_digits check (account_from ~ '^[0-9]{10}$')
);

-- Не более одного лимита по умолчанию на пару (счёт, категория). На эту гарантию опирается
-- INSERT ... ON CONFLICT DO NOTHING при создании лимита по умолчанию: без неё два параллельных
-- запроса создали бы два таких лимита.
create unique index ux_expense_limit_default
    on expense_limit (account_from, category_id)
    where is_default;

-- Обслуживает выборку действующего лимита: последний с created_at <= :occurredAt.
create index ix_expense_limit_account_category_created
    on expense_limit (account_from, category_id, created_at desc);

create table expense_transaction
(
    id            bigserial primary key,
    account_from  varchar(10) not null,
    account_to    varchar(10) not null,
    currency_id   smallint      not null references currency (id),
    amount        numeric(14, 2) not null,
    category_id   smallint      not null references expense_category (id),
    occurred_at   timestamptz not null,
    -- Первое число месяца в часовом поясе учёта, чтобы не пересчитывать границы на каждый запрос.
    occurred_month date        not null,
    -- Сумма в валюте учёта (app.ledger.currency-code). Валюта учёта
    -- задаётся конфигурацией, и колонка хранит именно её сумму.
    ledger_sum    numeric(14, 2),
    applied_rate  numeric(19, 8),
    rate_date     date,
    -- Лимит, по которому на самом деле выставлялся флаг. Без него запрос отчётности вывел бы для
    -- прошлых транзакций тот лимит, который действует сейчас, а не тот, который их превысил.
    limit_id      bigint   references expense_limit (id),
    limit_exceeded boolean,
    -- Различает «вычислен и равен false» и «не вычислен, курса не было» — по наличию строки, а не
    -- отдельной колонкой: вычисленный флаг и есть limit_exceeded is not null, поэтому хранить второе
    -- отдельно значило бы хранить производную величину и согласовывать её с оригиналом вручную.
    --
    -- Производная колонка, а не CHECK: старый CHECK был односторонним и пропускал
    -- flag_calculated = false вместе с limit_exceeded = true. Здесь такое состояние невозможно по
    -- построению — значение вычисляется базой, и ошибиться в нём нельзя.
    flag_calculated boolean generated always as (limit_exceeded is not null) stored,
    created_at     timestamptz not null default now(),
    constraint ck_expense_transaction_amount_positive check (amount > 0),
    constraint ck_expense_transaction_account_from_digits check (account_from ~ '^[0-9]{10}$'),
    constraint ck_expense_transaction_account_to_digits check (account_to ~ '^[0-9]{10}$'),
    constraint ck_expense_transaction_occurred_at_sane check (occurred_at > timestamptz '1990-01-01 00:00:00+00')
);

-- Расчёт остатка за месяц и выборка превышенных.
create index ix_expense_transaction_account_category_month
    on expense_transaction (account_from, category_id, occurred_month);

-- Частичный индекс под выборку превышенных: строки с limit_exceeded = false в него не попадают.
create index ix_expense_transaction_exceeded
    on expense_transaction (account_from, occurred_month)
    where limit_exceeded;

-- Дневные курсы. Ссылка на справочник, а не строка: код валюты должен быть проверенным.
--
-- Хранится только базовая сторона пары (валюта к USD). Обратная выводится делением, и хранить её
-- отдельно значило бы завести две строки, которые со временем разойдутся.
create table currency_rate
(
    id              bigserial primary key,
    base_currency   varchar(3)   not null references currency (code),
    rate_date       date         not null,
    close           numeric(19, 8),
    previous_close  numeric(19, 8),
    source          varchar(32)  not null,
    fetched_at      timestamptz  not null default now(),
    constraint ux_currency_rate_base_date unique (base_currency, rate_date),
    constraint ck_currency_rate_has_value check (close is not null or previous_close is not null)
);
