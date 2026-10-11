package com.idfinance.financial_transactions.config;

import com.idfinance.financial_transactions.logging.MaskedRemoteSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Sink;

/**
 * Makes the HTTP log safe to ship.
 *
 * <p>
 * Everything else about that log is configuration: bodies, headers and query
 * parameters are switches in {@code application.yml}, and an operator can see
 * them there. The caller's address is the exception — Logbook has no switch for
 * it, and it is the one field that identifies a person. So it is replaced here,
 * where the absence of a property is visible, rather than left to a setting
 * that does not exist.
 *
 * <p>
 * The sink decorates Logbook's own rather than replacing the formatting, so the
 * HTTP line carries the same metadata it always did: method, path, status and
 * duration. The address is the only thing this changes.
 */
@Configuration(proxyBeanMethods = false)
class HttpLoggingConfiguration {

	/**
	 * Logbook's default sink, wrapped so the address is masked on the way to it.
	 *
	 * <p>
	 * Built explicitly rather than asking Logbook's ECS module for a sink. That
	 * module <em>was</em> the output here and it was abandoned deliberately: it
	 * falls back to the plain formatter on any formatting failure, silently, which
	 * produced a line that looked like it had been structured and was not. An
	 * explicit default fails loudly if it ever stops working.
	 *
	 * <p>
	 * The application log around it is structured JSON in the profile that ships —
	 * see {@code logback-spring.xml}. This line is not, and the honest reason is
	 * that Logbook's structured formatter does not survive contact with this
	 * project's Jackson 3 setup.
	 *
	 * <p>
	 * {@link ConditionalOnMissingBean} so a deployment that wants a different
	 * formatter can define its own {@link Sink} and have it win: this is the
	 * default, not an override of somebody else's decision.
	 */
	@Bean
	@ConditionalOnMissingBean(Sink.class)
	Sink logbookSink() {
		return new MaskedRemoteSink();
	}

}
