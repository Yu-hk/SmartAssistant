import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.*;

/** Read-only resolver probe; cache settings affect only this isolated process. */
public class ContainerDnsProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length % 2 != 1) throw new IllegalArgumentException("Invalid probe arguments");
        int repeats = Integer.parseInt(args[0]);
        if (repeats < 1 || repeats > 200) throw new IllegalArgumentException("Invalid repeats");
        for (int i=1;i<args.length;i+=2) {
            if (!args[i].matches("[a-zA-Z0-9.-]{1,253}") || !args[i+1].matches("[0-9.]+|ANY|MISSING"))
                throw new IllegalArgumentException("Invalid target");
        }
        Security.setProperty("networkaddress.cache.ttl", "0");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        List<String> resolvers = new ArrayList<>();
        if (Files.exists(Path.of("/etc/resolv.conf")))
            for (String line: Files.readAllLines(Path.of("/etc/resolv.conf")))
                if (line.strip().startsWith("nameserver ")) resolvers.add(line.strip().split("\\s+")[1]);
        int total=0, failures=0;
        List<String> rows = new ArrayList<>();
        for (int i=1;i<args.length;i+=2) {
            int missing=0, wrong=0;
            long start=System.nanoTime();
            for (int n=0;n<repeats;n++) {
                try {
                    InetAddress[] found=InetAddress.getAllByName(args[i]);
                    String expected=args[i+1];
                    boolean correct=found.length>0 && (expected.equals("ANY") ||
                        Arrays.stream(found).anyMatch(a -> a.getHostAddress().equals(expected)));
                    if (!correct) wrong++;
                } catch (UnknownHostException e) {
                    if (!args[i+1].equals("MISSING")) missing++;
                }
                Thread.sleep(10);
            }
            total+=repeats;failures+=missing+wrong;
            rows.add("{\"host\":\""+args[i]+"\",\"attempts\":"+repeats+
                ",\"unresolved\":"+missing+",\"wrong\":"+wrong+",\"duration_ms\":"+
                (System.nanoTime()-start)/1000000+"}");
        }
        System.out.println("{\"schema\":1,\"attempts\":"+total+",\"failures\":"+failures+
            ",\"resolvers\":["+resolvers.stream().map(s->"\""+s+"\"").reduce((a,b)->a+","+b).orElse("")+
            "],\"rows\":["+String.join(",",rows)+"]}");
        if (failures>0) System.exit(1);
    }
}
