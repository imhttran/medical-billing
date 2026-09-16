package com.htt.template.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

@Configuration
class DatabaseConfig {

    /**
     * Built by hand rather than from `spring.datasource.*` because the single
     * source of truth is DATABASE_URL (see [DatabaseUrl]).
     */
    @Bean
    fun dataSource(properties: AppProperties): DataSource {
        val url = DatabaseUrl.parse(properties.databaseUrl)
        val config = HikariConfig()
        config.jdbcUrl = url.jdbcUrl
        if (url.username != null) {
            config.username = url.username
        }
        if (url.password != null) {
            config.password = url.password
        }
        return HikariDataSource(config)
    }

    /**
     * The repository layer's only database dependency. Every query is raw SQL —
     * no ORM.
     */
    @Bean
    fun jdbcClient(dataSource: DataSource): JdbcClient = JdbcClient.create(dataSource)
}
