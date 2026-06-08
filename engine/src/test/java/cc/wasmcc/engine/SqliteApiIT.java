package cc.wasmcc.engine;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives a real {@code sqlite3.wasm} (wasm32-wasi reactor, built by
 * {@code make sqlite}) through the raw WebAssembly API — the Java mirror of the
 * mcp-v8 JS sqlite example. Proves end-to-end: instantiate with WASI stubs, call
 * {@code malloc}/{@code sqlite3_*} exports, and marshal C strings/pointers through
 * linear memory. An in-memory DB needs no fs/net caps.
 */
class SqliteApiIT {

    // sqlite result codes / column types
    private static final int SQLITE_OK = 0, SQLITE_ROW = 100, SQLITE_DONE = 101;
    private static final int COL_INT = 1, COL_FLOAT = 2, COL_NULL = 5;

    static byte[] resource(String path) throws Exception {
        try (InputStream in = SqliteApiIT.class.getResourceAsStream(path)) {
            assertNotNull(in, () -> "missing test resource " + path
                + " — run `nix develop -c make sqlite`");
            return in.readAllBytes();
        }
    }

    @Test
    void inMemoryQueryThroughRawApi() throws Exception {
        WasmHost host = new WasmHost();
        try (WasmInstance db =
                 host.instantiate(resource("/modules/sqlite3.wasm"), InstanceConfig.wasiOnly())) {
            Sqlite sql = new Sqlite(db);

            sql.exec("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, email TEXT, age INTEGER)");
            sql.exec("INSERT INTO users (name, email, age) VALUES ('Alice', 'alice@example.com', 30)");
            sql.exec("INSERT INTO users (name, email, age) VALUES ('Bob', 'bob@example.com', 25)");
            sql.exec("INSERT INTO users (name, email, age) VALUES ('Charlie', 'charlie@example.com', 35)");

            List<Map<String, Object>> rows = sql.query("SELECT name, age FROM users ORDER BY age");
            assertEquals(3, rows.size());
            assertEquals("Bob", rows.get(0).get("name"));
            assertEquals(25, ((Number) rows.get(0).get("age")).intValue());
            assertEquals("Alice", rows.get(1).get("name"));
            assertEquals("Charlie", rows.get(2).get("name"));

            List<Map<String, Object>> stats =
                sql.query("SELECT COUNT(*) AS count, AVG(age) AS avg_age FROM users");
            assertEquals(1, stats.size());
            assertEquals(3, ((Number) stats.get(0).get("count")).intValue());
            assertEquals(30.0, ((Number) stats.get(0).get("avg_age")).doubleValue(), 1e-9);

            sql.close();
        }
    }

    /** Minimal SQLite wrapper over the raw API — the Java analogue of the JS
     *  {@code SQLite} class in the mcp-v8 example. */
    private static final class Sqlite {
        private final WasmInstance w;
        private final MemoryView mem;
        private final int db;

        Sqlite(WasmInstance w) {
            this.w = w;
            this.mem = w.memory();
            int namePtr = mem.allocCString(":memory:");
            int ppDb = mem.allocPtr();
            int rc = w.callI32("sqlite3_open", namePtr, ppDb);
            assertEquals(SQLITE_OK, rc, "sqlite3_open rc");
            this.db = mem.readI32(ppDb);
            mem.free(namePtr);
            mem.free(ppDb);
        }

        String errmsg() {
            return mem.readCString(w.callI32("sqlite3_errmsg", db));
        }

        void exec(String sqlText) {
            int sqlPtr = mem.allocCString(sqlText);
            int rc = w.callI32("sqlite3_exec", db, sqlPtr, 0, 0, 0);
            mem.free(sqlPtr);
            assertEquals(SQLITE_OK, rc, () -> "exec rc for [" + sqlText + "]: " + errmsg());
        }

        List<Map<String, Object>> query(String sqlText) {
            int sqlPtr = mem.allocCString(sqlText);
            int ppStmt = mem.allocPtr();
            int rc = w.callI32("sqlite3_prepare_v2", db, sqlPtr, -1, ppStmt, 0);
            mem.free(sqlPtr);
            assertEquals(SQLITE_OK, rc, () -> "prepare rc: " + errmsg());
            int stmt = mem.readI32(ppStmt);
            mem.free(ppStmt);

            int cols = w.callI32("sqlite3_column_count", stmt);
            List<String> names = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                names.add(mem.readCString(w.callI32("sqlite3_column_name", stmt, c)));
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            while (true) {
                int step = w.callI32("sqlite3_step", stmt);
                if (step == SQLITE_DONE) break;
                assertEquals(SQLITE_ROW, step, () -> "step rc: " + errmsg());
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < cols; c++) {
                    int type = w.callI32("sqlite3_column_type", stmt, c);
                    Object v = switch (type) {
                        case COL_NULL -> null;
                        case COL_INT -> w.callI32("sqlite3_column_int", stmt, c);
                        case COL_FLOAT -> w.callF64("sqlite3_column_double", stmt, c);
                        default -> mem.readCString(w.callI32("sqlite3_column_text", stmt, c));
                    };
                    row.put(names.get(c), v);
                }
                rows.add(row);
            }
            w.callI32("sqlite3_finalize", stmt);
            return rows;
        }

        void close() {
            w.callI32("sqlite3_close", db);
        }
    }
}
