package dev.onionall.brawler.m1;

import java.lang.reflect.*;
import java.util.*;
import crawlercommons.robots.SimpleRobotRules;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.protocol.ProtocolResponse;

/** Real 3.7.0 classes, in-memory protocol/collector; no HTTP or Docker required.
 * Reflection keeps the standalone compiler independent of provided Storm classes.
 */
public final class DelayRegressionCheck {
    static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException e) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    static Object construct(Class<?> type, Class<?>[] parameters, Object... args) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }
    static Object invoke(Object target, String name, Class<?>[] parameters, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
    static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    static Object noop(Class<?> type) {
        return proxy(type, (p, m, a) -> {
            if (m.getReturnType().isInterface()) return noop(m.getReturnType());
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == int.class) return 0;
            return null;
        });
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Class<?> configType = Class.forName("org.apache.storm.Config");
        Object config = construct(configType, new Class<?>[0]);
        ((Map<String, Object>) config).put("fetcher.server.delay", 2.0);
        Class<?> tupleType = Class.forName("org.apache.storm.tuple.Tuple");
        Class<?> queuesType = Class.forName("org.apache.stormcrawler.bolt.FetcherBolt$FetchItemQueues");
        Class<?> itemType = Class.forName("org.apache.stormcrawler.bolt.FetcherBolt$FetchItem");
        Object queues = construct(queuesType, new Class<?>[]{configType}, config);
        Object queue = invoke(queues, "getFetchItemQueue", new Class<?>[]{String.class, Metadata.class}, "fixture", new Metadata());
        Object item = construct(itemType, new Class<?>[]{String.class, tupleType, String.class}, "http://fixture:8080/", null, "fixture");
        invoke(queue, "addFetchItem", new Class<?>[]{itemType}, item);
        invoke(queue, "getFetchItem", new Class<?>[0]);
        invoke(queues, "finishFetchItem", new Class<?>[]{itemType, boolean.class}, item, false);
        if (((java.util.concurrent.atomic.AtomicLong) field(queue.getClass(), "nextFetchTime").get(queue)).get() <= System.currentTimeMillis())
            throw new AssertionError("Expected outstanding cooldown");
        invoke(queues, "getFetchItem", new Class<?>[0]); // Reaps empty queue despite cooldown.
        Object recreated = invoke(queues, "getFetchItemQueue", new Class<?>[]{String.class, Metadata.class}, "fixture", new Metadata());
        if (recreated == queue) throw new AssertionError("Empty-queue regression was not reproduced");
        if (((java.util.concurrent.atomic.AtomicLong) field(recreated.getClass(), "nextFetchTime").get(recreated)).get() > System.currentTimeMillis())
            throw new AssertionError("Recreated queue unexpectedly retained the cooldown");
        System.out.println("Reproduced FetcherBolt cooldown loss on empty-queue recreation");

        Object bolt = construct(Class.forName("org.apache.stormcrawler.bolt.SimpleFetcherBolt"), new Class<?>[0]);
        for (String name : new String[]{"eventCounter", "averagedMetrics", "perSecMetrics"}) {
            Field f = field(bolt.getClass(), name); f.set(bolt, noop(f.getType()));
        }
        field(bolt.getClass(), "conf").set(bolt, config);
        field(bolt.getClass(), "queueMode").set(bolt, "byHost");
        field(bolt.getClass(), "crawlDelay").set(bolt, 2000L);
        field(bolt.getClass(), "crawlDelayForce").set(bolt, true);
        field(bolt.getClass(), "maxThrottleSleepMilliSec").set(bolt, -1L);
        SimpleRobotRules rules = new SimpleRobotRules();
        rules.addRule("/blocked", false); rules.addRule("/", true); rules.sortRules(); rules.setCrawlDelay(2000);
        List<Long> starts = new ArrayList<>(), ends = new ArrayList<>();
        List<String> fetched = new ArrayList<>();
        Class<?> protocolType = Class.forName("org.apache.stormcrawler.protocol.Protocol");
        Object protocol = proxy(protocolType, (p, m, a) -> {
            if (m.getName().equals("getRobotRules")) return rules;
            if (m.getName().equals("getProtocolOutput")) {
                if (((String) a[0]).endsWith("/transport")) throw new java.io.IOException("Synthetic transport failure");
                fetched.add((String) a[0]); starts.add(System.nanoTime());
                var response = new ProtocolResponse(new byte[0], ((String) a[0]).endsWith("/missing") ? 404 : 200, new Metadata());
                ends.add(System.nanoTime()); return response;
            }
            return null;
        });
        Object factory = construct(Class.forName("org.apache.stormcrawler.protocol.ProtocolFactory"), new Class<?>[0]);
        Object protocols = Array.newInstance(protocolType, 1); Array.set(protocols, 0, protocol);
        ((Map<String, Object>) field(factory.getClass(), "cache").get(factory)).put("http", protocols);
        field(bolt.getClass(), "protocolFactory").set(bolt, factory);
        List<String> statuses = new ArrayList<>();
        List<String> transportStatuses = new ArrayList<>();
        Class<?> collectorInterface = Class.forName("org.apache.storm.task.IOutputCollector");
        Object collector = proxy(collectorInterface, (p, m, a) -> {
            if (m.getName().equals("reportError") || m.getName().equals("fail")) throw new AssertionError("Unexpected bolt error");
            if (m.getName().equals("emit")) {
                List<?> values = (List<?>) a[a.length - 1];
                if (values.size() == 3 && values.get(0).toString().endsWith("/missing")) statuses.add(values.get(2).toString());
                if (values.size() == 3 && values.get(0).toString().endsWith("/transport")) {
                    transportStatuses.add(values.get(2).toString());
                    if (((Metadata) values.get(1)).getFirstValue("fetch.exception") == null)
                        throw new AssertionError("Transport exception metadata lost");
                }
                return List.of();
            }
            return null;
        });
        field(bolt.getClass(), "collector").set(bolt, construct(Class.forName("org.apache.storm.task.OutputCollector"), new Class<?>[]{collectorInterface}, collector));
        for (String path : new String[]{"/", "/a", "/blocked", "/missing", "/transport"}) {
            Object tuple = proxy(tupleType, (p, m, a) -> {
                if (m.getName().equals("getStringByField")) return "http://fixture:8080" + path;
                if (m.getName().equals("contains")) return false;
                return null;
            });
            invoke(bolt, "execute", new Class<?>[]{tupleType}, tuple);
            if (path.equals("/")) Thread.sleep(1000); // Frontier idle gap shorter than cooldown.
        }
        if (fetched.size() != 3 || fetched.stream().anyMatch(url -> url.endsWith("/blocked")) || !statuses.contains("FETCH_ERROR"))
            throw new AssertionError("Robots/404 behavior changed: " + fetched + statuses);
        if (!transportStatuses.equals(List.of("FETCH_ERROR"))) throw new AssertionError("Transport status lost: " + transportStatuses);
        for (int i = 1; i < starts.size(); i++) {
            double gap = (starts.get(i) - ends.get(i - 1)) / 1e9;
            if (gap < 1.8) throw new AssertionError("SimpleFetcherBolt lost cooldown: " + gap);
            System.out.println("SimpleFetcherBolt document gap: " + gap);
        }
        System.out.println("PASS: idle gap, intervening robots denial, HTTP 404 and 2s document pacing");
    }
}
