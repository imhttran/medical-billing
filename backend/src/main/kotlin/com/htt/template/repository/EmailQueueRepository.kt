package com.htt.template.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * The Postgres-backed outbound mail queue. Rows are written inside the same
 * transaction as the change that caused them, and drained by the worker
 * ([com.htt.template.service.EmailWorker]) — so no request ever blocks on a
 * mail server.
 */
@Repository
class EmailQueueRepository(private val jdbc: JdbcClient) {

    data class Job(val id: Int, val to: String, val subject: String, val body: String, val attempts: Int)

    fun enqueue(to: String, subject: String, body: String) {
        jdbc.sql("INSERT INTO email_queue (\"to\", subject, body) VALUES (:to, :subject, :body)")
            .param("to", to)
            .param("subject", subject)
            .param("body", body)
            .update()
    }

    fun findPending(maxAttempts: Int, limit: Int): List<Job> = jdbc
        .sql(
            """
            SELECT id, "to", subject, body, attempts
            FROM email_queue
            WHERE status = 'pending' AND attempts < :maxAttempts
            ORDER BY created_at ASC
            LIMIT :limit
            """,
        )
        .param("maxAttempts", maxAttempts)
        .param("limit", limit)
        .query(Job::class.java)
        .list()

    fun markSent(id: Int) {
        jdbc.sql("UPDATE email_queue SET status = 'sent', sent_at = now() WHERE id = :id")
            .param("id", id)
            .update()
    }

    fun markAttempted(id: Int, attempts: Int, error: String?, status: String) {
        jdbc.sql(
            """
            UPDATE email_queue SET attempts = :attempts, last_error = :error, status = :status
            WHERE id = :id
            """,
        )
            .param("attempts", attempts)
            .param("error", error)
            .param("status", status)
            .param("id", id)
            .update()
    }
}
