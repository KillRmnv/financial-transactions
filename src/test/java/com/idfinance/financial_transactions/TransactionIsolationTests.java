package com.idfinance.financial_transactions;

import com.idfinance.financial_transactions.bdd.support.StubRatesConfiguration;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the database guarantees, checked directly against it.
 *
 * <p>
 * Not a test of the service: a test of the property the service's correctness
 * rests on. {@code
 * TransactionService.applyFlag} takes a {@code PESSIMISTIC_WRITE} lock, and
 * that lock is only necessary because of what is verified here. Without it the
 * two are unrelated — the lock would be an unexplained line in a method.
 *
 * <p>
 * It lives as a JUnit test rather than a feature because it describes no
 * business rule. The flags, the running total and the truth table are in
 * {@code features/service}; what is here is the database underneath all of
 * them, and putting it in a feature would file it under behaviour the
 * specification never mentions.
 *
 * <p>
 * Plain JDBC on purpose, rather than two Spring transactions.
 * {@code @Transactional} hands each thread its own connection and commits at
 * method exit, which is exactly the bookkeeping these tests need to do
 * themselves — a transaction that has to stay open is not one a test fixture
 * should own.
 *
 * <p>
 * {@link StubRatesConfiguration} is imported and never touched. The weekly
 * currency sweep is conditional on a {@code RatesProvider} existing, so without
 * a bean of that type the context fails to start — and this test does not care
 * which provider it is, only what the database does with the rows.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StubRatesConfiguration.class})
class TransactionIsolationTests {

	private static final String ACCOUNT = "0000000777";

	private static final String COUNTERPART = "9999999999";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		this.jdbcTemplate.update("delete from expense_transaction");
		this.jdbcTemplate.update("delete from expense_limit");
	}

	@AfterEach
	void clearAnyLockLeftBehind() {
		// A test that failed mid-transaction leaves a row lock behind, and the next
		// test would wait
		// on it rather than fail on its own account. The lock timeout keeps that from
		// hanging.
		this.jdbcTemplate.execute("set lock_timeout = '2s'");
	}

	/**
	 * The level the whole design assumes, asserted rather than assumed.
	 *
	 * <p>
	 * If this fails, nothing below is wrong — the assumptions changed.
	 * {@code READ COMMITTED} is the PostgreSQL default and nothing in this project
	 * sets it, so a failure here means the database configuration moved rather than
	 * that the code did.
	 */
	@Test
	void theDatabaseIsAtReadCommitted() throws SQLException {
		String level;
		try (Connection connection = this.dataSource.getConnection();
				var statement = connection.createStatement();
				var result = statement.executeQuery("show transaction_isolation")) {
			result.next();
			level = result.getString(1);
		}

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(level).as("the isolation level the lock exists for").isEqualTo("read committed");
		softly.assertThat(level).as("SERIALIZABLE would make the lock redundant rather than wrong")
				.isNotEqualTo("serializable");
		softly.assertAll();
	}

	/**
	 * The reason the lock exists, stated as a test.
	 *
	 * <p>
	 * A transaction inserts a row and does not commit. Another one sums the month
	 * and does not see it.
	 *
	 * <p>
	 * That is the whole justification for {@code FOR UPDATE}: without it, two
	 * concurrent posts would each sum a month that excluded the other's pending
	 * row, each conclude the limit is not exceeded, and both would be right about
	 * the database and wrong about the month. The consequence is a lost update, and
	 * it is invisible afterwards — both rows exist and the total is too low.
	 *
	 * <p>
	 * The read goes through a <em>different</em> connection, and that is the whole
	 * test. Reading the sum on the blocking connection itself would see 400 — the
	 * row is in the transaction's own view — and assert nothing. Verified by
	 * mutation: pointing this at the blocking connection turns the test red.
	 *
	 * <p>
	 * The blocking half is the other thread's job and is not asserted here; {@code
	 * features/service/concurrency.feature} is where two transactions meet.
	 */
	@Test
	void anUncommittedTransactionIsInvisibleToAnother() throws Exception {
		BigDecimal committedTotal = withBlockedConnection((blocking) -> {
			// The blocker holds a transaction open with one row in it, uncommitted.
			run(blocking, insert("2022-01-05"));
			return totalForMonth();
		});

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(committedTotal).as("the month total without the pending row").isEqualByComparingTo("0");
		softly.assertThat(totalForMonth()).as("the total after the blocker committed").isEqualByComparingTo("400");
		softly.assertThat(this.jdbcTemplate.queryForObject("select count(*) from expense_transaction", Long.class))
				.as("the row is stored, it was only invisible while uncommitted").isEqualTo(1L);
		softly.assertAll();
	}

	/**
	 * A row that is updated and not committed cannot be seen in its new form by
	 * anyone else.
	 *
	 * <p>
	 * This is the other half of the same property, and it is the one that would
	 * bite the running total: {@code sumInLedgerForMonth} reads {@code ledger_sum}
	 * while another transaction is in the middle of recalculating it. Reading the
	 * old value is correct behaviour under this isolation level; reading a
	 * half-written one is not something a database can produce, and this is what
	 * says so.
	 */
	@Test
	void anUncommittedUpdateIsInvisible() throws Exception {
		storeCommitted("2022-01-05");
		BigDecimal stored = this.jdbcTemplate.queryForObject("select ledger_sum from expense_transaction",
				BigDecimal.class);

		BigDecimal seenWhilePending = withBlockedConnection((blocking) -> {
			run(blocking, "update expense_transaction set ledger_sum = 99999 where account_from = '" + ACCOUNT + "'");
			return this.jdbcTemplate.queryForObject("select ledger_sum from expense_transaction", BigDecimal.class);
		});

		SoftAssertions softly = new SoftAssertions();
		softly.assertThat(stored).as("what was stored to begin with").isEqualByComparingTo("400");
		softly.assertThat(seenWhilePending).as("the value another transaction may read while the update is pending")
				.isEqualByComparingTo("400");
		softly.assertThat(
				this.jdbcTemplate.queryForObject("select ledger_sum from expense_transaction", BigDecimal.class))
				.as("and the committed value afterwards").isEqualByComparingTo("99999");
		softly.assertAll();
	}

	/**
	 * Runs {@code work} on a connection whose transaction is held open until it
	 * returns, then commits.
	 *
	 * <p>
	 * The alternative — a latch and a second thread — races: the assertion could
	 * run before the blocking transaction had even inserted its row, and would pass
	 * without having tested anything. Doing it on one thread is deterministic, and
	 * the determinism is the entire reason this is not a BDD scenario.
	 *
	 * @param work
	 *            runs with an open transaction; its result is what the caller gets
	 *            to assert on
	 * @return whatever {@code work} returned, read while the transaction was still
	 *         open
	 */
	private <T> T withBlockedConnection(TransactionalWork<T> work) throws Exception {
		try (var executor = Executors.newSingleThreadExecutor()) {
			Future<T> pending = executor.submit((Callable<T>) () -> {
				try (Connection connection = this.dataSource.getConnection()) {
					connection.setAutoCommit(false);
					try {
						T result = work.run(connection);
						connection.commit();
						return result;
					} catch (RuntimeException | SQLException ex) {
						connection.rollback();
						throw ex;
					}
				}
			});
			try {
				return pending.get(20, TimeUnit.SECONDS);
			} catch (TimeoutException ex) {
				pending.cancel(true);
				throw new IllegalStateException(
						"The blocking transaction did not finish; the row lock is probably held", ex);
			}
		}
	}

	private BigDecimal totalForMonth() {
		BigDecimal total = this.jdbcTemplate.queryForObject(
				"select coalesce(sum(ledger_sum), 0) from expense_transaction where occurred_month = ?",
				BigDecimal.class, LocalDate.parse("2022-01-01"));
		return (total != null) ? total : BigDecimal.ZERO;
	}

	/**
	 * Commits a row through {@code JdbcTemplate}, for the tests that need one
	 * visible to everything.
	 */
	private void storeCommitted(String isoDate) {
		this.jdbcTemplate.update("""
				insert into expense_transaction
				    (account_from, account_to, currency_id, category_id, sum, ledger_sum, occurred_at,
				     occurred_month, flag_calculated, limit_exceeded, created_at)
				values (?, ?, (select id from currency where code = 'USD'),
				        (select id from expense_category where code = 'product'), 400, 400, ?::timestamptz,
				        '2022-01-01', true, false, now())
				""", ACCOUNT, COUNTERPART, java.sql.Timestamp.valueOf(LocalDate.parse(isoDate).atStartOfDay()));
	}

	/**
	 * The row the tests store: already converted, already flagged, so nothing here
	 * depends on the flag logic the features cover. Written as literal SQL because
	 * {@code blocking.executeUpdate} takes a statement and a statement cannot carry
	 * the {@code ?} parameters — the same insert through {@code JdbcTemplate} is
	 * used for the committed rows.
	 */
	private String insert(String isoDate) {
		return """
				insert into expense_transaction
				    (account_from, account_to, currency_id, category_id, sum, ledger_sum, occurred_at,
				     occurred_month, flag_calculated, limit_exceeded, created_at)
				values ('%s', '%s', (select id from currency where code = 'USD'),
				        (select id from expense_category where code = 'product'), 400, 400, '%s'::timestamptz,
				        '2022-01-01', true, false, now())
				""".formatted(ACCOUNT, COUNTERPART, isoDate);
	}

	@FunctionalInterface
	private interface TransactionalWork<T> {

		T run(Connection connection) throws SQLException;

	}

	/**
	 * Runs one statement on the open transaction. {@link Connection} has no
	 * {@code executeUpdate} of its own — that is {@link java.sql.Statement}'s — and
	 * going through {@code JdbcTemplate} here would take a second connection from
	 * the pool, which is the opposite of what the test is about.
	 */
	private void run(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

}
