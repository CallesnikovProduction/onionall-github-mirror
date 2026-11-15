package dev.onionall.brawler.archive.internal;

import dev.onionall.brawler.research.ResearchCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * <b>Единственный владелец JDBC-соединения и жизненного цикла архива.</b>
 *
 * <p>Monitor экземпляра сериализует чтения, транзакции, research и закрытие.
 * Порядок владения: monitor → JDBC transaction → catalog_guard → filesystem.
 * Неопределённый commit или неудачный cleanup выводит соединение из эксплуатации.
 *
 * <p>
 *     <i>
 *         Все изменяемые поля состояния защищены монитором
 *         текущего экземпляра; отдельная синхронизация не требуется.
 *     </i>
 * </p>
 *
 * @Refactor: Stephan Kolesnikov
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
public final class ArchiveContext implements AutoCloseable {

    /**
     * Корневой каталог архива и базы H2.
     * @Documented: Stephan Kolesnikov
     * */
    private final Path root;

    /**
     * Каталог неизменяемых HTML-объектов.
     * @Documented: Stephan Kolesnikov
     * */
    private final Path objects;

    /**
     * Единственное JDBC-соединение, принадлежащее контексту.
     * @Documented: Stephan Kolesnikov
     * */
    private final Connection db;

    /**
     * ResearchCatalog, использующий общее JDBC-соединение.
     * @Documented: Stephan Kolesnikov
     * */
    private final ResearchCatalog research;

    /**
     * Запрещает новые операции после закрытия или quarantine.
     * @Documented: Stephan Kolesnikov
     * */
    private boolean closed;

    /**
     * Подтверждает успешное освобождение JDBC-ресурса.
     * @Documented: Stephan Kolesnikov
     * */
    private boolean resourceReleased;

    /**
     * Предотвращает вложенные транзакции.
     * @Documented: Stephan Kolesnikov
     * */
    private boolean transactionActive;

    /** Глубина reentrant-вызовов под monitor; запрещает close до возврата callback. */
    private int operationDepth;

    /**
     * <b>Открывает проверенный каталог и инициализирует обе схемы до транзакций.</b>
     *
     * @param location каталог H2 и HTML-объектов
     * @throws IOException при небезопасном пути, несовместимой схеме или ошибке открытия
     */
    public ArchiveContext(Path location) throws IOException {
        root = location.toAbsolutePath().normalize();
        objects = root.resolve("objects");
        if (
                root.toString().contains(";") ||
                root.toString().contains("\n") ||
                root.toString().contains("\r")
        ) throw new IOException("Unsafe catalog path");

        ArchiveSupport.noSymlinks(root);
        Files.createDirectories(root);
        ArchiveSupport.noSymlinks(objects);
        Files.createDirectories(objects);

        for (String name : new String[] {
                "catalog.mv.db", "catalog.lock.db", "catalog.trace.db"
        }) {
            ArchiveSupport.noSymlinks(root.resolve(name));
        }

        Connection opened = null;
        ResearchCatalog initialized;

        try {
            Class.forName("org.h2.Driver");
            opened = DriverManager.getConnection(
                    "jdbc:h2:file:" +
                            root.resolve("catalog") +
                            ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;AUTO_SERVER=FALSE",
                    "sa",
                    ""
            );
            ArchiveSchema.migrate(opened);
            initialized = new ResearchCatalog(opened);
        } catch (Throwable error) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (Throwable cleanup) {
                    if (cleanup != error) {
                        error.addSuppressed(cleanup);
                    }
                }
            }
            switch (error) {
                case IOException io
                        -> throw io;
                case RuntimeException runtime
                        -> throw runtime;
                case Error fatal
                        -> throw fatal;
                default
                        -> {
                    // Тихо молчим...
                }
            }

            throw error instanceof Exception exception ? ArchiveSupport.failure(exception)
                : new IOException("Archive initialization failed", error);
        }
        db = opened;
        research = initialized;
    }

    /**
     * <b>Создаёт контекст для управляемого JDBC test double без DDL.</b>
     *
     * @param root временный каталог теста
     * @param db соединение, владение которым передаётся контексту
     */
    ArchiveContext(Path root, Connection db) {
        this.root = root;
        objects = root.resolve("objects");
        this.db = db;
        research = null;
    }

    /**
     * Возвращает immutable root без JDBC access.
     * @return нормализованный корневой путь
     */
    Path root() {
        return root;
    }

    /**
     * Возвращает путь объектов; публикацией управляет BlobStore.
     * @return каталог content-addressed HTML
     */
    Path objects() {
        return objects;
    }

    /**
     * Возвращает заимствованное соединение только владельцу monitor.
     *
     * <p>Заимствующий компонент не вправе вызывать {@code commit()},
     * {@code rollback()}, {@code close()} или изменять {@code autoCommit}. Управление
     * транзакцией и жизненным циклом принадлежит {@link ArchiveContext}.
     * @return соединение текущей операции; его нельзя сохранять или закрывать
     * @throws IOException если контекст закрыт
     * @throws IllegalStateException если вызов выполнен вне serializableRead/transaction
     */
    Connection connection() throws IOException {
        requireOwnership();
        ensureOpen();
        return db;
    }

    /**
     * Проверяет участие составной mutation в текущей context transaction.
     *
     * @throws IOException если контекст закрыт или transaction не начата
     * @throws IllegalStateException если caller не владеет monitor
     */
    void requireTransaction() throws IOException {
        requireOwnership();
        ensureOpen();
        if (!transactionActive) {
            throw new IOException("Archive transaction required");
        }
    }

    /**
     * Возвращает заранее инициализированный research adapter под тем же владением.
     * @return адаптер, заимствующий общее JDBC-соединение
     * @throws IOException если контекст закрыт или адаптер отсутствует в test double
     */
    ResearchCatalog research() throws IOException {
        requireOwnership();
        ensureOpen();
        if (research == null) {
            throw new IOException("Research adapter unavailable");
        }
        return research;
    }

    /**
     * <b>Проверяет предварительный порог размера H2.</b>
     *
     * <p>Отказывает при уже превышенном лимите 128 MiB.
     * Не гарантирует, что текущая транзакция не увеличит
     * размер базы сверх порога.
     *
     * @throws IOException при небезопасном пути, ошибке
     * файловой системы или превышении порога
     */
    void capacity() throws IOException {
        requireOwnership();
        ensureOpen();
        Path database = root.resolve("catalog.mv.db");
        ArchiveSupport.noSymlinks(database);
        if (
                Files.exists(database) &&
                Files.size(database) > 128L * 1024 * 1024
        ) throw new IOException("Catalog128MiB write limit reached; no automatic deletion");
    }

    /**
     * <b>Сериализует операцию под монитором контекста.</b>
     *
     * <p>Название serializableRead историческое: метод не устанавливает
     * JDBC serializableRead-only режим и не запрещает изменения данных.
     * Транзакционность определяется вызываемой операцией;
     * для атомарных изменений используется transaction().
     *
     * @param operation операция над ресурсами текущего контекста
     * @param <T> тип результата
     * @return результат операции
     * @throws IOException при ошибке операции или закрытом контексте
     */
    public synchronized <T> T serializableRead(Operation<T> operation) throws IOException {
        ensureOpen();
        operationDepth++;
        try {
            return operation.execute();
        } catch (SQLException error) {
            throw ArchiveSupport.failure(error);
        } finally {
            operationDepth--;
        }
    }

    /**
     * <b>Читает составную проекцию под общим catalog_guard.</b>
     *
     * <p>Сериализует чтение с Archive writers других contexts. В существующей
     * транзакции читает её состояние без нового begin, commit или savepoint;
     * самостоятельное чтение использует обычный transaction lifecycle.
     * Не превращает catalog projection в доказательство Frontier state.
     *
     * @param operation материализация проекции без самостоятельных mutations
     * @param <T> тип результата без открытых JDBC-ресурсов
     * @return согласованный относительно catalog_guard результат
     * @throws IOException при закрытом контексте или SQL/I/O/cleanup ошибке
     */
    public synchronized <T> T consistentRead(Operation<T> operation) throws IOException {
        return serializableRead(() -> transactionActive ? operation.execute() : transaction(operation));
    }

    /**
     * <b>Дожидается активной операции и закрывает единственное соединение.</b>
     *
     * <p>После неудачного JDBC close операции уже запрещены, но повторный close
     * повторяет освобождение ресурса. После успешного close JDBC больше не вызывается.
     *
     * @throws IOException при JDBC-ошибке или reentrant-вызове из активной операции;
     * повторное закрытие безопасно
     */
    @Override public synchronized void close() throws IOException {
        if (transactionActive || operationDepth > 0) {
            throw new IOException("Cannot close archive inside an active operation");
        }
        if (!resourceReleased) {
            closed = true;
            try {
                db.close();
                resourceReleased = true;
            } catch (SQLException error) {
                throw ArchiveSupport.failure(error);
            }
        }
    }

    /**
     * <b>Выполняет одну транзакцию с общим catalog_guard.</b>
     *
     * <p>Вложенные транзакции запрещены. Ошибка тела откатывается; ошибка commit
     * означает неизвестный результат. Readback после commit должен выполняться
     * вызывающим кодом после возврата отсюда. Ошибки cleanup не скрывают основную.
     *
     * @param operation SQL/файловая операция под monitor и H2 guard
     * @param <T> тип результата
     * @return результат после успешного commit и восстановления autocommit
     * @throws IOException при ошибке операции, неизвестном commit или cleanup
     */
    public synchronized <T> T transaction(Operation<T> operation) throws IOException {
        ensureOpen();
        if (transactionActive) {
            throw new IOException("Nested archive transaction is not supported");
        }

        transactionActive = true;

        boolean began = false;
        boolean commitAttempted = false;
        boolean committed = false;
        boolean reusable = true;

        T result = null;
        Throwable primary = null;

        try {
            if (!db.getAutoCommit()) {
                throw new SQLException("Archive connection has an unexpected transaction");
            }
            db.setAutoCommit(false);
            began = true;
            try (
                    var lock = db.prepareStatement(
                            "SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE"
                    );
                    var rows = lock.executeQuery()
            ) {
                if (!rows.next()) {
                    throw new IOException("Archive catalog guard unavailable");
                }
            }

            result = operation.execute();
            commitAttempted = true;
            db.commit();
            committed = true;
        } catch (Throwable error) {
            primary = error;
            if (commitAttempted) {
                if (error instanceof Error) {
                    error.addSuppressed(new IOException(
                            "Archive commit outcome is unknown; connection quarantined"
                    ));
                } else {
                    primary = new IOException(
                            "Archive commit outcome is unknown; connection quarantined",
                            error
                    );
                }
                reusable = false;
            }
            if (began && !committed) {
                try {
                    db.rollback();
                } catch (Throwable cleanup) {
                    if (cleanup != primary) {
                        primary.addSuppressed(cleanup);
                    }
                    reusable = false;
                }
            } else if (!began) {
                reusable = false;
            }
        } finally {
            if (began && reusable) {
                try {
                    db.setAutoCommit(true);
                } catch (Throwable cleanup) {
                    if (primary == null) {
                        primary = new IOException(
                                "Archive transaction committed; connection restoration failed",
                                cleanup
                        );
                    } else if (cleanup != primary) {
                        primary.addSuppressed(cleanup);
                    }
                    reusable = false;
                }
            }
            if (!reusable) {
                closed = true;
                try {
                    db.close();
                    resourceReleased = true;
                } catch (Throwable cleanup) {
                    if (primary != null && cleanup != primary) {
                        primary.addSuppressed(cleanup);
                    }
                }
            }
            transactionActive = false;
        }

        if      (primary instanceof IOException io)           throw io;
        else if (primary instanceof SQLException sql)         throw ArchiveSupport.failure(sql);
        else if (primary instanceof RuntimeException runtime) throw runtime;
        else if (primary instanceof Error error)              throw error;

        if (primary != null) {
            throw new IOException("Archive transaction failed", primary);
        }
        return result;
    }

    private void requireOwnership() {
        if (!Thread.holdsLock(this)) {
            throw new IllegalStateException("Archive connection requires context ownership");
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Archive is closed");
        }
    }

    /**
     * <b>Операция над заимствованными ресурсами текущего контекста.</b>
     *
     * @param <T> тип результата, не содержащего открытые JDBC-ресурсы
     */
    @FunctionalInterface
    public interface Operation<T> {
        /**
         * Выполняет действие под monitor контекста.
         * @return материализованный результат
         * @throws SQLException при ошибке JDBC
         * @throws IOException при ошибке файлов или контракта
         */
        T execute() throws SQLException, IOException;
    }
}
