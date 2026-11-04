package dev.onionall.brawler.archive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.onionall.brawler.tor.TorPolicy;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.jsoup.Jsoup;

/** Local document catalog, never a crawl scheduler or Frontier admission authority.
 * Embedded H2 deliberately has one process owner; no catalog network listener.
 * Published immutable blobs precede catalog commit; failed commits can leave safe
 * unreferenced blobs, never completed snapshots referring to partial files.
 */
public final class ArchiveStore implements AutoCloseable {
    public static final int MAX_HTML_BYTES = 131072;
    public static final long MAX_BLOB_BYTES = 64L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root, objects;
    private final Connection db;

    public ArchiveStore(Path location) throws IOException {
        root = location.toAbsolutePath().normalize();
        if (root.toString().contains(";") || root.toString().contains("\n") || root.toString().contains("\r"))
            throw new IOException("Unsafe catalog path");
        noSymlinks(root);
        Files.createDirectories(root);
        objects = root.resolve("objects");
        noSymlinks(objects);
        Files.createDirectories(objects);
        for (String name : List.of("catalog.mv.db", "catalog.lock.db", "catalog.trace.db"))
            noSymlinks(root.resolve(name));
        try {
            Class.forName("org.h2.Driver");
            db = DriverManager.getConnection("jdbc:h2:file:" + root.resolve("catalog")
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;AUTO_SERVER=FALSE", "sa", "");
            try { migrate(); }
            catch (SQLException | IOException e) { try { db.close(); } catch(SQLException close) { e.addSuppressed(close); } throw e; }
        } catch (SQLException | ClassNotFoundException e) { throw failure(e); }
    }

    private static void noSymlinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Archive paths must not contain symbolic links");
    }
    private static IOException failure(Exception e) { return new IOException("Archive operation failed: " + e.getClass().getSimpleName(), e); }
    private void migrate() throws SQLException, IOException {
        try (Statement statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY)");
            try (ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                rs.next(); if (rs.getInt(1) > 2) throw new IOException("Archive schema is newer than this application");
            }
            statement.execute("CREATE TABLE IF NOT EXISTS catalog_guard (id INT PRIMARY KEY)");
            statement.execute("MERGE INTO catalog_guard KEY(id) VALUES (1)");
            statement.execute("CREATE TABLE IF NOT EXISTS crawl_plans (id VARCHAR(36) PRIMARY KEY, seeds_json CLOB NOT NULL, origin VARCHAR(256) NOT NULL, max_depth INT NOT NULL CHECK(max_depth BETWEEN 0 AND 5), max_pages INT NOT NULL CHECK(max_pages BETWEEN 1 AND 100), provider VARCHAR(256) NOT NULL, approved BOOLEAN NOT NULL DEFAULT FALSE, created_at VARCHAR(40) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS pages (id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES crawl_plans(id), canonical_url VARCHAR(2048) NOT NULL, latest_snapshot VARCHAR(36), last_seen VARCHAR(40), UNIQUE(job_id,canonical_url))");
            statement.execute("CREATE TABLE IF NOT EXISTS snapshots (id VARCHAR(36) PRIMARY KEY, page_id VARCHAR(36) NOT NULL REFERENCES pages(id), content_hash VARCHAR(64) NOT NULL, content_size INT NOT NULL CHECK(content_size BETWEEN 1 AND 131072), http_status INT NOT NULL CHECK(http_status=0 OR http_status BETWEEN 200 AND 299), content_type VARCHAR(64) NOT NULL, fetched_at VARCHAR(40), recorded_at VARCHAR(40) NOT NULL, source_kind VARCHAR(16) NOT NULL, discovery_depth INT NOT NULL CHECK(discovery_depth BETWEEN 0 AND 5), source_url VARCHAR(2048) NOT NULL, title VARCHAR(2048) NOT NULL, extracted_text CLOB NOT NULL, UNIQUE(page_id,content_hash,source_kind))");
            statement.execute("CREATE TABLE IF NOT EXISTS page_links (snapshot_id VARCHAR(36) NOT NULL REFERENCES snapshots(id), ordinal INT NOT NULL, source_url VARCHAR(2048) NOT NULL, target_url VARCHAR(2048) NOT NULL, disposition VARCHAR(64) NOT NULL, PRIMARY KEY(snapshot_id,ordinal))");
            statement.execute("CREATE TABLE IF NOT EXISTS admission_observations (job_id VARCHAR(36) PRIMARY KEY REFERENCES crawl_plans(id), observation_json CLOB NOT NULL, observed_at VARCHAR(40) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS failures (id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES crawl_plans(id), canonical_url VARCHAR(2048) NOT NULL, category VARCHAR(64) NOT NULL, occurred_at VARCHAR(40) NOT NULL, UNIQUE(job_id,canonical_url,category))");
            statement.execute("CREATE INDEX IF NOT EXISTS pages_job_idx ON pages(job_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS snapshots_page_idx ON snapshots(page_id,recorded_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS failures_job_idx ON failures(job_id,occurred_at)");
            // H2 DDL commits independently: each migration step must be restart-idempotent.
            // Legacy edges have no original discovery clock. Retain their snapshot's
            // recorded time explicitly as an approximation instead of fabricating one.
            statement.execute("ALTER TABLE page_links ADD COLUMN IF NOT EXISTS discovered_at VARCHAR(40)");
            statement.execute("ALTER TABLE page_links ADD COLUMN IF NOT EXISTS discovery_time_basis VARCHAR(32)");
            statement.execute("UPDATE page_links l SET discovered_at=(SELECT s.recorded_at FROM snapshots s WHERE s.id=l.snapshot_id), discovery_time_basis='SNAPSHOT_RECORDED' WHERE discovered_at IS NULL");
            statement.execute("CREATE INDEX IF NOT EXISTS links_target_idx ON page_links(target_url)");
            // Additive, independently versioned observation extension. CoreV2 and
            // legacy tables stay unchanged so previous binaries can still read them.
            statement.execute("CREATE TABLE IF NOT EXISTS failure_observations_v1 (observation_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES crawl_plans(id), failure_id VARCHAR(36) NOT NULL REFERENCES failures(id), canonical_url VARCHAR(2048) NOT NULL, category VARCHAR(64) NOT NULL, observed_at VARCHAR(40) NOT NULL, phase VARCHAR(16) NOT NULL, source VARCHAR(32) NOT NULL, http_status INT CHECK(http_status IS NULL OR http_status BETWEEN 100 AND 599), retryable BOOLEAN, terminal_intent BOOLEAN, next_due_epoch BIGINT CHECK(next_due_epoch IS NULL OR next_due_epoch>=0), transport_category VARCHAR(32) NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS failure_observations_job_seq_v1 ON failure_observations_v1(job_id,observation_seq)");
            statement.execute("MERGE INTO schema_version KEY(version) VALUES(2)");
        }
    }
    private void capacity() throws IOException {
        Path database=root.resolve("catalog.mv.db");noSymlinks(database);
        if(Files.exists(database) && Files.size(database)>128L*1024*1024)
            throw new IOException("Catalog128MiB write limit reached; no automatic deletion");
    }
    private static String identifier(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IOException("Invalid archive identifier");
        return id;
    }
    private static int bound(int value) throws IOException {
        if (value < 1 || value > 100) throw new IOException("Require result limit1..100");
        return value;
    }
    private static String bounded(String value, int max, String field) throws IOException {
        if (value == null || value.length() > max || value.chars().anyMatch(c -> c == 0)) throw new IOException("Invalid " + field);
        return value;
    }
    private static TorPolicy policy(String host) throws IOException {
        if (!TorPolicy.validOnion(host)) throw new IOException("Require valid onion v3 hostname");
        return new TorPolicy(Map.of("tor.mode", "ONLY", "tor.socks.host", "127.0.0.1", "tor.socks.port", 9050, "tor.allowed.hosts", List.of(host)));
    }
    private static String normalize(String url, String origin) throws IOException {
        bounded(url, 2048, "URL");
        String host;
        try { host = URI.create(origin == null ? url : origin).getHost(); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid URL", e); }
        String result = policy(host == null ? "" : host).normalize(url);
        if (origin != null && !origin.equals(origin(result))) throw new IOException("Origin is outside approved same-host scope");
        URI canonical = URI.create(result);
        if (canonical.getPort() != -1) result = origin(result) + canonical.getRawPath()
            + (canonical.getRawQuery() == null ? "" : "?" + canonical.getRawQuery());
        return result;
    }
    private static String origin(String url) { URI u = URI.create(url); return u.getScheme() + "://" + u.getHost(); }

    public synchronized Map<String,Object> createPlan(List<String> seeds, int depth, int maxPages, String provider) throws IOException {
        capacity();
        if (seeds == null || seeds.isEmpty() || seeds.size() > 100 || depth < 0 || depth > 5 || maxPages < 1 || maxPages > 100)
            throw new IOException("Require1..100 seeds/pages and depth0..5");
        provider = bounded(provider == null ? "user" : provider, 256, "provider attribution");
        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        String allowed = null;
        for (String seed : seeds) {
            String normalized = normalize(seed, allowed);
            if (allowed == null) allowed = origin(normalized);
            canonical.add(normalized);
        }
        if (canonical.size() > maxPages) throw new IOException("Seeds exceed page budget");
        String id = UUID.randomUUID().toString();
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO crawl_plans(id,seeds_json,origin,max_depth,max_pages,provider,created_at) VALUES(?,?,?,?,?,?,?)")) {
            insert.setString(1,id); insert.setString(2,JSON.writeValueAsString(canonical)); insert.setString(3,allowed);
            insert.setInt(4,depth); insert.setInt(5,maxPages); insert.setString(6,provider); insert.setString(7,Instant.now().toString()); insert.executeUpdate();
            return plan(id);
        } catch (SQLException e) { throw failure(e); }
    }
    /** Live catalog keyset, not a scheduler or a frozen membership snapshot. */
    public synchronized Map<String,Object> plans(String after,int limit) throws IOException {
        bound(limit);if(after!=null)identifier(after);
        List<String> ids=new ArrayList<>();
        try(var query=db.prepareStatement("SELECT id FROM crawl_plans WHERE id>? ORDER BY id LIMIT ?")) {
            query.setString(1,after==null?"":after);query.setInt(2,limit+1);
            try(var rows=query.executeQuery()){while(rows.next())ids.add(rows.getString(1));}
        }catch(SQLException e){throw failure(e);}
        boolean more=ids.size()>limit;if(more)ids.removeLast();
        List<Map<String,Object>> plans=new ArrayList<>();for(String id:ids)plans.add(plan(id));
        Map<String,Object> result=new LinkedHashMap<>();result.put("plan_list_version",1);result.put("plans",plans);
        result.put("limit",limit);result.put("truncated",more);result.put("next_cursor",more?ids.getLast():null);
        result.put("order","plan_id");result.put("membership_snapshot",false);return result;
    }
    public synchronized Map<String,Object> plan(String id) throws IOException {
        identifier(id);
        try (PreparedStatement query = db.prepareStatement("SELECT * FROM crawl_plans WHERE id=?")) {
            query.setString(1,id);
            try (ResultSet rs=query.executeQuery()) {
                if (!rs.next()) throw new IOException("Unknown crawl plan");
                Map<String,Object> result = new LinkedHashMap<>();
                result.put("plan_id",id); result.put("job_id",id); result.put("seeds",JSON.readValue(rs.getString("seeds_json"), new TypeReference<List<String>>(){}));
                result.put("origin",rs.getString("origin")); result.put("scope","same-host"); result.put("depth",rs.getInt("max_depth"));
                result.put("max_pages",rs.getInt("max_pages")); result.put("provider",rs.getString("provider")); result.put("approved",rs.getBoolean("approved"));
                result.put("state",rs.getBoolean("approved") ? "APPROVED" : "DRAFT"); result.put("admission","UNOBSERVED");
                result.put("admission_basis","CATALOG_PLAN_ONLY"); result.put("created_at",rs.getString("created_at"));
                return result;
            }
        } catch (SQLException e) { throw failure(e); }
    }
    public synchronized Map<String,Object> approvePlan(String id) throws IOException {
        plan(id);
        try (PreparedStatement update=db.prepareStatement("UPDATE crawl_plans SET approved=TRUE WHERE id=?")) {
            update.setString(1,id); update.executeUpdate(); return plan(id);
        } catch (SQLException e) { throw failure(e); }
    }
    /** A cached observation only: Frontier remains authoritative after any unknown RPC outcome. */
    public synchronized void recordAdmission(String id, Map<String,Object> observation) throws IOException {
        Map<String,Object> plan = plan(id);
        if (!Boolean.TRUE.equals(plan.get("approved"))) throw new IOException("Plan is not approved");
        if (observation.keySet().stream().anyMatch(k -> Set.of("approved","origin","seeds","max_pages","depth","job_id","scope").contains(k)))
            throw new IOException("Admission observations cannot change plan authority");
        if (observation.containsKey("session_id") && !id.equals(observation.get("session_id")))
            throw new IOException("Admission session mismatch");
        writeObservation(id, observation, null);
    }
    /** Bounded observational audit in existing schemaV2 JSON; never scheduling authority. */
    public synchronized void recordJobStatus(String id, Map<String,Object> status) throws IOException {
        plan(id);
        if (!Objects.equals(status.get("plan_id"), id) || !Objects.equals(status.get("job_status_version"), 1))
            throw new IOException("Invalid job status observation");
        writeObservation(id, Map.of(), status);
    }
    private void writeObservation(String id, Map<String,Object> update, Map<String,Object> status) throws IOException {
        try {
            db.setAutoCommit(false);
            try (var lock = db.prepareStatement("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE"); var row = lock.executeQuery()) { row.next(); }
            Map<String,Object> old = new LinkedHashMap<>(admissionObservation(id));
            Object admissionClock = old.get("admission_observed_at");
            old.remove("admission_observed_at"); old.remove("admission_authority");
            Map<String,Object> value = status == null ? new LinkedHashMap<>(update) : new LinkedHashMap<>(old);
            if (status == null) {
                for (String key : List.of("job_status", "status_transitions", "status_transitions_dropped"))
                    if (old.containsKey(key)) value.put(key, old.get(key));
            } else {
                List<Object> history = new ArrayList<>();
                if (old.get("status_transitions") instanceof List<?> entries) history.addAll(entries);
                else if (old.containsKey("status_transitions")) throw new IOException("Malformed status audit");
                Map<?,?> previous = old.get("job_status") instanceof Map<?,?> m ? m : Map.of();
                if (List.of("job_state", "completion", "state_basis").stream().anyMatch(k -> !Objects.equals(previous.get(k), status.get(k)))) {
                    Map<String,Object> event = new LinkedHashMap<>();
                    for (String key : List.of("job_state", "completion", "state_basis", "observed_at")) event.put(key, status.get(key));
                    history.add(event);
                }
                long dropped = old.get("status_transitions_dropped") instanceof Number n ? n.longValue() : 0;
                if (dropped < 0 || dropped > 1_000_000_000) throw new IOException("Invalid status audit bound");
                while (history.size() > 32) { history.removeFirst(); dropped++; }
                value.put("job_status", status); value.put("status_transitions", history); value.put("status_transitions_dropped", dropped);
            }
            String encoded = JSON.writeValueAsString(value);
            if (encoded.length() > 16384) throw new IOException("Admission observation exceeds bound");
            try (var st = db.prepareStatement("MERGE INTO admission_observations(job_id,observation_json,observed_at) KEY(job_id) VALUES(?,?,?)")) {
                st.setString(1,id);st.setString(2,encoded);
                st.setString(3,status!=null && admissionClock instanceof String clock ? clock : Instant.now().toString());st.executeUpdate();
            }
            db.commit();
        } catch (SQLException | IOException e) {
            try { db.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e instanceof IOException io ? io : failure((SQLException)e);
        } finally { try { db.setAutoCommit(true); } catch (SQLException e) { throw failure(e); } }
    }
    /** Archive-wide legacy launch evidence; no scheduler authority or metadata migration. */
    public synchronized boolean hasExecutionHistory() throws IOException {
        try(var statement=db.prepareStatement("SELECT observation_json FROM admission_observations");var rows=statement.executeQuery()) {
            while(rows.next()) {
                Map<?,?> value=JSON.readValue(rows.getString(1),Map.class);
                if(value.containsKey("worker_pid")||value.containsKey("worker_owner_pid")||value.containsKey("runtime")||"COMMITTED".equals(value.get("admission")))return true;
            }
            return false;
        }catch(SQLException e){throw new IOException("Execution ownership history unavailable",e);}
    }
    public synchronized Map<String,Object> admissionObservation(String id) throws IOException {
        identifier(id);
        try(PreparedStatement st=db.prepareStatement("SELECT observation_json,observed_at FROM admission_observations WHERE job_id=?")) {
            st.setString(1,id);
            try(ResultSet rs=st.executeQuery()) {
                if(!rs.next()) return Map.of();
                Map<String,Object> value=JSON.readValue(rs.getString(1),new TypeReference<Map<String,Object>>(){});
                value.put("admission_observed_at",rs.getString(2));value.put("admission_authority","URLFrontier");
                return value;
            }
        } catch(SQLException e) { throw failure(e); }
    }
    public synchronized Map<String,Object> job(String id) throws IOException {
        Map<String,Object> result = new LinkedHashMap<>(plan(id));
        result.putAll(admissionObservation(id));
        try {
            result.put("archived_pages", count("SELECT COUNT(*) FROM pages WHERE job_id=?", id));
            result.put("snapshots",count("SELECT COUNT(*) FROM snapshots s JOIN pages p ON p.id=s.page_id WHERE p.job_id=?",id));
            result.put("failures",count("SELECT COUNT(*) FROM failures WHERE job_id=?",id));
            List<Map<String,Object>> failures=new ArrayList<>();
            try(PreparedStatement query=db.prepareStatement("SELECT id,canonical_url,category,occurred_at FROM failures WHERE job_id=? ORDER BY occurred_at DESC,id LIMIT 100")) {
                query.setString(1,id);try(ResultSet rs=query.executeQuery()){while(rs.next())failures.add(Map.of("failure_id",rs.getString(1),"url",rs.getString(2),"category",rs.getString(3),"occurred_at",rs.getString(4)));}
            }
            result.put("failure_records",failures);
            result.put("failure_count_basis","LEGACY_UNIQUE_URL_CATEGORY");
            result.put("failure_observation_count",count("SELECT COUNT(*) FROM failure_observations_v1 WHERE job_id=?",id));
            result.put("fetch_attempt_count",null);
            try(var query=db.prepareStatement("SELECT * FROM failure_observations_v1 WHERE job_id=? ORDER BY observation_seq DESC LIMIT 1")) {
                query.setString(1,id);try(var row=query.executeQuery()){result.put("latest_failure_observation",row.next()?failureObservationRow(row):null);}
            }
            result.put("scheduling_owner","URLFrontier"); result.put("archive_owner","local catalog"); return result;
        } catch (SQLException e) { throw failure(e); }
    }
    private long count(String sql, String id) throws SQLException {
        try (PreparedStatement query=db.prepareStatement(sql)) { query.setString(1,id); try(ResultSet rs=query.executeQuery()) { rs.next(); return rs.getLong(1); } }
    }
    private static String hash(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw failure(e); }
    }
    private void publish(String digest, byte[] html) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Archive cancelled");
        noSymlinks(objects);
        Path destination=objects.resolve(digest+".html"); noSymlinks(destination);
        if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(destination,LinkOption.NOFOLLOW_LINKS) || Files.size(destination)!=html.length || !hash(Files.readAllBytes(destination)).equals(digest))
                throw new IOException("Existing content-addressed snapshot is inconsistent");
            return;
        }
        long used=0;
        try (DirectoryStream<Path> files=Files.newDirectoryStream(objects)) {
            for(Path file:files) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected archive object");
                used=Math.addExact(used, Files.size(file));
            }
        }
        if (used + html.length > MAX_BLOB_BYTES) throw new IOException("Archive64MiB storage limit reached; no automatic deletion");
        Path temporary=Files.createTempFile(objects,".publish-",".tmp");
        try {
            try(FileChannel output=FileChannel.open(temporary,StandardOpenOption.WRITE)) { ByteBuffer data=ByteBuffer.wrap(html); while(data.hasRemaining()) output.write(data); output.force(true); }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Archive cancelled");
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
    private Map<String,Object> approved(String id) throws IOException {
        Map<String,Object> plan=plan(id);
        if (!Boolean.TRUE.equals(plan.get("approved"))) throw new IOException("Crawl plan requires explicit approval");
        return plan;
    }
    public synchronized Map<String,Object> archive(String jobId,String url,int depth,String source,int status,String contentType,byte[] html,List<Map<String,Object>> links) throws IOException {
        return archiveInternal(jobId,url,depth,source,status,contentType,html,links,false);
    }
    public synchronized Map<String,Object> importHtml(String jobId,String url,byte[] html,List<Map<String,Object>> links) throws IOException {
        return archiveInternal(jobId,url,0,url,0,"text/html",html,links,true);
    }
    private Map<String,Object> archiveInternal(String jobId,String url,int depth,String source,int status,String contentType,byte[] html,List<Map<String,Object>> links,boolean manual) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("Archive cancelled");
        capacity();
        Map<String,Object> plan=approved(jobId);
        String allowed=(String)plan.get("origin"); url=normalize(url,allowed);
        if (depth<0 || depth>(Integer)plan.get("depth")) throw new IOException("Discovery depth exceeds approved plan");
        if (source==null || source.isBlank()) source=url;
        source=normalize(source,allowed);
        if((manual ? status!=0 : status<200 || status>299) || contentType==null || !contentType.split(";",2)[0].trim().equalsIgnoreCase("text/html") || html==null || html.length<1 || html.length>MAX_HTML_BYTES)
            throw new IOException("Archive requires bounded successful HTML");
        if(links==null || links.size()>100) throw new IOException("Archive link limit100");
        List<Map<String,String>> safeLinks=new ArrayList<>();
        for(Map<String,Object> link:links) {
            String target=Objects.toString(link.getOrDefault("target_url",link.getOrDefault("target","")),"");
            String disposition=Objects.toString(link.getOrDefault("disposition",link.getOrDefault("result","OBSERVED")),"OBSERVED");
            bounded(target,2048,"link target");
            if(!disposition.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("Invalid link disposition");
            String observed = Objects.toString(link.getOrDefault("discovered_at", link.getOrDefault("discoveredAt", "")), "");
            String timeBasis = observed.isEmpty() ? "ARCHIVE_OBSERVED" : "DISCOVERY_REPORTED";
            if (observed.isEmpty()) observed = Instant.now().toString();
            else {
                bounded(observed,40,"link discovery time");
                try { observed = Instant.parse(observed).toString(); }
                catch(java.time.format.DateTimeParseException e) { throw new IOException("Invalid link discovery time",e); }
            }
            safeLinks.add(Map.of("source_url",url,"target_url",target,"disposition",disposition,
                "discovered_at",observed,"discovery_time_basis",timeBasis));
        }
        String digest=hash(html), pageId=null, snapshotId=UUID.randomUUID().toString();
        var document=Jsoup.parse(new String(html,java.nio.charset.StandardCharsets.UTF_8),url);
        String title=document.title(); if(title.length()>2048) title=title.substring(0,2048);
        String text=document.text(); if(text.length()>131072) text=text.substring(0,131072);
        try {
            db.setAutoCommit(false);
            try (PreparedStatement lock=db.prepareStatement("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE")) { try(ResultSet rows=lock.executeQuery()) { rows.next(); } }
            try(PreparedStatement query=db.prepareStatement("SELECT id FROM pages WHERE job_id=? AND canonical_url=?")) { query.setString(1,jobId);query.setString(2,url);try(ResultSet rs=query.executeQuery()){if(rs.next())pageId=rs.getString(1);} }
            if(pageId==null) {
                if(count("SELECT COUNT(*) FROM pages WHERE job_id=?",jobId)>=(Integer)plan.get("max_pages")) throw new IOException("Archive page limit reached (not a Frontier admission decision)");
                pageId=UUID.randomUUID().toString();
                try(PreparedStatement insert=db.prepareStatement("INSERT INTO pages(id,job_id,canonical_url) VALUES(?,?,?)")){insert.setString(1,pageId);insert.setString(2,jobId);insert.setString(3,url);insert.executeUpdate();}
            }
            try(PreparedStatement query=db.prepareStatement("SELECT id FROM snapshots WHERE page_id=? AND content_hash=? AND source_kind=?")) { query.setString(1,pageId);query.setString(2,digest);query.setString(3,manual?"MANUAL_IMPORT":"CRAWLER");try(ResultSet rs=query.executeQuery()){if(rs.next()){
                try(PreparedStatement latest=db.prepareStatement("UPDATE pages SET latest_snapshot=?,last_seen=? WHERE id=?")){
                    latest.setString(1,rs.getString(1));latest.setString(2,Instant.now().toString());latest.setString(3,pageId);latest.executeUpdate();
                }
                db.commit();db.setAutoCommit(true);return page(pageId);
            }} }
            publish(digest,html);
            if(Thread.currentThread().isInterrupted())throw new IOException("Archive cancelled");
            try(PreparedStatement insert=db.prepareStatement("INSERT INTO snapshots(id,page_id,content_hash,content_size,http_status,content_type,fetched_at,recorded_at,source_kind,discovery_depth,source_url,title,extracted_text) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1,snapshotId);insert.setString(2,pageId);insert.setString(3,digest);insert.setInt(4,html.length);insert.setInt(5,status);insert.setString(6,"text/html");insert.setString(7,manual?null:Instant.now().toString());insert.setString(8,Instant.now().toString());insert.setString(9,manual?"MANUAL_IMPORT":"CRAWLER");insert.setInt(10,depth);insert.setString(11,source);insert.setString(12,title);insert.setString(13,text);insert.executeUpdate();
            }
            int ordinal=0;
            try(PreparedStatement insert=db.prepareStatement("INSERT INTO page_links(snapshot_id,ordinal,source_url,target_url,disposition,discovered_at,discovery_time_basis) VALUES(?,?,?,?,?,?,?)")) {
                for(Map<String,String> link:safeLinks) {
                    insert.setString(1,snapshotId);insert.setInt(2,ordinal++);insert.setString(3,link.get("source_url"));
                    insert.setString(4,link.get("target_url"));insert.setString(5,link.get("disposition"));
                    insert.setString(6,link.get("discovered_at"));insert.setString(7,link.get("discovery_time_basis"));insert.addBatch();
                }
                insert.executeBatch();
            }
            try(PreparedStatement update=db.prepareStatement("UPDATE pages SET latest_snapshot=?,last_seen=? WHERE id=?")){update.setString(1,snapshotId);update.setString(2,Instant.now().toString());update.setString(3,pageId);update.executeUpdate();}
            db.commit();db.setAutoCommit(true);return page(pageId);
        } catch(SQLException | IOException e) {
            try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}throw e instanceof IOException io?io:failure((SQLException)e);
        } finally {try{db.setAutoCommit(true);}catch(SQLException e){throw failure(e);} }
    }
    private Map<String,Object> snapshotRow(ResultSet rs) throws SQLException {
        Map<String,Object> row=new LinkedHashMap<>();
        for(String key:List.of("page_id","job_id","canonical_url","snapshot_id","content_hash","content_type","fetched_at","recorded_at","source_kind","last_seen","source_url","title")) row.put(key,rs.getString(key));
        row.put("url",row.get("canonical_url"));row.put("depth",rs.getInt("discovery_depth"));row.put("http_status",rs.getInt("http_status"));row.put("content_size",rs.getInt("content_size"));
        row.put("storage_location",root.resolve("objects").resolve(rs.getString("content_hash")+".html").toString());
        return row;
    }
    private static final String LATEST="SELECT p.id page_id,p.job_id,p.canonical_url,p.last_seen,s.id snapshot_id,s.* FROM pages p JOIN snapshots s ON s.id=p.latest_snapshot";
    public synchronized Map<String,Object> pages(String jobId,int limit) throws IOException {
        bound(limit);if(jobId!=null)plan(jobId);
        List<Map<String,Object>> result=new ArrayList<>();
        try(PreparedStatement query=db.prepareStatement(LATEST+(jobId==null?"":" WHERE p.job_id=?")+" ORDER BY s.recorded_at,p.id LIMIT ?")) {
            int index=1;if(jobId!=null)query.setString(index++,jobId);query.setInt(index,limit);
            try(ResultSet rows=query.executeQuery()){while(rows.next())result.add(snapshotRow(rows));}
            return Map.of("pages",result,"limit",limit);
        } catch(SQLException e){throw failure(e);}
    }
    public synchronized Map<String,Object> page(String pageId) throws IOException {
        identifier(pageId);
        try(PreparedStatement query=db.prepareStatement(LATEST+" WHERE p.id=?")) {
            query.setString(1,pageId);
            try(ResultSet rs=query.executeQuery()) {
                if(!rs.next())throw new IOException("Unknown archived page");
                Map<String,Object> result=snapshotRow(rs);result.put("text",rs.getString("extracted_text"));
                String digest=rs.getString("content_hash");Path file=objects.resolve(digest+".html");noSymlinks(file);
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=rs.getInt("content_size")||!hash(Files.readAllBytes(file)).equals(digest))throw new IOException("Committed snapshot object missing/inconsistent");
                List<Map<String,Object>> history=new ArrayList<>();
                try(PreparedStatement versions=db.prepareStatement("SELECT id,content_hash,recorded_at FROM snapshots WHERE page_id=? ORDER BY recorded_at,id")){versions.setString(1,pageId);try(ResultSet rows=versions.executeQuery()){while(rows.next())history.add(Map.of("snapshot_id",rows.getString(1),"content_hash",rows.getString(2),"recorded_at",rows.getString(3)));}}
                result.put("history",history);
                result.put("links",readLinks(" WHERE l.snapshot_id=? ORDER BY l.ordinal LIMIT 100",(String)result.get("snapshot_id"),100));
                return result;
            }
        } catch(SQLException e){throw failure(e);}
    }
    private static String hostOf(String url) {
        try { return Objects.toString(URI.create(url).getHost(), ""); }
        catch(IllegalArgumentException e) { return ""; }
    }
    private List<Map<String,Object>> readLinks(String suffix,String id,int limit) throws SQLException {
        List<Map<String,Object>> result=new ArrayList<>();
        // Resolve against the same job at read time: a later archived target
        // becomes navigable without mutating immutable snapshot observations.
        String sql="SELECT l.*,s.page_id source_page_id,p.job_id,t.id target_page_id FROM page_links l "
            + "JOIN snapshots s ON s.id=l.snapshot_id JOIN pages p ON p.id=s.page_id "
            + "LEFT JOIN pages t ON t.job_id=p.job_id AND t.canonical_url=l.target_url";
        try(PreparedStatement query=db.prepareStatement(sql+suffix)) {
            query.setString(1,id);
            if(suffix.endsWith("LIMIT ?"))query.setInt(2,limit);
            try(ResultSet rows=query.executeQuery()) {
                while(rows.next()) {
                    Map<String,Object> edge=new LinkedHashMap<>();
                    for(String key:List.of("snapshot_id","source_page_id","job_id","source_url","target_url",
                            "disposition","discovered_at","discovery_time_basis","target_page_id"))
                        edge.put(key,rows.getString(key));
                    edge.put("ordinal",rows.getInt("ordinal"));
                    edge.put("source_host",hostOf(rows.getString("source_url")));
                    edge.put("target_host",hostOf(rows.getString("target_url")));
                    result.add(edge);
                }
            }
        }
        return result;
    }
    /** Bounded historical directed edges, including rejected/foreign targets. */
    public synchronized Map<String,Object> links(String jobId,int limit) throws IOException {
        bound(limit);plan(jobId);
        try { return Map.of("job_id",jobId,"links",readLinks(" WHERE p.job_id=? ORDER BY l.discovered_at,l.snapshot_id,l.ordinal LIMIT ?",jobId,limit),"limit",limit); }
        catch(SQLException e) { throw failure(e); }
    }
    public synchronized Map<String,Object> search(String query,String jobId,int limit) throws IOException {
        bound(limit);bounded(query,256,"search query");if(query.isBlank())throw new IOException("Search query is empty");if(jobId!=null)plan(jobId);
        List<Map<String,Object>> result=new ArrayList<>();
        // LOCATE treats user input literally; '%' and '_' are never wildcard operators.
        try(PreparedStatement statement=db.prepareStatement(LATEST+" WHERE (LOCATE(LOWER(?),LOWER(s.title))>0 OR LOCATE(LOWER(?),LOWER(s.extracted_text))>0)"+(jobId==null?"":" AND p.job_id=?")+" ORDER BY s.recorded_at DESC,p.id LIMIT ?")) {
            statement.setString(1,query);statement.setString(2,query);int index=3;if(jobId!=null)statement.setString(index++,jobId);statement.setInt(index,limit);
            try(ResultSet rs=statement.executeQuery()){while(rs.next())result.add(snapshotRow(rs));}
            return Map.of("query",query,"results",result,"limit",limit,"provider","local-archive");
        }catch(SQLException e){throw failure(e);}
    }
    public synchronized Map<String,Object> exportSummary(String jobId,int limit) throws IOException {
        return Map.of("job",job(jobId),"pages",pages(jobId,limit).get("pages"),"links",links(jobId,limit).get("links"),"limit",limit);
    }
    public synchronized Map<String,Object> recordFailure(String jobId,String url,String category) throws IOException {
        return recordFailure(jobId,url,category,FailureObservation.unknown());
    }
    public synchronized Map<String,Object> recordFailure(String jobId,String url,String category,FailureObservation observation) throws IOException {
        capacity();
        Map<String,Object> plan=approved(jobId);url=normalize(url,(String)plan.get("origin"));
        if(category==null||!category.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IOException("Require bounded failure category");
        if(observation==null)throw new IOException("Require structured failure observation");
        try {
            db.setAutoCommit(false);
            try(PreparedStatement lock=db.prepareStatement("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE")){try(ResultSet rs=lock.executeQuery()){rs.next();}}
            String id=null;
            try(PreparedStatement query=db.prepareStatement("SELECT id FROM failures WHERE job_id=? AND canonical_url=? AND category=?")){
                query.setString(1,jobId);query.setString(2,url);query.setString(3,category);
                try(ResultSet rs=query.executeQuery()){if(rs.next())id=rs.getString(1);}
            }
            if(id==null) {
                if(count("SELECT COUNT(*) FROM failures WHERE job_id=?",jobId)>=1000)throw new IOException("Job failure record limit1000");
                id=UUID.randomUUID().toString();try(PreparedStatement insert=db.prepareStatement("INSERT INTO failures VALUES(?,?,?,?,?)")){insert.setString(1,id);insert.setString(2,jobId);insert.setString(3,url);insert.setString(4,category);insert.setString(5,Instant.now().toString());insert.executeUpdate();}
            }
            if(count("SELECT COUNT(*) FROM failure_observations_v1 WHERE job_id=?",jobId)>=1000)
                throw new IOException("Job failure observation limit1000; no automatic deletion");
            try(var insert=db.prepareStatement("INSERT INTO failure_observations_v1(job_id,failure_id,canonical_url,category,observed_at,phase,source,http_status,retryable,terminal_intent,next_due_epoch,transport_category) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1,jobId);insert.setString(2,id);insert.setString(3,url);insert.setString(4,category);insert.setString(5,Instant.now().toString());
                insert.setString(6,observation.phase().name());insert.setString(7,observation.source().name());
                insert.setObject(8,observation.httpStatus(),Types.INTEGER);insert.setObject(9,observation.retryable(),Types.BOOLEAN);
                insert.setObject(10,observation.terminal(),Types.BOOLEAN);insert.setObject(11,observation.nextDueEpoch(),Types.BIGINT);
                insert.setString(12,observation.transport().name());insert.executeUpdate();
            }
            db.commit();return Map.of("failure_id",id,"job_id",jobId,"url",url,"category",category);
        } catch(SQLException | IOException e) {
            try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}throw e instanceof IOException io?io:failure((SQLException)e);
        } finally{try{db.setAutoCommit(true);}catch(SQLException e){throw failure(e);}}
    }

    /** Immutable observation sequence, not a fetch counter or a scheduler queue. */
    public synchronized Map<String,Object> failureHistory(String jobId,String cursor,int limit) throws IOException {
        plan(jobId);bound(limit);
        try {
            long maximum;
            try(var query=db.prepareStatement("SELECT COALESCE(MAX(observation_seq),0) FROM failure_observations_v1 WHERE job_id=?")) {
                query.setString(1,jobId);try(var row=query.executeQuery()){row.next();maximum=row.getLong(1);}
            }
            long through=maximum,after=0;
            if(cursor!=null) {
                bounded(cursor,256,"failure history cursor");
                try {
                    String[] parts=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split(":",-1);
                    if(parts.length!=4||!parts[0].equals("1")||!parts[1].equals(jobId)||!parts[2].matches("[0-9]{1,19}")||!parts[3].matches("[0-9]{1,19}"))throw new IllegalArgumentException();
                    through=Long.parseLong(parts[2]);after=Long.parseLong(parts[3]);
                    if(through>maximum||after<1||after>=through)throw new IllegalArgumentException();
                }catch(IllegalArgumentException e){throw new IOException("Invalid failure history cursor");}
            }
            List<Map<String,Object>> rows=new ArrayList<>();long last=after;
            try(var query=db.prepareStatement("SELECT * FROM failure_observations_v1 WHERE job_id=? AND observation_seq>? AND observation_seq<=? ORDER BY observation_seq LIMIT ?")) {
                query.setString(1,jobId);query.setLong(2,after);query.setLong(3,through);query.setInt(4,limit);
                try(var rs=query.executeQuery()){while(rs.next()) {
                    last=rs.getLong("observation_seq");rows.add(failureObservationRow(rs));
                }}
            }
            long count;
            try(var query=db.prepareStatement("SELECT COUNT(*) FROM failure_observations_v1 WHERE job_id=? AND observation_seq<=?")) {
                query.setString(1,jobId);query.setLong(2,through);try(var row=query.executeQuery()){row.next();count=row.getLong(1);}
            }
            Map<String,Object> result=new LinkedHashMap<>();result.put("history_version",1);result.put("plan_id",jobId);
            result.put("rows",rows);result.put("observation_count",count);result.put("fetch_attempt_count",null);
            result.put("legacy_aggregate_count",count("SELECT COUNT(*) FROM failures WHERE job_id=?",jobId));
            result.put("legacy_aggregates_are_attempts",false);result.put("through_sequence",through);result.put("limit",limit);
            result.put("next_cursor",null);result.put("truncated",last<through);
            if(last<through) {
                if(rows.isEmpty())throw new IOException("Failure history cursor points to unavailable rows");
                result.put("next_cursor",Base64.getUrlEncoder().withoutPadding().encodeToString(("1:"+jobId+":"+through+":"+last).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            return result;
        }catch(SQLException e){throw failure(e);}
    }
    private static Map<String,Object> failureObservationRow(ResultSet rs)throws SQLException {
        Map<String,Object> row=new LinkedHashMap<>();row.put("observation_id",rs.getLong("observation_seq"));
        for(String key:List.of("job_id","failure_id","category","observed_at","phase","source","transport_category"))row.put(key,rs.getString(key));
        row.put("url",rs.getString("canonical_url"));row.put("http_status",rs.getObject("http_status"));
        row.put("retryable",rs.getObject("retryable"));row.put("terminal",rs.getObject("terminal_intent"));row.put("next_due_epoch",rs.getObject("next_due_epoch"));
        row.put("applied_to_frontier",null);return row;
    }

    @Override public synchronized void close() throws IOException {try{db.close();}catch(SQLException e){throw failure(e);} }
}
