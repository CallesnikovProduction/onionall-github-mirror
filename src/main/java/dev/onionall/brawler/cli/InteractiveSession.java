package dev.onionall.brawler.cli;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** A small ANSI viewport; owns only presentation and never terminal input/networking. */
final class InteractiveSession implements AutoCloseable {
    record Size(int columns, int rows) { }
    interface Dimensions { Size read(); }
    private final PrintWriter terminal;
    private final Map<String,String> env;
    private final boolean pinned;
    private final Dimensions dimensions;
    private final ScheduledExecutorService timer;
    private final List<String> lines = new ArrayList<>();
    private final List<String> painted = new ArrayList<>();
    private StringBuilder current = new StringBuilder();
    private Size size;
    private int headerRows;
    private boolean closed;
    private String progress;
    private final Thread shutdown;
    private final PrintWriter writer;

    InteractiveSession(PrintWriter terminal, Map<String,String> env, boolean pinned) {
        this(terminal,env,pinned,()->terminalSize(env),true);
    }
    InteractiveSession(PrintWriter terminal, Map<String,String> env, boolean pinned, Dimensions dimensions, boolean poll) {
        this.terminal=terminal;this.env=env;this.pinned=pinned;this.dimensions=dimensions;
        this.writer=new PrintWriter(new Writer() {
            public void write(char[] chars,int offset,int length) { accept(new String(chars,offset,length)); }
            public void flush() { synchronized(InteractiveSession.this) { paint(); } }
            public void close() { }
        },true);
        this.shutdown=new Thread(this::close,"onionall-terminal-reset");
        Runtime.getRuntime().addShutdownHook(shutdown);
        size=dimensions.read();
        if(pinned) terminal.print("\033[2J\033[H"); // Once on entry, never per interaction.
        header();
        timer=poll && pinned ? Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"onionall-terminal-size");t.setDaemon(true);return t;}) : null;
        if(timer!=null) timer.scheduleWithFixedDelay(this::resize,500,500,TimeUnit.MILLISECONDS);
    }
    synchronized int availableRows() {return Math.max(1,size.rows()-headerRows);}
    PrintWriter writer() { return writer; }
    synchronized void begin() {
        lines.clear();current.setLength(0);progress=null;paint();
    }
    synchronized void progress(String text) { progress=text;paint(); }
    synchronized void stopProgress() { progress=null;paint(); }
    private synchronized void accept(String text) {
        if(closed)return;
        if(!pinned) { terminal.print(text);terminal.flush();return; }
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='\n') { lines.add(current.toString());current.setLength(0);if(lines.size()>200)lines.removeFirst(); }
            else if(c=='\r') current.setLength(0);
            else if(c!='\033') current.append(c);
        }
    }
    synchronized void resize() {
        if(closed || !pinned)return;
        Size next=dimensions.read();
        if(!next.equals(size)) {
            size=next;
            // Resize is the only time header geometry changes. Clear individual old rows.
            terminal.print("\033[r");
            for(int row=1;row<=Math.max(headerRows+painted.size(),size.rows());row++) {
                if(row<=size.rows()) terminal.printf("\033[%d;1H\033[2K",row);
            }
            header();paint();
        }
    }
    private void header() {
        List<String> header=Brand.header(size.columns(),size.rows(),env,pinned);
        headerRows=header.size();
        if(pinned) {
            for(int i=0;i<headerRows;i++) terminal.printf("\033[%d;1H\033[2K%s",i+1,header.get(i));
            terminal.printf("\033[%d;%dr",headerRows+1,size.rows());
        } else for(String row:header) terminal.println(row);
        painted.clear();terminal.flush();
    }
    private void paint() {
        if(!pinned || closed)return;
        int available=Math.max(1,size.rows()-headerRows);
        List<String> view=new ArrayList<>(lines);view.add(current.toString());
        if(progress!=null)view.add(progress);
        if(view.size()>available)view=new ArrayList<>(view.subList(view.size()-available,view.size()));
        int count=Math.max(painted.size(),view.size());
        for(int i=0;i<count;i++) {
            String value=i<view.size()?clip(view.get(i),size.columns()-1):"";
            if(i>=painted.size() || !value.equals(painted.get(i))) terminal.printf("\033[%d;1H\033[2K%s",headerRows+1+i,CliPresentation.style(value,true));
        }
        painted.clear();for(String row:view)painted.add(clip(row,size.columns()-1));
        int cursor=headerRows+Math.max(1,view.size());
        int column=view.isEmpty()?1:clip(view.getLast(),size.columns()-1).length()+1;
        terminal.printf("\033[%d;%dH",cursor,column);terminal.flush();
    }
    static List<String> summary(Map<String,Object> result) {
        if(result.containsKey("socks_tcp_reachable")) return List.of(
                "JVM "+result.get("java")+" | onionall "+result.get("version"),
                "SOCKS "+result.get("socks_endpoint")+" | TCP reachable: "+result.get("socks_tcp_reachable"),
                "Tor: "+result.get("tor_connectivity")+" | onion: "+result.get("onion_connectivity"),
                "Downloads: "+result.get("downloads"),
                "Timeout: "+result.get("timeout_ms")+" ms | limit: "+result.get("max_bytes")+" bytes",
                "fetch / inspect / status | M2/M3 acceptance: "+result.get("m2_m3_acceptance"));
        if(result.containsKey("anchors")) return List.of(
                "Title: "+result.get("title"),"Bytes: "+result.get("bytes")+" | encoding: "+result.get("encoding"),
                "Anchors: "+result.get("anchors")+" | unique: "+result.get("unique_links"),
                "Relative: "+result.get("relative_links")+" | absolute: "+result.get("absolute_links")+" | onion: "+result.get("onion_links"),
                "Description: "+result.get("description"),"File: "+result.get("file"));
        return result.entrySet().stream().map(e->e.getKey()+": "+e.getValue()).toList();
    }
    // Untrusted wide/combining characters cannot invalidate viewport cell accounting.
    static String clip(String text,int width) {
        StringBuilder ascii=new StringBuilder();
        Brand.safe(text).codePoints().forEach(c->{if(c>=32 && c<127 || c==0x276f)ascii.appendCodePoint(c);else ascii.append(String.format("\\u%04x",c));});
        return ascii.substring(0,Math.min(Math.max(0,width),ascii.length()));
    }
    public synchronized void close() {
        if(closed)return;closed=true;
        if(timer!=null)timer.shutdownNow();
        if(pinned)terminal.printf("\033[r\033[?25h\033[%d;1H%n",size.rows());
        terminal.flush();
        try {Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException stopping){ }
    }
    private static Size terminalSize(Map<String,String> env) {
        int cols=number(env.get("COLUMNS"),80),rows=number(env.get("LINES"),24);
        Process process=null;
        try {
            process=new ProcessBuilder("stty","size").redirectInput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(process.waitFor(200,TimeUnit.MILLISECONDS) && process.exitValue()==0) {
                String[] fields=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII).trim().split("\\s+");
                if(fields.length==2) { rows=number(fields[0],rows);cols=number(fields[1],cols); }
            }
        } catch(IOException e) { /* Minimal terminals use environment dimensions. */ }
        catch(InterruptedException e) {Thread.currentThread().interrupt();}
        finally {
            if(process!=null) {
                if(process.isAlive())process.destroyForcibly();
                try {process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();}
                catch(IOException ignored) { }
            }
        }
        return new Size(Math.max(2,cols),Math.max(3,rows));
    }
    private static int number(String text,int fallback) {try {int n=Integer.parseInt(text);return n>0?n:fallback;}catch(Exception e){return fallback;} }
}
