package com.idfinance.financial_transactions.repository;

import com.idfinance.financial_transactions.entity.ScheduledJob;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Which scheduled jobs are on.
 *
 * <p>
 * Read one row per firing, which is once a day and once a week, so there is
 * nothing here worth caching and a cache would only add a way to be stale.
 */
public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, String> {

	Optional<ScheduledJob> findByName(String name);

	List<ScheduledJob> findAllByOrderByNameAsc();

}
