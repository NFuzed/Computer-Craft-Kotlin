package app.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * The Postgres database every store saves to. Connection details come from environment variables:
 * - `FLEET_DB_URL`, default `jdbc:postgresql://localhost:5432/fleet`
 * - `FLEET_DB_USER`, default `fleet`
 * - `FLEET_DB_PASSWORD`, required
 *
 * On startup it runs `resources/db/schema.sql`, which creates any table that doesn't exist yet.
 */
class Database private constructor(
    private val dataSource: HikariDataSource
) : AutoCloseable by dataSource {

    constructor(url: String, user: String, password: String) : this(HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = 5
        poolName = "fleet-db"
    }))

    init {
        val schema = javaClass.classLoader.getResource("db/schema.sql")?.readText()
            ?: error("db/schema.sql is missing from the classpath")
        transaction { conn -> conn.createStatement().use { it.execute(schema) } }
    }

    /** Runs [block] in one transaction: committed if it returns, rolled back if it throws. */
    fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { conn ->
        conn.autoCommit = false
        try {
            block(conn).also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        }
    }

    companion object {
        fun fromEnvironment(): Database {
            val password = System.getenv("FLEET_DB_PASSWORD")
                ?: error("Set FLEET_DB_PASSWORD (and FLEET_DB_URL / FLEET_DB_USER if not the defaults)")
            return Database(
                url = System.getenv("FLEET_DB_URL") ?: "jdbc:postgresql://localhost:5432/fleet",
                user = System.getenv("FLEET_DB_USER") ?: "fleet",
                password = password
            )
        }
    }
}

/** Runs [sql] with [params] bound in order. */
fun Connection.execute(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { it.bind(params).executeUpdate() }

/** Runs [sql] with [params] bound in order, mapping each row with [map]. */
fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { stmt ->
        stmt.bind(params).executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun PreparedStatement.bind(params: Array<out Any?>): PreparedStatement {
    params.forEachIndexed { i, value ->
        when (value) {
            null -> setNull(i + 1, Types.NULL)
            is Int -> setInt(i + 1, value)
            is Long -> setLong(i + 1, value)
            is Boolean -> setBoolean(i + 1, value)
            is String -> setString(i + 1, value)
            is List<*> -> setArray(i + 1, connection.createArrayOf("text", value.toTypedArray()))
            else -> throw IllegalArgumentException("Can't bind ${value::class.simpleName}")
        }
    }
    return this
}

/** The column's value, or null if it is SQL NULL. */
fun ResultSet.getIntOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
