package dev.catananti.portfolio_blog_v2;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test that loads the full application context.
 * Disabled by default as it requires external dependencies (PostgreSQL, Redis, SMTP).
 * Run with Testcontainers or use a dedicated integration test profile.
 */
@SpringBootTest
@Disabled("Requires external dependencies - run with integration test profile")
class PortfolioBlogV2ApplicationTests {

	@Test
	void contextLoads() {
	}

}
