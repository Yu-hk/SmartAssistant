package com.example.smartassistant.consumer.service.recommendation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Own-user legacy derived files only. Never follows links or removes chat/document folders. */
@Service
public class ProfileLegacyFiles {
    private final Path root;
    private final boolean enabled;
    public ProfileLegacyFiles(@Value("${app.data.dir:data/users}") String root,
            @Value("${profile.cleanup.legacy-enabled:false}") boolean enabled) {
        this.root=Path.of(root).toAbsolutePath().normalize();this.enabled=enabled;
    }
    public boolean enabled(){return enabled;}
    public void clean(long user) {
        if(!enabled || user<=0 || root.getParent()==null) throw new IllegalStateException("Legacy cleanup unavailable");
        try {
            // Check every existing ancestor; an absent directory is safe only when
            // none of its ancestors redirects to another storage root.
            for(Path path=root;path!=null;path=path.getParent()) {
                if(Files.exists(path,LinkOption.NOFOLLOW_LINKS) && (!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)
                        || !path.toRealPath().equals(path))) throw new IOException("Unsafe legacy root");
            }
            if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return;
            Path owner=root.resolve(Long.toString(user));
            if(!Files.exists(owner,LinkOption.NOFOLLOW_LINKS)) return;
            if(!Files.isDirectory(owner,LinkOption.NOFOLLOW_LINKS) || !owner.toRealPath().equals(owner)) throw new IOException("Unsafe legacy owner");
            List<Path> files=new ArrayList<>();
            try(var entries=Files.newDirectoryStream(owner)) {
                int scanned=0;
                for(Path item:entries) {
                    String name=item.getFileName().toString();
                    if(++scanned>1000 || (name.endsWith("-memory.md") && !Set.of("order-memory.md","product-memory.md","general-memory.md").contains(name)))
                        throw new IOException("Unknown legacy owner inventory");
                }
            }
            for(String name:List.of("preferences.json","memories.json","order-memory.md","product-memory.md","general-memory.md")) {
                Path file=owner.resolve(name);if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) files.add(file);
            }
            Path summaries=owner.resolve("memories");
            if(Files.exists(summaries,LinkOption.NOFOLLOW_LINKS)) {
                if(!Files.isDirectory(summaries,LinkOption.NOFOLLOW_LINKS) || !summaries.toRealPath().equals(summaries)) throw new IOException("Unsafe legacy summaries");
                try(var entries=Files.newDirectoryStream(summaries)) {
                    for(Path file:entries) {
                        if(files.size()>=500 || !file.getFileName().toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[a-zA-Z0-9_-]{1,128}\\.md"))
                            throw new IOException("Unknown legacy summary format");
                        files.add(file);
                    }
                }
            }
            // Complete the allowlist/type/size checks before the first deletion.
            long bytes=0;
            for(Path file:files) {
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file)
                        || !file.startsWith(owner) || (bytes+=Files.size(file))>64*1024*1024) throw new IOException("Unsafe legacy file");
            }
            for(Path file:files) {
                if(!file.toRealPath().equals(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Legacy file changed");
                Files.delete(file);
            }
        } catch(IOException unavailable) { throw new IllegalStateException("Legacy profile cleanup incomplete",unavailable); }
    }
}
