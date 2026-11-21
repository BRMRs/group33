package com.group33.server.migration;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributes;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import org.postgresql.ds.PGSimpleDataSource;

/** Explicit loopback-only recovery rehearsal. Never runs in the application or connects to cloud. */
public final class ManualSchemaCopy {
    public static final List<String> LEGACY = List.of("pairing_issuance_guard", "anonymous_identity",
        "pairing_active_code", "pairing_issue_operation", "pairing_issuance_time", "pairing_relationship",
        "pairing_request", "pairing_request_operation", "pairing_request_attempt", "pairing_decision_operation",
        "pairing_code_deletion", "medication_photo", "photo_upload_operation", "medication", "reminder_plan",
        "reminder_plan_revision", "plan_confirmation_operation", "plan_mutation_operation");
    private static final Set<String> TARGET;
    static {
        var names = new TreeSet<>(LEGACY);
        names.addAll(List.of("occurrence_registration_operation", "registered_occurrence", "dose_group_operation", "recorded_dose_event", "group33_schema_history"));
        TARGET = Collections.unmodifiableSet(names);
    }
    private static final String HISTORY = "group33_schema_history";
    private static final int MAX_ROWS = 10_000, MAX_TOTAL_ROWS = 50_000, MAX_CELL_BYTES = 1_048_576;

    public record Reference(String schema, String catalog, String history) {
        public Reference {
            identifier(schema);
            if (catalog == null || history == null || !catalog.matches("[0-9a-f]{64}") ||
                !(history.equals("none") || history.matches("(?:0|[1-9][0-9]{0,4}):[0-9a-f]{64}")))
                throw new IllegalArgumentException("Invalid private catalog reference");
        }
        @Override public String toString() { return "Reference(REDACTED)"; }
    }
    private static final class Budget {
        final long end = System.nanoTime() + 30_000_000_000L;
        int seconds() {
            long left = end - System.nanoTime();
            if (left <= 0) throw new IllegalStateException("Rehearsal budget exhausted");
            return (int)Math.min(5, (left + 999_999_999L) / 1_000_000_000L);
        }
        Statement statement(Connection c) throws SQLException {
            Statement s = c.createStatement(); s.setQueryTimeout(seconds()); return s;
        }
    }
    private static void identifier(String name) {
        if (name == null || !name.matches("group33_[a-z0-9_]{1,56}") || name.equals("group33_schema_history"))
            throw new IllegalArgumentException("Invalid private rehearsal schema");
    }
    private static String quote(String name) {
        if (!name.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalArgumentException("Unsupported identifier");
        return "\"" + name + "\"";
    }
    private static String table(String schema, String name) { return quote(schema) + "." + quote(name); }

    public static void requireLoopback(String url, boolean privateInput) {
        if (url == null || !url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("Loopback URL required");
        URI u = URI.create(url.substring(5));
        if (!Set.of("127.0.0.1", "::1", "[::1]").contains(u.getHost()) || u.getUserInfo() != null ||
            u.getFragment() != null || u.getRawQuery() != null ||
            u.getPath() == null || !u.getPath().matches("/[a-zA-Z0-9_-]+") ||
            !u.getRawPath().equals(u.getPath()) || !(u.getPort() == -1 || u.getPort() > 0 && u.getPort() < 65536))
            throw new IllegalArgumentException("Only explicit loopback rehearsal connections are allowed");
    }
    private static void setup(Connection c, Budget budget, boolean readOnly) throws SQLException {
        requireLoopback(c.getMetaData().getURL(), false);
        c.setReadOnly(readOnly);
        c.setTransactionIsolation(readOnly ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
        c.setAutoCommit(false);
        try (Statement s = budget.statement(c)) {
            s.execute("SET LOCAL lock_timeout='750ms'");
            s.execute("SET LOCAL statement_timeout='5000ms'");
            s.execute("SET LOCAL idle_in_transaction_session_timeout='15000ms'");
        }
    }
    private static Set<String> tables(Connection c, String schema, Budget budget) throws SQLException {
        var result = new TreeSet<String>();
        try (PreparedStatement s = c.prepareStatement("SELECT relname,relkind FROM pg_class WHERE relnamespace=(SELECT oid FROM pg_namespace WHERE nspname=?) AND relkind IN ('r','p','v','m','f','S') ORDER BY relname")) {
            s.setQueryTimeout(budget.seconds()); s.setString(1, schema);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (!r.getString(2).equals("r") || result.size() > 128) throw new IllegalStateException("Unsupported relation");
                    quote(r.getString(1)); result.add(r.getString(1));
                }
            }
        }
        return result;
    }
    private static void scope(Connection c, String schema, boolean target, Budget budget) throws SQLException {
        if (!tables(c, schema, budget).equals(target ? TARGET : new TreeSet<>(LEGACY)))
            throw new IllegalStateException("Unsupported source or target relation set");
    }
    private static void feed(MessageDigest digest, String value) {
        if (value == null) { digest.update(new byte[]{-1,-1,-1,-1}); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CELL_BYTES) throw new IllegalStateException("Catalog or row budget exceeded");
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException("Digest unavailable"); }
    }
    private static String fingerprint(Connection c, String schema, Budget budget) throws Exception {
        String actor, owner;
        try (PreparedStatement s = c.prepareStatement("SELECT current_user,pg_get_userbyid(nspowner),set_config('search_path',?,true) FROM pg_namespace WHERE nspname=?")) {
            s.setQueryTimeout(budget.seconds()); s.setString(1, quote(schema)); s.setString(2, schema);
            try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IllegalStateException("Missing schema"); actor=r.getString(1); owner=r.getString(2); }
        }
        MessageDigest hash = digest();
        try (InputStream in = ManualSchemaCopy.class.getResourceAsStream("/db/manual-copy-catalog.tsv")) {
            if (in == null) throw new IllegalStateException("Missing fixed catalog queries");
            var lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            if (lines.size() != 22) throw new IllegalStateException("Incomplete fixed catalog queries");
            for (String line : lines) {
                String[] pair = line.split("\t", 2);
                if (pair.length != 2 || !pair[1].startsWith("SELECT ")) throw new IllegalStateException("Invalid fixed query");
                feed(hash, pair[0]);
                try (Statement s = budget.statement(c)) {
                    s.setMaxRows(5001);
                    try (ResultSet r = s.executeQuery(pair[1])) {
                        ResultSetMetaData metadata = r.getMetaData(); int rows=0;
                        while (r.next()) {
                            if (++rows > 5000) throw new IllegalStateException("Catalog row budget exceeded");
                            feed(hash, "row");
                            for (int i=1; i<=metadata.getColumnCount(); i++) {
                                feed(hash, metadata.getColumnLabel(i)); String value = r.getString(i);
                                if (value != null) value = value.replace("\""+schema+"\".","SCHEMA.")
                                    .replace(schema+".","SCHEMA.").replace(schema,"SCHEMA")
                                    .replace(actor,"APP_ROLE").replace(owner,"SCHEMA_OWNER");
                                feed(hash, value);
                            }
                        }
                        feed(hash, Integer.toString(rows));
                    }
                }
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static String rowDigest(Connection c, String schema, String name, Budget budget) throws SQLException {
        MessageDigest hash = digest(); int rows=0;
        try (Statement s = budget.statement(c)) {
            s.setFetchSize(128);
            try (ResultSet r = s.executeQuery("SELECT row_to_json(t)::text FROM "+table(schema,name)+" t ORDER BY row_to_json(t)::text COLLATE \"C\" LIMIT "+(MAX_ROWS+1))) {
                while (r.next()) { if (++rows > MAX_ROWS) throw new IllegalStateException("Row budget exceeded"); feed(hash,r.getString(1)); }
            }
        }
        return rows+":"+HexFormat.of().formatHex(hash.digest());
    }
    public static Reference inspect(PGSimpleDataSource source, String schema, boolean target) throws Exception {
        identifier(schema); requireLoopback(source.getURL(),false);
        if (target) new SchemaMigrations(source,schema).validate();
        try (Connection c=source.getConnection()) {
            Budget budget=new Budget(); setup(c,budget,true);
            try { scope(c,schema,target,budget); return new Reference(schema,fingerprint(c,schema,budget),target?rowDigest(c,schema,HISTORY,budget):"none"); }
            finally { c.rollback(); }
        }
    }
    private static List<String> columns(Connection c, String schema, String name, Budget budget) throws SQLException {
        var columns=new ArrayList<String>();
        try (PreparedStatement s=c.prepareStatement("SELECT attname,format_type(atttypid,atttypmod),attnotnull,attidentity,attgenerated FROM pg_attribute WHERE attrelid=to_regclass(?) AND attnum>0 AND NOT attisdropped ORDER BY attnum")) {
            s.setQueryTimeout(budget.seconds());s.setString(1,table(schema,name));
            try (ResultSet r=s.executeQuery()) { while(r.next()) {
                quote(r.getString(1));
                if(!r.getString(4).isEmpty() || !r.getString(5).isEmpty())throw new IllegalStateException("Unsupported generated column");
                columns.add(r.getString(1)+":"+r.getString(2)+":"+r.getBoolean(3));
            } }
        }
        if(columns.isEmpty() || columns.size()>128)throw new IllegalStateException("Unsupported column count");
        return columns;
    }
    public static int copy(PGSimpleDataSource source, Reference original, Reference target) throws Exception {
        if(original.schema.equals(target.schema) || !original.history.equals("none") || target.history.equals("none"))
            throw new IllegalArgumentException("Distinct source and managed target required");
        requireLoopback(source.getURL(),false);
        new SchemaMigrations(source,target.schema).validate();
        try (Connection c=source.getConnection()) {
            Budget budget=new Budget();setup(c,budget,false);
            try {
                // Source/target writers and privileged DDL must already be quiesced by the operator.
                try (Statement s=budget.statement(c)) {
                    s.execute("LOCK TABLE "+String.join(",",LEGACY.stream().map(n->table(original.schema,n)).toList())+" IN SHARE MODE");
                    s.execute("LOCK TABLE "+String.join(",",TARGET.stream().map(n->table(target.schema,n)).sorted().toList())+" IN ACCESS EXCLUSIVE MODE");
                }
                scope(c,original.schema,false,budget);scope(c,target.schema,true,budget);
                if(!fingerprint(c,original.schema,budget).equals(original.catalog) || !fingerprint(c,target.schema,budget).equals(target.catalog)
                    || !rowDigest(c,target.schema,HISTORY,budget).equals(target.history)) throw new IllegalStateException("Catalog reference changed");
                for(String name:TARGET)if(!name.equals(HISTORY))try(Statement s=budget.statement(c);ResultSet r=s.executeQuery("SELECT count(*) FROM "+table(target.schema,name))) {
                    if(!r.next() || r.getLong(1)!=(name.equals("pairing_issuance_guard")?1:0))throw new IllegalStateException("Target is not empty");
                }
                var before=new LinkedHashMap<String,String>(); int total=0;
                for(String name:LEGACY) {
                    if(!columns(c,original.schema,name,budget).equals(columns(c,target.schema,name,budget)))throw new IllegalStateException("Column mismatch");
                    String value=rowDigest(c,original.schema,name,budget);before.put(name,value);total+=Integer.parseInt(value.substring(0,value.indexOf(':')));
                    if(total>MAX_TOTAL_ROWS)throw new IllegalStateException("Total row budget exceeded");
                }
                try(Statement s=budget.statement(c)){s.execute("SET CONSTRAINTS ALL DEFERRED");}
                for(String name:LEGACY)if(!name.equals("pairing_issuance_guard")) {
                    String names=String.join(",",columns(c,original.schema,name,budget).stream().map(n->quote(n.substring(0,n.indexOf(':')))).toList());
                    try(Statement s=budget.statement(c)){s.executeUpdate("INSERT INTO "+table(target.schema,name)+" ("+names+") SELECT "+names+" FROM "+table(original.schema,name));}
                }
                try(Statement s=budget.statement(c)){s.execute("SET CONSTRAINTS ALL IMMEDIATE");}
                for(String name:LEGACY)if(!before.get(name).equals(rowDigest(c,target.schema,name,budget)) || !before.get(name).equals(rowDigest(c,original.schema,name,budget)))
                    throw new IllegalStateException("Copied rows differ");
                if(!fingerprint(c,original.schema,budget).equals(original.catalog) || !fingerprint(c,target.schema,budget).equals(target.catalog)
                    || !rowDigest(c,target.schema,HISTORY,budget).equals(target.history))throw new IllegalStateException("Catalog or history changed");
                budget.seconds();c.commit();return total;
            } catch(Exception failure) { try {c.rollback();} catch(SQLException ignored) {} throw failure; }
        }
    }
    private static Path privateReferencePath(String name) {
        Path path=Path.of(Objects.requireNonNull(name));
        if(!path.isAbsolute() || !path.equals(path.normalize()) || path.getFileName()==null)
            throw new IllegalArgumentException("Invalid private catalog file");
        return path;
    }
    private static boolean privateDirectoryAttributes(PosixFileAttributes attributes) {
        return attributes.isDirectory() && attributes.permissions().equals(PosixFilePermissions.fromString("rwx------"));
    }
    private record PrivateReferenceDirectory(Path path, Object fileKey) {
        void check() throws IOException {
            PosixFileAttributes current=Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!privateDirectoryAttributes(current) || !fileKey.equals(current.fileKey()))
                throw new IllegalArgumentException("Private reference directory changed");
        }
    }
    private static PrivateReferenceDirectory privateDirectory(Path file) throws IOException {
        Path parent=file.getParent();
        PosixFileAttributes expected=Files.readAttributes(parent,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!privateDirectoryAttributes(expected) || expected.fileKey()==null)
            throw new IllegalArgumentException("Private reference directory required");
        // Canonicalize system ancestor aliases (e.g. macOS /var) only after rejecting a linked parent.
        PrivateReferenceDirectory directory=new PrivateReferenceDirectory(parent.toRealPath(),expected.fileKey());
        directory.check(); return directory;
    }
    private static void privateReferenceAttributes(PosixFileAttributes attributes) {
        if(!attributes.isRegularFile() || attributes.size()>4096 || attributes.fileKey()==null ||
            !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------")))
            throw new IllegalArgumentException("Invalid private catalog file");
    }
    public static Reference readReference(String path) throws IOException {
        Path file=privateReferencePath(path); byte[] bytes;
        PrivateReferenceDirectory parent=privateDirectory(file);
        Path pinned=parent.path.resolve(file.getFileName()); parent.check();
        PosixFileAttributes before=Files.readAttributes(pinned,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        privateReferenceAttributes(before);
        try(var input=Files.newByteChannel(pinned,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer=ByteBuffer.allocate(4097);
            while(buffer.hasRemaining() && input.read(buffer)!=-1) { }
            if(buffer.position()>4096)throw new IllegalArgumentException("Private reference budget exceeded");
            bytes=Arrays.copyOf(buffer.array(),buffer.position());
        }
        PosixFileAttributes after=Files.readAttributes(pinned,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        privateReferenceAttributes(after); parent.check();
        if(!before.fileKey().equals(after.fileKey()))throw new IllegalArgumentException("Private reference path changed");
        if(new String(bytes,StandardCharsets.ISO_8859_1).lines().filter(s->!s.isBlank()&&!s.startsWith("#")&&!s.startsWith("!")).count()!=4)
            throw new IllegalArgumentException("Duplicate or incomplete private reference fields");
        Properties p=new Properties();try(InputStream in=new ByteArrayInputStream(bytes)){p.load(in);}
        if(!p.stringPropertyNames().equals(Set.of("format","schema","catalog","history")) || !"LOCAL_COPY_REFERENCE_V1".equals(p.getProperty("format")))
            throw new IllegalArgumentException("Invalid private catalog reference");
        return new Reference(p.getProperty("schema"),p.getProperty("catalog"),p.getProperty("history"));
    }
    public static void writeReference(String path, Reference reference) throws IOException {
        Path file=privateReferencePath(path);
        Properties p=new Properties();p.setProperty("format","LOCAL_COPY_REFERENCE_V1");p.setProperty("schema",reference.schema);
        p.setProperty("catalog",reference.catalog);p.setProperty("history",reference.history);
        PrivateReferenceDirectory parent=privateDirectory(file); parent.check();
        Path pinned=parent.path.resolve(file.getFileName());
        try(var channel=Files.newByteChannel(pinned,Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            OutputStream out=Channels.newOutputStream(channel)) {
            p.store(out,"Private local recovery reference; review before copy");
        }
        privateReferenceAttributes(Files.readAttributes(pinned,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS)); parent.check();
    }
    /** Fixed internal properties retain the CLI budgets without accepting user-controlled URL options. */
    public static PGSimpleDataSource loopbackSource(String url, String configuredUser, String configuredPassword) {
        requireLoopback(url,true);
        if(configuredUser==null || configuredUser.isBlank() || configuredPassword==null || configuredPassword.isBlank())
            throw new IllegalArgumentException("Private local credentials required");
        return new PGSimpleDataSource() {
            @Override public String getURL() { return url; }
            @Override public Connection getConnection() throws SQLException {
                return getConnection(configuredUser,configuredPassword);
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                requireLoopback(url,true);
                Properties properties=new Properties(); properties.setProperty("user",user); properties.setProperty("password",password);
                properties.setProperty("connectTimeout","5"); properties.setProperty("socketTimeout","30");
                properties.setProperty("ApplicationName","group33-local-recovery-copy");
                return DriverManager.getConnection(url,properties);
            }
        };
    }
    public static int run(String[] args, Map<String,String> env, Consumer<String> out, Consumer<String> error) {
        if(args.length!=1 || !Set.of("inspect-source","inspect-target","copy").contains(args[0])) {
            error.accept("Usage: manual-schema-copy inspect-source|inspect-target|copy");return 2;
        }
        try {
            requireLoopback(env.get("GROUP33_MIGRATION_URL"),true);
            MigrationSettings settings=MigrationSettings.Companion.from(env);
            if(!(settings.getSource() instanceof PGSimpleDataSource configured))throw new IllegalArgumentException("Unsupported connection type");
            PGSimpleDataSource local=loopbackSource(env.get("GROUP33_MIGRATION_URL"),configured.getUser(),configured.getPassword());
            String original=env.get("GROUP33_COPY_SOURCE_SCHEMA");identifier(original);identifier(settings.getSchema());
            if(original.equals(settings.getSchema()))throw new IllegalArgumentException("Distinct schemas required");
            String sourceFile=env.get("GROUP33_COPY_SOURCE_CATALOG_FILE"),targetFile=env.get("GROUP33_COPY_TARGET_CATALOG_FILE");
            if(args[0].equals("inspect-source"))writeReference(sourceFile,inspect(local,original,false));
            else if(args[0].equals("inspect-target"))writeReference(targetFile,inspect(local,settings.getSchema(),true));
            else {
                if(!"LOCAL_RESTORE_CATALOGS_REVIEWED_WRITERS_AND_DDL_PAUSED".equals(env.get("GROUP33_COPY_ACK")))throw new IllegalArgumentException("Explicit catalog/maintenance acknowledgement required");
                Reference a=readReference(sourceFile),b=readReference(targetFile);
                if(!a.schema.equals(original) || !b.schema.equals(settings.getSchema()))throw new IllegalArgumentException("Catalog scope mismatch");
                copy(local,a,b);
            }
            out.accept(args[0].equals("copy")?"Local recovery rows copied and verified; source/history unchanged":"Private local catalog reference created; review before copy");return 0;
        } catch(Exception ignored) {
            error.accept("Local recovery operation not confirmed; preserve both schemas and inspect state before retrying");return 1;
        }
    }
    public static void main(String[] args) { System.exit(run(args,System.getenv(),System.out::println,System.err::println)); }
}
