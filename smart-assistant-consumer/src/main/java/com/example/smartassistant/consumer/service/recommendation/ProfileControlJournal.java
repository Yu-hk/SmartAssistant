package com.example.smartassistant.consumer.service.recommendation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Append-only, fsynced same-host control journal. Contains no profile/chat body.
 * An existing operator-created source pin is mandatory: never auto-reinitialize
 * a lost journal from a possibly restored database. Not protection against root
 * tampering, filesystem rollback or host/disk loss.
 */
public final class ProfileControlJournal {
    public record Event(long sequence,UUID eventId,UUID sourceId,long userId,long generation,boolean enabled) {
        String line(String previous) {
            if(sequence<=0 || eventId==null || sourceId==null || userId<=0 || generation<=0)
                throw new IllegalArgumentException("Invalid control event");
            return "1|"+sourceId+"|"+sequence+"|"+eventId+"|"+userId+"|"+generation+"|"+(enabled?1:0)+"|"+previous+"\n";
        }
    }
    private static final String ZERO="0".repeat(64);
    private final Path root;
    private final UUID source;
    public ProfileControlJournal(Path root,UUID source) {
        if(root==null || !root.isAbsolute() || root.normalize().getParent()==null || source==null)
            throw new IllegalArgumentException("Explicit control root and source required");
        this.root=root.normalize();this.source=source;
    }
    public synchronized long sync(List<Event> events) throws IOException {
        verifyRoot();
        if(events==null || events.size()>10000) throw new IOException("Control stream limit exceeded");
        Path lock=root.resolve("writer.lock");
        try(var channel=FileChannel.open(lock,Set.of(CREATE,WRITE,LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            verifyFile(lock);
            try(var lease=channel.tryLock()) {
                if(lease==null) throw new IOException("Control journal busy");
                return syncLocked(events);
            }
        }
    }
    private long syncLocked(List<Event> events) throws IOException {
        Set<String> present=new HashSet<>();
        List<Path> abandoned=new ArrayList<>();
        try(var entries=Files.newDirectoryStream(root)) {
            for(Path item:entries) {
                String name=item.getFileName().toString();
                if(name.equals("source.pin") || name.equals("writer.lock") || name.equals("HEAD")) continue;
                if(name.matches("pending-[0-9a-f-]{36}\\.tmp")) {
                    verifyFile(item);abandoned.add(item);
                    if(abandoned.size()>100) throw new IOException("Too many unfinished journal writes");
                    continue;
                }
                if(!name.matches("[0-9]{20}\\.ctl")) throw new IOException("Unexpected journal entry");
                present.add(name);
                if(present.size()>10000) throw new IOException("Control stream limit exceeded");
            }
        }
        // DB rollback/truncation can never overwrite a later independent event.
        if(present.size()>events.size()) throw new IOException("Database control stream behind journal");
        String hash=ZERO;Map<Long,String> hashes=new HashMap<>();hashes.put(0L,ZERO);
        List<byte[]> encoded=new ArrayList<>();Map<Long,Long> generations=new HashMap<>();Set<UUID> identities=new HashSet<>();
        for(int i=0;i<events.size();i++) {
            Event event=events.get(i);
            long previousGeneration=generations.getOrDefault(event.userId(),0L);
            if(event.sequence()!=i+1L || !source.equals(event.sourceId()) || !identities.add(event.eventId())
                    || event.generation()!=previousGeneration+1 || (previousGeneration==0 && event.enabled()))
                throw new IOException("Control source, identity or sequence mismatch");
            generations.put(event.userId(),event.generation());
            String name=String.format(Locale.ROOT,"%020d.ctl",event.sequence());
            Path target=root.resolve(name);byte[] data=event.line(hash).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if(present.contains(name)) {
                if(!Arrays.equals(readSmall(target),data)) throw new IOException("Control history mismatch");
                present.remove(name);
            } else {
                // A suffix is retryable; a hole before an existing later file is not.
                if(!present.isEmpty()) throw new IOException("Control journal sequence hole");
            }
            encoded.add(data);
            hash=sha256(data);hashes.put(event.sequence(),hash);
        }
        Path head=root.resolve("HEAD");
        if(Files.exists(head,LinkOption.NOFOLLOW_LINKS)) {
            String[] fields=new String(readSmall(head),java.nio.charset.StandardCharsets.US_ASCII).strip().split("\\|",-1);
            try {
                if(fields.length!=3 || !fields[0].equals("1") || !fields[1].matches("0|[1-9][0-9]*")
                    || !Objects.equals(hashes.get(Long.parseLong(fields[1])),fields[2])) throw new IOException("Invalid journal acknowledgement");
            } catch(NumberFormatException invalid) { throw new IOException("Invalid journal acknowledgement"); }
        }
        for(int i=0;i<events.size();i++) {
            Path target=root.resolve(String.format(Locale.ROOT,"%020d.ctl",i+1L));
            if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) atomicWrite(target,encoded.get(i),false);
        }
        // Durable files alone are not a delivery ACK. Force them and the directory
        // again on retries, including after a previous directory-fsync failure.
        for(Event event:events) {
            try(var file=FileChannel.open(root.resolve(String.format(Locale.ROOT,"%020d.ctl",event.sequence())),WRITE,LinkOption.NOFOLLOW_LINKS)) { file.force(true); }
        }
        forceDirectory();
        byte[] ack=("1|"+events.size()+"|"+hash+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        atomicWrite(head,ack,true);
        // Only after all committed history was verified and ACK durably published.
        for(Path temporary:abandoned) Files.delete(temporary);
        forceDirectory();
        return events.size();
    }
    private void atomicWrite(Path target,byte[] data,boolean replace) throws IOException {
        Path temporary=root.resolve("pending-"+UUID.randomUUID()+".tmp");
        try {
            createDurably(temporary,data);
            if(replace) Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            forceDirectory();
        } finally { Files.deleteIfExists(temporary); }
    }
    private void verifyRoot() throws IOException {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root)) throw new IOException("Unsafe control directory");
        if(!Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("Control directory must be private");
        if(!new String(readSmall(root.resolve("source.pin")),java.nio.charset.StandardCharsets.US_ASCII).equals("1|"+source+"\n")) throw new IOException("Independent source pin mismatch");
    }
    private void verifyFile(Path file) throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                || !Files.getOwner(file).equals(Files.getOwner(root))
                || !Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))) throw new IOException("Unsafe control file");
    }
    private byte[] readSmall(Path file) throws IOException {
        verifyFile(file);
        if(Files.size(file)>512) throw new IOException("Invalid control file size");
        try(var input=Files.newInputStream(file,READ,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(513);
            if(bytes.length>512) throw new IOException("Invalid control file size");
            return bytes;
        }
    }
    private void createDurably(Path file,byte[] data) throws IOException {
        try(var channel=FileChannel.open(file,Set.of(CREATE_NEW,WRITE,LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer buffer=ByteBuffer.wrap(data);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true);
        }
        forceDirectory();
    }
    private void forceDirectory() throws IOException { try(var directory=FileChannel.open(root,READ)) { directory.force(true); } }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
