# pglite4j

Embedded PostgreSQL in plain Java bytecode. No containers, no native binaries, no external processes: just add a dependency and use a JDBC URL.

> **Warning**
> This project is highly experimental and in a very early stage of development. APIs may change, features are incomplete, and it is not yet recommended for production use. Feedback and contributions are very welcome!

## How it works

`pglite4j` bundles a full PostgreSQL 17 instance compiled to WebAssembly (WASI) and runs it directly inside the JVM via [Chicory](https://github.com/dylibso/chicory) (a pure-Java WebAssembly runtime). The JDBC driver opens an internal loopback socket and transparently bridges TCP to the WASM module's CMA (Channel Memory Access) shared memory, no network traffic ever leaves the process.

The build pipeline that produces the WASM binary runs inside Docker and chains several tools:

1. **wasi-sdk** — cross-compiles PostgreSQL + [PGlite](https://github.com/electric-sql/postgres-pglite) patches to a WASI target
2. **wasmtime** — runs `initdb` to create the database cluster
3. **Wizer** — snapshots the fully initialized PostgreSQL state (post-initdb, post-backend-start) so runtime startup skips all of that
4. **wasi-vfs** — embeds the read-only PostgreSQL distribution (`share/`, `lib/`) directly into the WASM binary
5. **wasm-opt** — optimizes the final binary for size

At build-time the Chicory compiler translates the WASM module to JVM bytecode. The JDBC driver (`PgLiteDriver`) opens a `ServerSocket` on a random loopback port, delegates to [pgjdbc](https://jdbc.postgresql.org/), and acts as a transparent byte shuttle between the TCP socket and PostgreSQL's wire protocol on the WASM memory.

```
DriverManager.getConnection("jdbc:pglite:memory://")
        |
        v
PgLiteDriver ──> boots WASM instance (pre-initialized via Wizer)
        |
        v
ServerSocket(127.0.0.1:<random-port>)
        |           ^
        |           |  raw PG wire protocol bytes
        v           |
   CMA shared memory  <──>  PostgreSQL (in WASM linear memory)
        |
        v
pgjdbc connects to localhost:<port>  ──>  returns java.sql.Connection
```

## Quick start

### Maven

Add the JDBC driver dependency:

```xml
<dependency>
  <groupId>io.roastedroot</groupId>
  <artifactId>pglite4j-jdbc</artifactId>
  <version>latest</version>
</dependency>
```

### Plain JDBC

```java
Connection conn = DriverManager.getConnection("jdbc:pglite:memory://");
conn.createStatement().execute("CREATE TABLE demo (id serial PRIMARY KEY, name text)");
conn.createStatement().execute("INSERT INTO demo (name) VALUES ('hello')");
```

### Quarkus

```properties
# application.properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:pglite:memory://
quarkus.datasource.jdbc.driver=io.roastedroot.pglite4j.jdbc.PgLiteDriver
quarkus.datasource.username=postgres
quarkus.datasource.password=password
quarkus.datasource.jdbc.min-size=1
quarkus.datasource.jdbc.max-size=1
quarkus.devservices.enabled=false
```

### Spring Boot - NOT TESTED

```properties
# application-test.properties
spring.datasource.url=jdbc:pglite:memory://
spring.datasource.driver-class-name=io.roastedroot.pglite4j.jdbc.PgLiteDriver
```

### HikariCP - NOT TESTED

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:pglite:memory://");
config.setMaximumPoolSize(1);
DataSource ds = new HikariDataSource(config);
```

## Project structure

```
pglite4j/
  core/          Core module — WASM lifecycle, CMA transport, wire protocol bridge
  jdbc/          JDBC driver — PgLiteDriver, ServiceLoader registration, socket bridge
  it/            Integration tests (Quarkus pet-clinic app with Hibernate + Panache)
  wasm-build/    Dockerized build pipeline for the PostgreSQL WASM binary
```

## Status and known limitations

- [ ] **Only `memory://` is supported** — no persistent / file-backed databases yet
- [ ] **Single connection only** — PGlite is single-threaded; connection pool max size must be 1
- [ ] **CMA buffer size is fixed** — large messages that exceed the CMA buffer (~12 MB total, ~16 KB per single message) are not yet handled via the file transport fallback
- [ ] **Limited extensions** — only `plpgsql` and `dict_snowball` are bundled; adding more requires rebuilding the WASM binary
- [ ] **Startup time** — first connection has some overhead it can be optimized more
- [ ] **Binary size** — the WASM binary + pgdata resources add several MBs to the classpath
- [ ] **Error recovery** — `clear_error()` integration for automatic transaction recovery is not yet wired up

## Building from source

```bash
# 1. Build the WASM binary (requires Docker)
cd wasm-build
make build

# 2. Unpack into core module resources
make unpack

# 3. Build the Java modules
cd ..
mvn install
```

## Acknowledgements

Special thanks to:
- **[PGLite](https://github.com/electric-sql/postgres-pglite)** - The Postgres build already mostly patched for Wasm
- **[PGLite-build](https://github.com/electric-sql/pglite-build)** - Spearheaded the build for the WASI target
- **[Chicory](https://github.com/dylibso/chicory)** - Pure Java WebAssembly runtime that makes this possible
