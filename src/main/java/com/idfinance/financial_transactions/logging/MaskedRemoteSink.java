package com.idfinance.financial_transactions.logging;

import java.io.IOException;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.ForwardingHttpRequest;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.Sink;
import org.zalando.logbook.core.DefaultHttpLogFormatter;
import org.zalando.logbook.core.DefaultHttpLogWriter;
import org.zalando.logbook.core.DefaultSink;

/**
 * Logbook's HTTP log, with the caller's address reduced to its last octet.
 *
 * <p>
 * Logbook has switches for bodies, headers and query parameters, and none for
 * the remote address: {@code remote} is written as it arrives. That is the one
 * field of the metadata that identifies a person, so leaving it alone would
 * have meant shipping the thing this service was asked not to ship, with the
 * switches correctly set and everybody believing the log was clean.
 *
 * <p>
 * The address is masked rather than dropped because a throttled caller is
 * nearly always one caller repeating itself, and grouping those lines needs
 * something to group by. What is not there is the rest of it, which is both a
 * way to find a person and a description of the network this service runs in.
 *
 * <p>
 * <b>No field, deliberately.</b> The obvious shape is a decorator holding the
 * sink it decorates, and SpotBugs is right that this stores an externally
 * mutable object — it is also the only way a decorator works, and there is no
 * defensive copy of a sink to make. So the formatter and the writer are built
 * per call instead of kept. They are two small short-lived objects on a path
 * that is about to make an HTTP call, and the alternative is a field whose only
 * virtue is that it avoids two allocations.
 */
public final class MaskedRemoteSink implements Sink {

	@Override
	public void write(Precorrelation precorrelation, HttpRequest request) throws IOException {
		newSink().write(precorrelation, masked(request));
	}

	@Override
	public void write(Correlation correlation, HttpRequest request, HttpResponse response) throws IOException {
		newSink().write(correlation, masked(request), response);
	}

	private static DefaultSink newSink() {
		return new DefaultSink(new DefaultHttpLogFormatter(), new DefaultHttpLogWriter());
	}

	/**
	 * The same request with the address reduced.
	 *
	 * <p>
	 * An anonymous forwarding rather than a named type: there is nothing to say
	 * about it that is not already said above, and one line here is easier to check
	 * than a class that could later grow behaviour of its own.
	 */
	private static HttpRequest masked(HttpRequest request) {
		return new ForwardingHttpRequest() {
			@Override
			public HttpRequest delegate() {
				return request;
			}

			@Override
			public String getRemote() {
				return SafeLog.remoteAddress(request.getRemote());
			}
		};
	}

}
