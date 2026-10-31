package dev.onionall.brawler.m2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-process-only JDK17 resolver guard; does not change OS or production DNS. */
public final class TorDnsGuardProbe {
    public static void main(String[] args) throws Exception {
        boolean controlledHealth = args.length == 2 && args[1].equals("controlled-health");
        if ((args.length != 1 && !controlledHealth) || Runtime.version().feature() != 17) {
            throw new IllegalArgumentException("Requires JDK17, --add-opens java.base/java.net=ALL-UNNAMED and an evidence filename");
        }
        Field field = InetAddress.class.getDeclaredField("nameService");
        field.setAccessible(true);
        Object original = field.get(null);
        Class<?> service = Class.forName("java.net.InetAddress$NameService");
        AtomicInteger onionLookups = new AtomicInteger();
        Object guard = Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[]{service},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("lookupAllHostAddr") && arguments[0] instanceof String host
                            && host.toLowerCase(java.util.Locale.ROOT).replaceAll("\\.$", "").endsWith(".onion")) {
                        onionLookups.incrementAndGet();
                        throw new UnknownHostException("TEST_LOCAL_ONION_DNS_FORBIDDEN");
                    }
                    method.setAccessible(true);
                    try { return method.invoke(original, arguments); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
        Map<String, Object> evidence = new LinkedHashMap<>();
        field.set(null, guard);
        try {
            // Positive control proves interception works before checking application traffic.
            try { InetAddress.getAllByName("guard-control.onion"); }
            catch (UnknownHostException expected) { }
            boolean control = onionLookups.getAndSet(0) == 1;
            boolean testsPassed;
            if (controlledHealth) {
                evidence.put("scope", "JDK17 InetAddress resolver; real TorProtocol GET /health of the Compose-owned onion fixture");
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
                evidence.put("scope", "JDK17 InetAddress resolver in this fresh test JVM; controlled loopback SOCKS fixtures");
                evidence.put("tests_run", result.getClass().getMethod("getRunCount").invoke(result));
                evidence.put("test_failures", result.getClass().getMethod("getFailureCount").invoke(result));
            }
            evidence.put("guard_positive_control_passed", control);
            evidence.put("onion_lookup_attempts", onionLookups.get());
            evidence.put("passed", control && testsPassed && onionLookups.get() == 0);
            evidence.put("system_wide_dns_leak_absence_proven", false);
        } finally {
            field.set(null, original);
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(), evidence);
        System.out.println(new ObjectMapper().writeValueAsString(evidence));
        if (!Boolean.TRUE.equals(evidence.get("passed"))) throw new IllegalStateException("DNS guard or protocol regression failed");
    }
}
