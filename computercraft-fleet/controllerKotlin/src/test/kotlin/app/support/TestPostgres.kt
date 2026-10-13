package app.support

import app.store.Database
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real Postgres server for the tests, started from binaries that Gradle downloads (no install or Docker needed).
 * One server is shared by the whole test run, and every test gets its own empty database on it, so tests can't see
 * each other's data and can run in any order.
 */
object TestPostgres {
    // Test data is thrown away, so the server skips flushing to disk, which makes creating databases much faster
    private val lazyServer = lazy {
        EmbeddedPostgres.builder()
            .setServerConfig("fsync", "off")
            .setServerConfig("synchronous_commit", "off")
            .setServerConfig("full_page_writes", "off")
            .start()
    }
    private val server by lazyServer
    private val nextDatabase = AtomicInteger()

    /** A new, empty database with the schema created. Close it when done (the app module closes it on stop). */
    fun freshDatabase(): Database {
        val name = "test_${nextDatabase.incrementAndGet()}"
        // FILE_COPY copies the template's files instead of writing every page to the WAL, which is much faster here
        server.postgresDatabase.connection.use {
            it.createStatement().execute("CREATE DATABASE $name STRATEGY FILE_COPY")
        }
        return Database(url = server.getJdbcUrl("postgres", name), user = "postgres", password = "postgres")
    }

    /** Stops the server, if a test started it. */
    fun stop() {
        if (lazyServer.isInitialized()) server.close()
    }
}

/**
 * Stops [TestPostgres] once every test has run. EmbeddedPostgres would otherwise stop it from a JVM shutdown hook,
 * which on Windows runs out of time and leaves its files behind. Registered in
 * META-INF/services/org.junit.platform.launcher.LauncherSessionListener.
 */
class TestPostgresShutdown : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) = TestPostgres.stop()
}

/** Runs [block] with a fresh database, and closes it afterwards. */
fun <T> withDatabase(block: (Database) -> T): T = TestPostgres.freshDatabase().use(block)
