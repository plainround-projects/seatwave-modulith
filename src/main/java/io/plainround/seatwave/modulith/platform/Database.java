package io.plainround.seatwave.modulith.platform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The deployable's one database login and its local transactions. A call to {@link #inTransaction}
 * made while another is running on the same thread joins it, so a module's public method called by
 * another module's transaction writes inside that transaction; a call made outside any transaction
 * opens and commits its own.
 *
 * <p>Each module works through its own view of the handle ({@link #forModule}). On PostgreSQL the
 * view takes the module's role for the module's statements ({@code SET ROLE}), so the database
 * refuses a module's read of another module's table even inside one shared transaction; on H2,
 * which cannot switch roles, every view uses the one login.
 *
 * <p>Platform code: it knows nothing of SeatWave's modules, and no module's rule lives here.
 * Thread-safety: safe for concurrent use; each thread has its own current transaction.
 */
public final class Database {

  // The SQLSTATE every database uses for a unique-key violation (SQL standard class 23).
  private static final String UNIQUE_VIOLATION = "23505";
  // A role name is an identifier, which SET ROLE cannot take as a parameter: only names that match
  // this allow-list are ever written into the statement (code guidelines, rule 58).
  private static final Pattern ROLE_NAME = Pattern.compile("[a-z][a-z_]{0,62}");
  // The deployable's own view, which takes no module's role: on PostgreSQL its login reads nothing.
  private static final String NO_MODULE = "";

  private final Transactions transactions;
  private final String moduleRole;

  private Database(Transactions transactions, String moduleRole) {
    this.transactions = transactions;
    this.moduleRole = moduleRole;
  }

  /** Creates the deployable's handle; opens no connection yet. */
  public static Database open(Settings settings) {
    return new Database(new Transactions(Objects.requireNonNull(settings, "settings")), NO_MODULE);
  }

  /**
   * The view a module works through: the same transactions, in the module's role.
   *
   * @param role the module's database role, for example {@code inventory_module}
   * @throws IllegalArgumentException if the name is not a plain lower-case identifier
   */
  public Database forModule(String role) {
    Objects.requireNonNull(role, "role");
    if (!ROLE_NAME.matcher(role).matches()) {
      throw new IllegalArgumentException("not a role name: '" + role + "'");
    }
    return new Database(transactions, role);
  }

  /**
   * Runs the work in a local transaction at read committed: the current one if this thread is
   * already inside one, otherwise a new one that commits when the work returns and rolls back when
   * it throws.
   *
   * @return what the work returned
   * @throws IllegalStateException wrapping the database's error, after the rollback
   */
  public <T> T inTransaction(SqlWork<T> work) {
    Objects.requireNonNull(work, "work");
    Current joined = transactions.current.get();
    if (joined != null) {
      return runJoined(work, joined);
    }
    Settings settings = transactions.settings;
    try (Connection connection =
        DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password())) {
      connection.setAutoCommit(false);
      // Read committed is enough here: every rule that two transactions could race on is a
      // conditional update or a key, checked by the database at the moment of the write.
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      Current current = new Current(connection);
      transactions.current.set(current);
      try {
        takeRole(current, moduleRole);
        T result = work.run(connection);
        connection.commit();
        transactions.commits.incrementAndGet();
        return result;
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      } finally {
        transactions.current.remove();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("transaction failed for user " + settings.user(), failure);
    }
  }

  /** Whether this thread is inside a transaction of this deployable's database right now. */
  public boolean isInTransaction() {
    return transactions.current.get() != null;
  }

  /** Local transactions committed so far through this deployable's handles. */
  public int commits() {
    return transactions.commits.get();
  }

  /**
   * Runs an insert that the database may refuse with a unique-key error inside a savepoint, so the
   * refusal leaves the transaction usable: PostgreSQL aborts a transaction after any failed
   * statement, and only a rollback to a savepoint keeps the work done before it.
   *
   * @return true if the row was inserted, false if a unique key refused it
   * @throws SQLException any other error, unchanged
   */
  public static boolean insertUnlessDuplicate(Connection connection, PreparedStatement insert)
      throws SQLException {
    Savepoint beforeInsert = connection.setSavepoint();
    try {
      insert.executeUpdate();
      connection.releaseSavepoint(beforeInsert);
      return true;
    } catch (SQLException failure) {
      if (!UNIQUE_VIOLATION.equals(failure.getSQLState())) {
        throw failure;
      }
      connection.rollback(beforeInsert);
      return false;
    }
  }

  private <T> T runJoined(SqlWork<T> work, Current joined) {
    String callerRole = joined.role;
    try {
      takeRole(joined, moduleRole);
      T result = work.run(joined.connection);
      // Back to the caller's role, for the caller's next statement.
      takeRole(joined, callerRole);
      return result;
    } catch (SQLException failure) {
      // The outer transaction sees the exception and rolls back everything, this part included.
      throw new IllegalStateException("statement failed inside a joined transaction", failure);
    }
  }

  private void takeRole(Current current, String role) throws SQLException {
    if (transactions.settings.access() == Access.ONE_LOGIN || role.equals(current.role)) {
      return;
    }
    try (Statement statement = current.connection.createStatement()) {
      // RESET ROLE returns to the login itself, which on PostgreSQL inherits no module's rights.
      statement.execute(role.equals(NO_MODULE) ? "RESET ROLE" : "SET ROLE " + role);
    }
    current.role = role;
  }

  /** How the deployable's modules reach the database. */
  public enum Access {
    /** One login with rights on every module's schema: H2, the stand-in of the unit tests. */
    ONE_LOGIN,
    /** One login with no rights of its own that takes each module's role: PostgreSQL. */
    ROLE_PER_MODULE
  }

  /**
   * Where the database is and how to log in. A real password is read from the secret store (here a
   * file under {@code .secrets}) at run time; the lab's H2 in memory has none.
   *
   * @param jdbcUrl the database address, for example {@code jdbc:h2:mem:seatwave}
   * @param user the login
   * @param password the login's password; empty only for H2 in memory
   * @param access whether the modules take their own roles
   */
  public record Settings(String jdbcUrl, String user, String password, Access access) {

    /** Refuses a missing field and an address that is not a JDBC URL. */
    public Settings {
      Objects.requireNonNull(jdbcUrl, "jdbcUrl");
      Objects.requireNonNull(user, "user");
      Objects.requireNonNull(password, "password");
      Objects.requireNonNull(access, "access");
      if (!jdbcUrl.startsWith("jdbc:")) {
        throw new IllegalArgumentException("not a JDBC URL: '" + jdbcUrl + "'");
      }
    }

    /** Names the address and the login, never the password. */
    @Override
    public String toString() {
      return "Settings[" + jdbcUrl + " as " + user + ", " + access + "]";
    }
  }

  /** A unit of work against one connection, which may throw the driver's checked exception. */
  @FunctionalInterface
  public interface SqlWork<T> {

    /** Does the work; returns its result. */
    T run(Connection connection) throws SQLException;
  }

  /** The state every view of one deployable shares. Thread-safe: per-thread or atomic fields. */
  private static final class Transactions {

    private final Settings settings;
    // Instance state, not static: two deployables (the monolith and an extracted service) never
    // see each other's transaction.
    private final ThreadLocal<Current> current = new ThreadLocal<>();
    private final AtomicInteger commits = new AtomicInteger();

    Transactions(Settings settings) {
      this.settings = settings;
    }
  }

  /** One thread's open transaction and the role it is in. Confined to that thread. */
  private static final class Current {

    private final Connection connection;
    private String role = NO_MODULE;

    Current(Connection connection) {
      this.connection = connection;
    }
  }
}
