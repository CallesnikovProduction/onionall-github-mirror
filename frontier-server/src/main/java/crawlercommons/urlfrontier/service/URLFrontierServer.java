// SPDX-FileCopyrightText: 2020 Crawler-commons
// SPDX-License-Identifier: Apache-2.0

package crawlercommons.urlfrontier.service;

import crawlercommons.urlfrontier.service.rocksdb.RocksDBService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * <b>Управляет запуском и завершением серверной части URLFrontier.</b>
 *
 * <p>Принимает настройки командной строки, создаёт реализацию
 * службы Frontier, регистрирует защищённые gRPC-обработчики
 * и при необходимости запускает сбор метрик Prometheus.</p>
 *
 * <p>Отвечает за жизненный цикл серверных компонентов,
 * но не реализует планирование URL, резервирование заданий,
 * загрузку веб-страниц или постоянное хранение данных.
 * Эти обязанности принадлежат специализированным службам.</p>
 *
 * @author GPT-6.1 Sol
 * @Reviewer: Stephan Kolesnikov
 */
@Command(
        name = "URL Frontier Server",
        mixinStandardHelpOptions = true,
        version = "2.4")
public class URLFrontierServer implements Callable<Integer> {

    /**
     * Журнал событий запуска, работы и остановки сервера.
     */
    private static final org.slf4j.Logger LOG =
            LoggerFactory.getLogger(URLFrontierServer.class);

    /**
     * Порт, на котором сервер принимает gRPC-соединения.
     * По умолчанию используется 7071.
     */
    @Option(
            names = {"-p", "--port"},
            defaultValue = "7071",
            paramLabel = "NUM",
            description = "URL Frontier port (default to 7071)")
    int port;

    /**
     * Имя хоста, передаваемое реализации Frontier
     * для формирования её адреса.
     *
     * <p>Не определяет сетевой интерфейс,
     * на котором gRPC-сервер принимает соединения.</p>
     */
    // TODO(cli): Устранить конфликт краткого параметра -h
    //  со стандартной справкой Picocli.
    //  Сохранить --hostname и назначить ему свободный
    //  краткий параметр. Добавить проверку --help и -h.
    @Option(
            names = {"-h", "--hostname"},
            required = false,
            defaultValue = "localhost",
            paramLabel = "STRING",
            description = "hostname value to report - defaults to localhost")
    String host;

    /**
     * Путь к необязательному файлу настроек
     * в формате «ключ=значение».
     */
    @Option(
            names = {"-c", "--config"},
            required = false,
            paramLabel = "STRING",
            description = "key value configuration file")
    String config;

    /**
     * Порт HTTP-сервера метрик Prometheus.
     *
     * <p>Неположительное значение отключает
     * запуск отдельного сервера метрик.</p>
     */
    @Option(
            names = {"-s", "--prometheus_server"},
            paramLabel = "NUM",
            description = "Port number to use for Prometheus server")
    int prometheus_server;

    /**
     * Дополнительные параметры конфигурации,
     * переданные через командную строку.
     *
     * <p>Обрабатываются после файла настроек
     * и могут переопределять его значения.</p>
     */
    @Parameters List<String> positional;

    /**
     * Экземпляр gRPC-сервера, принимающего
     * запросы клиентов URLFrontier.
     */
    private Server server;

    /**
     * Созданная реализация службы Frontier.
     *
     * <p>В текущем варианте Onionall Brawler
     * допускается использование RocksDBService.</p>
     */
    private AbstractFrontierService service = null;

    /**
     * Необязательный HTTP-сервер для предоставления
     * метрик Prometheus.
     */
    private HTTPServer prometheus = null;

    /**
     * <b>Запускает URLFrontier как самостоятельное приложение.</b>
     *
     * <p>Создаёт команду Picocli, передаёт ей аргументы
     * запуска и завершает процесс с полученным кодом.</p>
     *
     * @param args аргументы командной строки
     */
    public static void main(String... args) {
        CommandLine cli = new CommandLine(new URLFrontierServer());
        int exitCode = cli.execute(args);
        System.exit(exitCode);
    }

    /**
     * <b>Выполняет команду запуска сервера.</b>
     *
     * <p>Делегирует инициализацию методу {@link #start()}.
     * При штатном завершении возвращает нулевой код,
     * при перехваченном исключении — отрицательный.</p>
     *
     * @return код завершения команды
     */
    @Override public Integer call() {
        try {
            start();
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
        return 0;
    }

    /**
     * <b>Создаёт, настраивает и запускает службы URLFrontier.</b>
     *
     * <p>Загружает конфигурацию, выбирает реализацию Frontier,
     * создаёт её экземпляр и регистрирует gRPC-службы
     * с проверкой полномочий через AdmissionRpc.</p>
     *
     * <p>При необходимости запускает сервер метрик,
     * регистрирует обработчик завершения процесса
     * и ожидает остановки gRPC-сервера.</p>
     *
     * <p>Не выполняет обработку URL самостоятельно:
     * запросы передаются соответствующим службам.</p>
     *
     * @throws Exception если не удалось создать или запустить
     *                   один из серверных компонентов
     */
    public void start() throws Exception {
        // TODO(lifecycle): Обеспечить освобождение всех уже
        //  созданных ресурсов при любой ошибке запуска.
        //  Исключить прямое завершение JVM из этапов
        //  инициализации; передавать ошибку вызывающему коду.
        //  Проверить отказ запуска gRPC и ошибочную конфигурацию.

        String implementationClassName = RocksDBService.class.getName();

        Map<String, String> configuration = new HashMap<>();

        // Do we want to expose Metrics via a Prometheus server?
        if (prometheus_server > 0) {
            /**
             * Register Prometheus collectors for garbage collection, memory pools, classloading,
             * and thread counts.
             */
            DefaultExports.initialize();

            LOG.info("Starting Prometheus server on port {}", prometheus_server);
            prometheus = new HTTPServer
                    .Builder()
                    .withPort(prometheus_server)
                    .build();
        }

        if (config != null) {
            try {
                // check that the file exists
                if (!new File(config).exists()) {
                    LOG.error("Config file not found : {}", config);
                    System.exit(-1);
                }
                // populate the config with the content of the file
                for (String line : Files.readAllLines(Paths.get(config))) {
                    line = line.trim();
                    if (line.startsWith("#")) continue;
                    addToConfig(configuration, line);
                }
            } catch (Exception e) {
                LOG.error("Exception caught when reading the configuration from {}", config, e);
                System.exit(-1);
            }
        }

        // override or add K/V with entries from args
        if (positional != null) {
            for (String l : positional) {
                addToConfig(configuration, l);
            }
        }

        // get the implementation class from the config if set
        implementationClassName =
                configuration.getOrDefault("implementation", implementationClassName);

        Class<?> implementationClass = Class.forName(implementationClassName);

        if (!AbstractFrontierService.class.isAssignableFrom(implementationClass)) {
            LOG.error(
                    "Implementation class {} does not extend AbstractFrontierService",
                    implementationClassName);
            System.exit(-1);
        }

        if (!configuration.isEmpty()) {
            try {
                // Реализация выбирается по имени класса из конфигурации.
                // Сначала предпочтителен конструктор с Map, позволяющий
                // передать настройки хранилища и планировщика.
                // При его отсутствии используется базовый конструктор.

                Constructor<?> c =
                        implementationClass.getConstructor(
                                Map.class, String.class, int.class
                        );
                c.setAccessible(true);
                service = (AbstractFrontierService) c.newInstance(configuration, host, port);
            } catch (NoSuchMethodException e) {
                // TODO(config): Проверить допустимость перехода
                //  к конструктору без Map при наличии настроек.
                //  Исключить молчаливую потерю параметров, влияющих
                //  на безопасность, хранение и ограничения Frontier.
                //  Добавить проверку конфигурации при запуске.
                LOG.info(
                        "Implementation {} does not have a constructor taking a Map as argument",
                        implementationClassName);
            } catch (Exception e) {
                LOG.error("Exception caught when initialising the service", e);
                System.exit(-1);
            }
        }

        if (service == null) {
            try {
                service = (AbstractFrontierService) implementationClass
                        .getDeclaredConstructor(String.class, int.class)
                        .newInstance(host, port);
            } catch (Exception e) {
                LOG.error("Exception caught when initialising the service", e);
                System.exit(-1);
            }
        }

        // Onionall использует постоянное состояние RocksDB
        // для защищённого допуска и резервирования URL.
        // Альтернативные реализации URLFrontier здесь не допускаются.
        if (!(service instanceof crawlercommons.urlfrontier.service.rocksdb.RocksDBService)) {
            throw new IllegalArgumentException("Admission candidate requires RocksDBService");
        }

        // Единый перехватчик проверяет полномочия до передачи
        // вызовов основной службе Frontier и службе Admission.
        // Правила разрешённых методов определяются AdmissionRpc.

        io.grpc.ServerInterceptor guard = crawlercommons.urlfrontier.service.rocksdb.AdmissionRpc.guard(System.getenv("ONIONALL_ADMISSION_TOKEN"));
        // TODO(security): Явно определить интерфейс прослушивания
        //  gRPC-сервера и требования к сетевой изоляции.
        //  Не считать параметр host ограничением доступа.
        //  Проверить отсутствие внешней доступности RPC
        //  при локальном развёртывании.
        this.server = ServerBuilder.forPort(port)
            .addService(io.grpc.ServerInterceptors.intercept(service, guard))
            .addService(io.grpc.ServerInterceptors.intercept(
                    crawlercommons.urlfrontier.service.rocksdb.AdmissionRpc.bind(
                        (crawlercommons.urlfrontier.service.rocksdb.RocksDBService) service
                    ),
                    guard
            ))
            .maxInboundMessageSize(131072).build();
        server.start();
        LOG.info(
                "Started URLFrontierServer [{}] on port {} as {}",
                service.getClass().getSimpleName(),
                server.getPort(),
                service.getAddress());

        registerShutdownHook();

        blockUntilShutdown();
    }

    /**
     * <b>Регистрирует обработчик завершения процесса.</b>
     *
     * <p>Добавляет поток, который при остановке JVM
     * вызывает {@link #stop()} и записывает сведения
     * об ошибках освобождения ресурсов.</p>
     */
    private void registerShutdownHook() {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> {
                    try {
                        stop();
                    } catch (Exception e) {
                        LOG.error(
                                "Error when trying to shutdown a lifecycle component: "
                                        + this.getClass().getName(),
                                e);
                    }
                }));
    }

    /**
     * <b>Останавливает серверные компоненты URLFrontier.</b>
     *
     * <p>Инициирует завершение gRPC-сервера,
     * закрывает службу Frontier при поддержке Closeable
     * и останавливает сервер метрик Prometheus.</p>
     *
     * <p>Текущая реализация не ожидает явно завершения
     * всех активных RPC-вызовов перед закрытием службы.
     * Этот аспект требует отдельной проверки.</p>
     */
    public void stop() {
        if (server != null) {
            // TODO(lifecycle): После запрета новых RPC-вызовов
            //  дождаться завершения активных запросов до закрытия
            //  службы и RocksDB. Предусмотреть ограниченное время
            //  ожидания, принудительную остановку и корректную
            //  обработку прерывания потока.
            //  Проверить отдельным тестом завершение сервера
            //  при незавершённой операции Admission.
            LOG.info("Shutting down URLFrontierServer on port {}", server.getPort());
            server.shutdown();
        }
        // terminate the service if possible
        if (service != null && Closeable.class.isAssignableFrom(service.getClass())) {
            LOG.info("Shutting down service {}", service.getClass().getSimpleName());
            try {
                service.close();
            } catch (IOException e) {
                LOG.error("Error when closing service: ", e);
            }
        }

        if (prometheus != null) {
            LOG.info("Shutting down Prometheus server on port {}", prometheus.getPort());
            prometheus.close();
        }
    }

    /**
     * <b>Ожидает завершения работы gRPC-сервера.</b>
     *
     * <p>Блокирует вызывающий поток до остановки сервера.
     * Не инициирует остановку самостоятельно.</p>
     *
     * @throws InterruptedException если ожидающий поток прерван
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * <b>Добавляет строковый параметр в конфигурацию.</b>
     *
     * <p>Удаляет пробелы по краям строки и разделяет её
     * по первому символу '='. Полученные ключ и значение
     * сохраняются в переданной карте настроек.</p>
     *
     * <p>Если разделитель отсутствует, текущая реализация
     * сохраняет ключ со значением null. Пустые строки
     * не изменяют конфигурацию.</p>
     *
     * @param config изменяемая карта настроек
     * @param line строка конфигурации
     */
    private static void addToConfig(
            final Map<String, String> config,
            String line
    ) {
        if (line == null || line.isEmpty()) return;

        line = line.trim();

        if (line.isEmpty()) return;

        // = to separate key from value
        int pos = line.indexOf('=');

        // TODO(config): Определить контракт строк конфигурации.
        //  Отклонять пустые ключи и отсутствие обязательного
        //  значения; выдавать понятную ошибку с номером строки.
        //  Добавить тесты для пустых строк, повторяющихся ключей,
        //  отсутствующего '=' и null-значений.
        if (pos == -1) {
            config.put(line, null);
            return;
        }
        String key = line.substring(0, pos).trim();
        String value = line.substring(pos + 1).trim();
        config.put(key, value);
    }
}
