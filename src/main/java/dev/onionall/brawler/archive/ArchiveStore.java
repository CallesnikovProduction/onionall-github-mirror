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
                rs.next(); if (rs.getInt(1) > 1) throw new IOException("Archive schema is newer than this application");
            }
            statement.execute("CREATE TABLE IF NOT EXISTS catalog_guard (id INT PRIMARY KEY)");
            statement.execute("MERGE INTO catalog_guard KEY(id) VALUES (1)");
            statement.execute("CREATE TABLE IF NOT EXISTS crawl_plans (id VARCHAR(36) PRIMARY KEY, seeds_json CLOB NOT NULL, origin VARCHAR(256) NOT NULL, max_depth INT NOT NULL CHECK(max_depth BETWEEN 0 AND 5), max_pages INT NOT NULL CHECK(max_pages BETWEEN 1 AND 100), provider VARCHAR(256) NOT NULL, approved BOOLEAN NOT NULL DEFAULT FALSE, created_at VARCHAR(40) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS pages (id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES crawl_plans(id), canonical_url VARCHAR(2048) NOT NULL, latest_snapshot VARCHAR(36), last_seen VARCHAR(40), UNIQUE(job_id,canonical_url))");
            statement.execute("CREATE TABLE IF NOT EXISTS snapshots (id VARCHAR(36) PRIMARY KEY, page_id VARCHAR(36) NOT NULL REFERENCES pages(id), content_hash VARCHAR(64) NOT NULL, content_size INT NOT NULL CHECK(content_size BETWEEN 1 AND 131072), http_status INT NOT NULL CHECK(http_status=0 OR http_status BETWEEN 200 AND 299), content_type VARCHAR(64) NOT NULL, fetched_at VARCHAR(40), recorded_at VARCHAR(40) NOT NULL, source_kind VARCHAR(16) NOT NULL, discovery_depth INT NOT NULL CHECK(discovery_depth BETWEEN 0 AND 5), source_url VARCHAR(2048) NOT NULL, title VARCHAR(2048) NOT NULL, extracted_text CLOB NOT NULL, UNIQUE(page_id,content_hash,source_kind))");
            statement.execute("CREATE TABLE IF NOT EXISTS page_links (snapshot_id VARCHAR(36) NOT NULL REFERENCES snapshots(id), ordinal INT NOT NULL, source_url VARCHAR(2048) NOT NULL, target_url VARCHAR(2048) NOT NULL, disposition VARCHAR(64) NOT NULL, PRIMARY KEY(snapshot_id,ordinal))");
            statement.execute("CREATE TABLE IF NOT EXISTS failures (id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES crawl_plans(id), canonical_url VARCHAR(2048) NOT NULL, category VARCHAR(64) NOT NULL, occurred_at VARCHAR(40) NOT NULL, UNIQUE(job_id,canonical_url,category))");
            statement.execute("CREATE INDEX IF NOT EXISTS pages_job_idx ON pages(job_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS snapshots_page_idx ON snapshots(page_id,recorded_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS failures_job_idx ON failures(job_id,occurred_at)");
            statement.execute("MERGE INTO schema_version KEY(version) VALUES(1)");
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
                result.put("state",rs.getBoolean("approved") ? "APPROVED" : "DRAFT"); result.put("admission","BLOCKED_ADR_0002"); result.put("created_at",rs.getString("created_at"));
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
    public synchronized Map<String,Object> job(String id) throws IOException {
        Map<String,Object> result = new LinkedHashMap<>(plan(id));
        try {
            result.put("archived_pages", count("SELECT COUNT(*) FROM pages WHERE job_id=?", id));
            result.put("snapshots",count("SELECT COUNT(*) FROM snapshots s JOIN pages p ON p.id=s.page_id WHERE p.job_id=?",id));
            result.put("failures",count("SELECT COUNT(*) FROM failures WHERE job_id=?",id));
            List<Map<String,Object>> failures=new ArrayList<>();
            try(PreparedStatement query=db.prepareStatement("SELECT id,canonical_url,category,occurred_at FROM failures WHERE job_id=? ORDER BY occurred_at DESC,id LIMIT 100")) {
                query.setString(1,id);try(ResultSet rs=query.executeQuery()){while(rs.next())failures.add(Map.of("failure_id",rs.getString(1),"url",rs.getString(2),"category",rs.getString(3),"occurred_at",rs.getString(4)));}
            }
            result.put("failure_records",failures);
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
            safeLinks.add(Map.of("source_url",url,"target_url",target,"disposition",disposition));
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
            try(PreparedStatement insert=db.prepareStatement("INSERT INTO page_links(snapshot_id,ordinal,source_url,target_url,disposition) VALUES(?,?,?,?,?)")) {for(Map<String,String> link:safeLinks){insert.setString(1,snapshotId);insert.setInt(2,ordinal++);insert.setString(3,link.get("source_url"));insert.setString(4,link.get("target_url"));insert.setString(5,link.get("disposition"));insert.addBatch();}insert.executeBatch();}
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
                List<Map<String,Object>> links=new ArrayList<>();
                try(PreparedStatement edges=db.prepareStatement("SELECT source_url,target_url,disposition FROM page_links WHERE snapshot_id=? ORDER BY ordinal")){edges.setString(1,(String)result.get("snapshot_id"));try(ResultSet rows=edges.executeQuery()){while(rows.next())links.add(Map.of("source_url",rows.getString(1),"target_url",rows.getString(2),"disposition",rows.getString(3)));}}
                result.put("links",links);return result;
            }
        } catch(SQLException e){throw failure(e);}
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
        return Map.of("job",job(jobId),"pages",pages(jobId,limit).get("pages"),"limit",limit);
    }
    public synchronized Map<String,Object> recordFailure(String jobId,String url,String category) throws IOException {
        capacity();
        Map<String,Object> plan=approved(jobId);url=normalize(url,(String)plan.get("origin"));
        if(category==null||!category.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IOException("Require bounded failure category");
        try {
            db.setAutoCommit(false);
            try(PreparedStatement lock=db.prepareStatement("SELECT id FROM catalog_guard WHERE id=1 FOR UPDATE")){try(ResultSet rs=lock.executeQuery()){rs.next();}}
            try(PreparedStatement query=db.prepareStatement("SELECT id FROM failures WHERE job_id=? AND canonical_url=? AND category=?")){
                query.setString(1,jobId);query.setString(2,url);query.setString(3,category);
                try(ResultSet rs=query.executeQuery()){if(rs.next()){
                    String id=rs.getString(1);db.commit();return Map.of("failure_id",id,"job_id",jobId,"url",url,"category",category);
                }}
            }
            if(count("SELECT COUNT(*) FROM failures WHERE job_id=?",jobId)>=1000)throw new IOException("Job failure record limit1000");
            String id=UUID.randomUUID().toString();try(PreparedStatement insert=db.prepareStatement("INSERT INTO failures VALUES(?,?,?,?,?)")){insert.setString(1,id);insert.setString(2,jobId);insert.setString(3,url);insert.setString(4,category);insert.setString(5,Instant.now().toString());insert.executeUpdate();}
            db.commit();return Map.of("failure_id",id,"job_id",jobId,"url",url,"category",category);
        } catch(SQLException | IOException e) {
            try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}throw e instanceof IOException io?io:failure((SQLException)e);
        } finally{try{db.setAutoCommit(true);}catch(SQLException e){throw failure(e);}}
    }

    @Override public synchronized void close() throws IOException {try{db.close();}catch(SQLException e){throw failure(e);} }
}
