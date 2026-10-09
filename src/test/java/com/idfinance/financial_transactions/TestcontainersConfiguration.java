package com.idfinance.financial_transactions;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The infrastructure the Spring tests need: PostgreSQL and Redis as
 * {@code @ServiceConnection} beans.
 *
 * <p>
 * The image tags are pinned to {@code postgres:16} and {@code redis:7}. The
 * {@code :latest} tags are not cached on a developer machine and pulling them
 * stalls long enough to look like a hang.
 */

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
	private static final DockerImageName POSTGRES = DockerImageName.parse("postgres:16");
	private static final DockerImageName REDIS = DockerImageName.parse("redis:7");

	@Bean
	@ServiceConnection
	public PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES);
	}

	@Bean
	@ServiceConnection(name = "redis")
	public GenericContainer<?> redisContainer() {
		return new GenericContainer(REDIS).withExposedPorts(new Integer[]{6379});
	}
}
