package com.example.smartassistant.common.memory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Read-only same-host rollback guard, shared by every optional profile consumer.
 * A missing/unreadable pin or database behind the independently persisted stream
 * disables profile use. It does not disable the customer's core business request.
 * Root/disk rollback and whole-host loss are outside this boundary.
 */
@Component
public class ProfileRecoveryGuard {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final Path root;
    private final UUID source;
    private static final String ZERO="0".repeat(64);

    @Autowired
    public ProfileRecoveryGuard(ObjectProvider<JdbcTemplate> jdbc,
            @Value("${profile.control.enabled:false}") boolean enabled,
            @Value("${profile.control.directory:/profile-control}") String directory,
            @Value("${profile.control.source-id:}") String source) {
        this(jdbc.getIfAvailable(),enabled,Path.of(directory),enabled?UUID.fromString(source):null);
    }
    public ProfileRecoveryGuard(JdbcTemplate jdbc,boolean enabled,Path root,UUID source) {
        this.jdbc=jdbc;this.enabled=enabled;this.root=root;this.source=source;
    }
    public void requireSafe() {
        if(!enabled) return;
        try { verify(); }
        catch(Exception unavailable) { throw new Unavailable(); }
    }
    private void verify() throws IOException {
        if(jdbc==null || source==null || !root.isAbsolute() || root.getParent()==null
                || !root.toRealPath().equals(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("Unsafe control root");
        if(!read("source.pin").equals("1|"+source+"\n")) throw new IOException("Wrong source");
        String acknowledgement=read("HEAD");
        String[] head=acknowledgement.strip().split("\\|",-1);
        if(head.length!=3 || !head[0].equals("1") || !head[1].matches("0|[1-9][0-9]*")
                || !head[2].matches("[0-9a-f]{64}")) throw new IOException("Invalid acknowledgement");
        long acknowledged=Long.parseLong(head[1]),highest=0;
        int count=0;
        try(var entries=Files.newDirectoryStream(root)) {
            for(Path entry:entries) {
                if(++count>10103) throw new IOException("Control stream capacity exceeded");
                String name=entry.getFileName().toString();
                if(name.matches("[0-9]{20}\\.ctl")) highest=Math.max(highest,Long.parseLong(name.substring(0,20)));
                else if(!Set.of("source.pin","HEAD","writer.lock").contains(name)
                        && !name.matches("pending-[0-9a-f-]{36}\\.tmp")) throw new IOException("Unexpected control entry");
            }
        }
        if(highest>10000 || acknowledged>highest || (acknowledged==0 && !head[2].equals(ZERO)))
            throw new IOException("Missing control history");
        // Also check unacknowledged published suffixes: a crash before HEAD must
        // not allow a database restore to silently forget an accepted pause.
        var header=jdbc.queryForMap("SELECT source_id,last_sequence FROM profile_control_source WHERE singleton");
        if(!source.equals(header.get("source_id")) || ((Number)header.get("last_sequence")).longValue()<highest)
            throw new IOException("Database behind independent controls");
        String previous=ZERO;
        // Bounded, full prefix validation. Never trust an equal sequence alone:
        // another history branch may have reused the same sequence after restore.
        var rows=jdbc.queryForList("SELECT sequence,event_id,source_id,user_id,generation,analysis_enabled FROM profile_control_outbox WHERE sequence<=? ORDER BY sequence",highest);
        if(rows.size()!=highest) throw new IOException("Incomplete database history");
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);long sequence=i+1L;
            String data=read(String.format(Locale.ROOT,"%020d.ctl",sequence));
            String expected="1|"+source+"|"+sequence+"|"+row.get("event_id")+"|"+row.get("user_id")+"|"
                    +row.get("generation")+"|"+(Boolean.TRUE.equals(row.get("analysis_enabled"))?1:0)+"|"+previous+"\n";
            if(((Number)row.get("sequence")).longValue()!=sequence || !source.equals(row.get("source_id")) || !data.equals(expected))
                throw new IOException("Control history mismatch");
            previous=sha(data);
            if(sequence==acknowledged && !previous.equals(head[2])) throw new IOException("Invalid acknowledgement digest");
        }
        // Concurrent append/ACK is retried on the next optional profile attempt.
        if(!read("HEAD").equals(acknowledgement)) throw new IOException("Control history changed");
    }
    private String read(String name) throws IOException {
        Path file=root.resolve(name);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                || !Files.getOwner(file).equals(Files.getOwner(root))
                || !Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))) throw new IOException("Unsafe control file");
        try(var input=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            byte[] data=input.readNBytes(513);
            if(data.length>512) throw new IOException("Invalid control file size");
            return new String(data,StandardCharsets.US_ASCII);
        }
    }
    private static String sha(String data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.US_ASCII))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("Optional profile recovery protection unavailable"); }
    }
}
