package com.htt.template.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Trusted devices skip the 2FA code on future logins. */
@Repository
class DeviceRepository(private val jdbc: JdbcClient) {

    fun isTrusted(userId: Int, deviceId: String): Boolean = jdbc
        .sql(
            """
            SELECT EXISTS (SELECT 1 FROM user_devices WHERE user_id = :userId AND device_id = :deviceId)
            """,
        )
        .param("userId", userId)
        .param("deviceId", deviceId)
        .query(Boolean::class.javaObjectType)
        .single() == true

    fun trust(userId: Int, deviceId: String) {
        jdbc.sql(
            """
            INSERT INTO user_devices (user_id, device_id) VALUES (:userId, :deviceId)
            ON CONFLICT (device_id) DO NOTHING
            """,
        )
            .param("userId", userId)
            .param("deviceId", deviceId)
            .update()
    }
}
