import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateFactory;
import java.util.*;

/** Runtime only. No provider connection, fallback, DDL or credential-bearing diagnostics. */
public final class RenderConfigWriter {
    private static final Set<String> KEYS = Set.of("app.persistence.jdbc-url", "app.persistence.schema",
        "app.persistence.username", "app.persistence.password", "app.jwt.secret", "app.supabase.project-ref",
        "app.photo.supabase.origin", "app.photo.supabase.bucket");
    private static byte[] decode(String name, int max) {
        String value = Objects.requireNonNull(System.getenv(name));
        if (value.length() > ((max + 2) / 3) * 4) throw new IllegalArgumentException();
        byte[] data = Base64.getDecoder().decode(value);
        if (data.length == 0 || data.length > max || !Base64.getEncoder().encodeToString(data).equals(value))
            throw new IllegalArgumentException();
        return data;
    }
    private static void write(Path path, byte[] data) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(path, data, StandardOpenOption.WRITE);
    }
    public static void main(String[] args) {
        Path directory = null;
        String stage = "port";
        try {
            String port = Objects.requireNonNull(System.getenv("PORT"));
            if (!port.matches("[1-9][0-9]{0,4}") || Integer.parseInt(port) < 1024 || Integer.parseInt(port) > 65535)
                throw new IllegalArgumentException();
            stage = "settings";
            byte[] settings = decode("GROUP33_RENDER_SETTINGS_B64", 16384);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(settings)).toString();
            Properties props = new Properties(); props.load(new StringReader(text));
            if (!props.stringPropertyNames().equals(KEYS) || text.lines().count() != KEYS.size()
                || props.values().stream().anyMatch(v -> v.toString().isBlank())
                || props.getProperty("app.jwt.secret").getBytes(StandardCharsets.UTF_8).length < 32)
                throw new IllegalArgumentException();
            stage = "receipt-key";
            byte[] receipt = decode("GROUP33_RENDER_RECEIPT_KEY_B64", 32);
            if (receipt.length != 32) throw new IllegalArgumentException();
            stage = "database-ca";
            byte[] ca = decode("GROUP33_RENDER_DATABASE_CA_B64", 65536);
            if (CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(ca)).isEmpty())
                throw new IllegalArgumentException();
            stage = "storage-key";
            String secret = Objects.requireNonNull(System.getenv("GROUP33_RENDER_STORAGE_SECRET"));
            if (!secret.matches("sb_secret_[A-Za-z0-9_-]{16,256}")) throw new IllegalArgumentException();
            stage = "private-files";
            directory = Files.createTempDirectory("group33-render-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            write(directory.resolve("receipt.key"), receipt);
            write(directory.resolve("database-ca.crt"), ca);
            write(directory.resolve("storage-secret.key"), secret.getBytes(StandardCharsets.UTF_8));
            props.setProperty("app.persistence.mode", "postgres-supabase");
            props.setProperty("app.photo.enabled", "true"); props.setProperty("app.plan.enabled", "true");
            props.setProperty("app.photo.storage-mode", "supabase");
            props.setProperty("app.persistence.receipt-key-file", directory.resolve("receipt.key").toString());
            props.setProperty("app.persistence.ssl-root-cert", directory.resolve("database-ca.crt").toString());
            props.setProperty("app.photo.supabase.secret-key-file", directory.resolve("storage-secret.key").toString());
            Path output = directory.resolve("application.properties"); write(output, new byte[0]);
            try (var out = Files.newOutputStream(output)) { props.store(out, "Private runtime only; never commit"); }
            System.out.println(output); // Captured by start.sh; never credential values.
        } catch (Exception ignored) {
            if (directory != null) {
                for (String name : List.of("application.properties", "receipt.key", "database-ca.crt", "storage-secret.key"))
                    try { Files.deleteIfExists(directory.resolve(name)); } catch (Exception unused) { }
                try { Files.deleteIfExists(directory); } catch (Exception unused) { }
            }
            System.err.println("Render private configuration is invalid; verify the four private variables and PORT (" + stage + ")");
            System.exit(64);
        }
    }
}
