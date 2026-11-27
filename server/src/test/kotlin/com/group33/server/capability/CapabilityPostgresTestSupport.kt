package com.group33.server.capability

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.nio.file.Path
import java.sql.Connection
import java.time.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.postgresql.ds.PGSimpleDataSource

internal object CapabilityPostgresTestSupport {
    fun required(name: String) = requireNotNull(System.getenv(name)) { "Missing explicit integration setting $name" }
    fun source(schema: String? = null) = PGSimpleDataSource().apply {
        val url = required("S2_PG_INTEGRATION_URL")
        require(url == "jdbc:postgresql://127.0.0.1:55432/group33_s2_local") { "Only the dedicated local practice database is allowed" }
        setURL(url); user = required("S2_PG_INTEGRATION_USER"); password = required("S2_PG_INTEGRATION_PASSWORD")
        connectTimeout = 5; socketTimeout = 10
        schema?.let { require(it.matches(Regex("s2_capability_it_[a-f0-9]+"))); currentSchema = it; applicationName = it }
    }
    fun install(schema: String) {
        source(schema).connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute(checkNotNull(javaClass.getResource("/db/prototypes/capability-store-v1.sql")).readText()) }
            c.commit()
        }
    }
    fun admin(text: String) = source().connection.use { c -> c.createStatement().use { it.execute(text) } }
    fun control(action: String) {
        require(action in listOf("stop", "start"))
        val child = ProcessBuilder(required("S2_PG_PYTHON"), required("S2_PG_MANAGER_SCRIPT"), action).redirectErrorStream(true).start()
        try {
            check(child.waitFor(40, TimeUnit.SECONDS)) { "Database control timed out" }
            check(child.exitValue() == 0) { "Database control failed; inspect private local logs" }
        } finally { if (child.isAlive) child.destroyForcibly() }
    }
    val elder = AnonymousUser("fictional-capability-elder", "fictional-capability-install", "Fictional Elder", UserRole.ELDER)
    fun report(sequence: Long = 1, id: UUID = UUID.randomUUID()) = ReminderCapabilityReport(id.toString(), sequence, 100,
        DeliveryCondition.AVAILABLE, true, true, ExactAlarmAccess.GRANTED, listOf(" keep spaces ", "fictional", "fictional"))
    fun owner(actor: AnonymousUser = elder) = CapabilityOwner(actor.id, actor.installationId)
    fun columns(c: Connection, sql: String) = c.createStatement().use { it.executeQuery(sql).use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
}
internal class CapabilityDatabaseClock : Clock() {
    val now = AtomicLong(Instant.parse("2026-10-27T02:00:00Z").toEpochMilli())
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    override fun instant(): Instant = Instant.ofEpochMilli(now.get())
}
