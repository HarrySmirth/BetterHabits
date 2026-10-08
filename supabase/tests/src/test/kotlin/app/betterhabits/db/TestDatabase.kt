package app.betterhabits.db

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

data class TestUser(val id: UUID, val email: String)

/**
 * One embedded Postgres per test JVM with the Supabase stub and every migration applied.
 * Tests isolate themselves by creating their own users and households.
 */
object TestDatabase {

    private val postgres: EmbeddedPostgres by lazy {
        EmbeddedPostgres.builder().start().also { pg ->
            pg.postgresDatabase.connection.use { conn ->
                conn.createStatement().use { it.execute(resource("supabase_stub.sql")) }
                migrationFiles().forEach { file ->
                    try {
                        conn.createStatement().use { it.execute(file.readText()) }
                    } catch (e: SQLException) {
                        throw IllegalStateException("Migration ${file.name} failed: ${e.message}", e)
                    }
                }
            }
        }
    }

    private fun resource(name: String): String =
        requireNotNull(TestDatabase::class.java.classLoader.getResource(name)) { "missing $name" }.readText()

    private fun migrationFiles(): List<File> {
        val dir = File(requireNotNull(System.getProperty("migrationsDir")) { "migrationsDir not set" })
        return dir.listFiles { f -> f.extension == "sql" }.orEmpty().sortedBy { it.name }
    }

    fun connection(): Connection = postgres.postgresDatabase.connection

    /** Creates an auth user the way Supabase Auth would (as a privileged role). */
    fun createUser(
        name: String = "User",
        email: String = "user-${UUID.randomUUID()}@example.test",
        isChild: Boolean = false,
    ): TestUser = connection().use { conn ->
        conn.prepareStatement(
            "insert into auth.users (email, raw_user_meta_data, raw_app_meta_data) " +
                "values (?, jsonb_build_object('display_name', ?::text), jsonb_build_object('is_child', ?::boolean)) returning id",
        ).use { st ->
            st.setString(1, email)
            st.setString(2, name)
            st.setBoolean(3, isChild)
            st.executeQuery().use { rs ->
                rs.next()
                TestUser(rs.getObject(1, UUID::class.java), email)
            }
        }
    }

    /** Runs [block] in one transaction as the `authenticated` role with [user]'s JWT claims. */
    fun <T> asUser(user: TestUser, block: Session.() -> T): T =
        inTransaction(role = "authenticated", claims = """{"sub":"${user.id}","email":"${user.email}","role":"authenticated"}""", block)

    fun <T> asAnon(block: Session.() -> T): T = inTransaction(role = "anon", claims = """{"role":"anon"}""", block)

    /** Superuser access for arranging test state (e.g. back-dating rows). Bypasses RLS. */
    fun <T> asAdmin(block: Session.() -> T): T = connection().use { conn -> Session(conn).block() }

    private fun <T> inTransaction(role: String, claims: String, block: Session.() -> T): T =
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("select set_config('request.jwt.claims', ?, true)").use {
                    it.setString(1, claims)
                    it.execute()
                }
                conn.createStatement().use { it.execute("set local role $role") }
                Session(conn).block().also { conn.commit() }
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
}

class Session(private val conn: Connection) {

    fun query(sql: String, vararg params: Any?): List<Map<String, Any?>> =
        conn.prepareStatement(sql).use { st ->
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            st.executeQuery().use { rs ->
                val meta = rs.metaData
                buildList {
                    while (rs.next()) {
                        add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) })
                    }
                }
            }
        }

    fun scalar(sql: String, vararg params: Any?): Any? = query(sql, *params).firstOrNull()?.values?.firstOrNull()

    fun count(sql: String, vararg params: Any?): Int = query(sql, *params).size

    fun update(sql: String, vararg params: Any?): Int =
        conn.prepareStatement(sql).use { st ->
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            st.executeUpdate()
        }
}
