package com.idfinance.financial_transactions.service;

import com.idfinance.financial_transactions.entity.ScheduledJob;
import com.idfinance.financial_transactions.repository.ScheduledJobRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a scheduled job should run this time.
 *
 * <p>
 * Asked by a job's own method rather than by the bean definition, which is the
 * whole point: a condition on a bean is settled while the context registers it
 * and cannot change afterwards, so a job switched off through one stays
 * switched on until the next restart.
 */
@Service
@RequiredArgsConstructor
public class ScheduledJobService {

	private final ScheduledJobRepository scheduledJobRepository;

	/**
	 * A missing row means enabled.
	 *
	 * <p>
	 * Falling back to disabled would let a deleted row silently stop the daily
	 * sweep, which the specification requires, and would make the table
	 * load-bearing in a way a typo could exploit. Falling back to enabled
	 * reproduces the behaviour of the code that predates the table, which is the
	 * safe direction for both the job that costs money daily and the one that costs
	 * it yearly.
	 */
	@Transactional(readOnly = true)
	public boolean isEnabled(String name) {
		return this.scheduledJobRepository.findByName(name).map(ScheduledJob::isEnabled).orElse(true);
	}

	@Transactional(readOnly = true)
	public List<ScheduledJob> all() {
		return this.scheduledJobRepository.findAllByOrderByNameAsc();
	}

	@Transactional
	public ScheduledJob setEnabled(String name, boolean enabled) {
		ScheduledJob job = this.scheduledJobRepository.findByName(name)
				.orElseThrow(() -> new IllegalArgumentException("No scheduled job named '" + name + "'"));
		if (job.isEnabled() != enabled) {
			if (enabled) {
				job.enable();
			} else {
				job.disable();
			}
		}
		return job;
	}

}
