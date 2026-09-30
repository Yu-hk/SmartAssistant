import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;

/** One-method overlay; refuse a divergent method baseline and verify every other class member. */
public class RouterEntityContextPatch {
    static final String NAME = "addConversationContextIfNeeded";
    static final String DESC = "(Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;";
    static ClassNode read(byte[] bytes, int flags) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, flags); return node;
    }
    static MethodNode method(ClassNode node) {
        return node.methods.stream().filter(m -> m.name.equals(NAME) && m.desc.equals(DESC)).findFirst().orElseThrow();
    }
    static byte[] canonical(ClassNode node) {
        var writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    static byte[] fingerprint(MethodNode method) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "GuardedMethod", null, "java/lang/Object", null);
        method.accept(writer); writer.visitEnd(); return writer.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        byte[] live = Files.readAllBytes(Path.of(args[0]));
        var baseline = read(Files.readAllBytes(Path.of(args[1])), ClassReader.SKIP_DEBUG);
        var current = read(live, ClassReader.SKIP_DEBUG);
        var built = read(Files.readAllBytes(Path.of(args[2])), 0);
        if (!Arrays.equals(fingerprint(method(baseline)), fingerprint(method(current))))
            throw new IllegalStateException("Live context method diverges from tested baseline; no patch");
        var original = read(live, 0);
        int index = original.methods.indexOf(method(original));
        original.methods.set(index, method(built));
        var writer = new ClassWriter(new ClassReader(live), 0);
        original.accept(writer);
        byte[] patched = writer.toByteArray();
        var verification = read(patched, ClassReader.SKIP_DEBUG);
        if (!Arrays.equals(fingerprint(method(verification)), fingerprint(method(read(Files.readAllBytes(Path.of(args[2])), ClassReader.SKIP_DEBUG)))))
            throw new IllegalStateException("Replacement method changed");
        // Restore the old method then compare canonical classes: all other members must be identical.
        verification.methods.set(verification.methods.indexOf(method(verification)), method(current));
        if (!Arrays.equals(canonical(current), canonical(verification)))
            throw new IllegalStateException("Unexpected changes outside the context method");
        Files.write(Path.of(args[3]), patched, StandardOpenOption.CREATE_NEW);
        System.out.println("ONE_METHOD_VERIFIED all_other_members_preserved");
    }
}
