package dev.onionall.brawler.m2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-process-only Java25 resolver SPI guard; does not change OS or production DNS. */
public final class TorDnsGuardProbe {
    private static final AtomicInteger onionLookups = new AtomicInteger();

    /** Loaded only when the separate test provider service JAR is on the classpath. */
    public static final class GuardProvider extends InetAddressResolverProvider {
        @Override public String name() { return "onionall-test-guard"; }
        @Override public InetAddressResolver get(Configuration configuration) {
            InetAddressResolver delegate = configuration.builtinResolver();
            return new InetAddressResolver() {
                @Override public java.util.stream.Stream<InetAddress> lookupByName(
                        String host, LookupPolicy policy) throws UnknownHostException {
                    String normalized = host.toLowerCase(java.util.Locale.ROOT);
                    if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
                    if (normalized.endsWith(".onion")) {
                        onionLookups.incrementAndGet();
                        throw new UnknownHostException("TEST_LOCAL_ONION_DNS_FORBIDDEN");
                    }
                    return delegate.lookupByName(host, policy);
                }
                @Override public String lookupByAddress(byte[] address) throws UnknownHostException {
                    return delegate.lookupByAddress(address);
                }
            };
        }
    }

    public static void main(String[] args) throws Exception {
        boolean controlledHealth = args.length == 2 && args[1].equals("controlled-health");
        if ((args.length != 1 && !controlledHealth) || Runtime.version().feature() != 25) {
            throw new IllegalArgumentException("Requires Java25, test resolver provider JAR and an evidence filename");
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        // Positive control proves interception works before checking application traffic.
        try { InetAddress.getAllByName("guard-control.onion"); }
        catch (UnknownHostException expected) { }
        try { InetAddress.getAllByName("GUARD-TRAILING.onion."); }
        catch (UnknownHostException expected) { }
        boolean control = onionLookups.getAndSet(0) == 2;
        boolean testsPassed;
        if (controlledHealth) {
            evidence.put("scope", "Java25 InetAddress resolver SPI; real TorProtocol GET /health of the Compose-owned onion fixture");
            try {
                Class.forName("dev.onionall.brawler.m2.TorScenarioProbe").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[]{"safe_retry_after_tor_return"});
                testsPassed = true;
            } catch (InvocationTargetException error) {
                testsPassed = false;
                evidence.put("probe_error_type", error.getCause().getClass().getName());
            }
        } else {
            Class<?> junit = Class.forName("org.junit.runner.JUnitCore");
            Object result = junit.getMethod("runClasses", Class[].class).invoke(null,
                    (Object) new Class<?>[]{Class.forName("dev.onionall.brawler.tor.TorProtocolTest"),
                            Class.forName("dev.onionall.brawler.tor.TorPolicyTest")});
            testsPassed = (boolean) result.getClass().getMethod("wasSuccessful").invoke(result);
            evidence.put("scope", "Java25 InetAddress resolver SPI in this fresh test JVM; controlled loopback SOCKS fixtures");
            evidence.put("tests_run", result.getClass().getMethod("getRunCount").invoke(result));
            evidence.put("test_failures", result.getClass().getMethod("getFailureCount").invoke(result));
        }
        evidence.put("guard_positive_control_passed", control);
        evidence.put("onion_lookup_attempts", onionLookups.get());
        evidence.put("passed", control && testsPassed && onionLookups.get() == 0);
        evidence.put("system_wide_dns_leak_absence_proven", false);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), evidence);
        System.out.println(new ObjectMapper().writeValueAsString(evidence));
        if (!Boolean.TRUE.equals(evidence.get("passed"))) throw new IllegalStateException("DNS guard or protocol regression failed");
    }
}
