import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.plugins.input.insns.InsnIndexType;
import jadx.api.plugins.input.insns.Opcode;

// Run with the official JADX all-in-one JAR on the Java classpath.
public class InspectApk {
    public static void main(String[] argv) throws Exception {
        if (argv.length < 3) {
            throw new IllegalArgumentException("Usage: InspectApk.java APK OUT --manifest|--literal REGEX|--invoke REGEX|CLASS [CLASS ...]");
        }
        Path output = Files.createDirectories(Path.of(argv[1]));
        boolean manifest = argv[2].equals("--manifest");
        boolean literal = argv[2].equals("--literal");
        boolean invoke = argv[2].equals("--invoke");
        if ((literal || invoke) && argv.length != 4) {
            throw new IllegalArgumentException("Supply one regex for the reference search");
        }
        JadxArgs args = new JadxArgs();
        args.setInputFile(Path.of(argv[0]).toFile());
        args.setOutDir(output.toFile());
        args.setThreadsCount(2);
        args.setSkipResources(!manifest);
        args.setSkipSources(manifest);
        try (JadxDecompiler jadx = new JadxDecompiler(args)) {
            jadx.load();
            if (manifest) {
                var file = jadx.getResources().stream()
                    .filter(resource -> resource.getOriginalName().equals("AndroidManifest.xml"))
                    .findFirst().orElseThrow();
                Files.writeString(output.resolve("AndroidManifest.xml"), file.loadContent().getText().getCodeStr());
                return;
            }
            if (literal || invoke) {
                Pattern pattern = Pattern.compile(argv[3]);
                try (var log = Files.newBufferedWriter(output.resolve(invoke ? "invoke-references.txt" : "literal-references.txt"))) {
                    findReferences(jadx, pattern, log, invoke);
                }
                return;
            }
            for (int i = 2; i < argv.length; i++) {
                String name = argv[i];
                var cls = jadx.searchJavaClassByOrigFullName(name);
                if (cls == null) {
                    System.out.println("Class missing: " + name);
                    continue;
                }
                Path destination = output.resolve(name + ".java");
                Files.writeString(destination, cls.getCode());
                System.out.println("Saved " + destination.getFileName());
            }
        }
    }

    private static void findReferences(JadxDecompiler jadx, Pattern pattern, BufferedWriter log, boolean invoke) {
        for (var cls : jadx.getRoot().getClasses(true)) {
            for (var method : cls.getMethods()) {
                var reader = method.getCodeReader();
                if (reader == null) {
                    continue;
                }
                reader.visitInstructions(insn -> {
                    if (invoke ? insn.getIndexType() != InsnIndexType.METHOD_REF : insn.getOpcode() != Opcode.CONST_STRING) {
                        return;
                    }
                    insn.decode();
                    String value;
                    if (invoke) {
                        var called = insn.getIndexAsMethod();
                        called.load();
                        value = called.getParentClassType() + "->" + called.getName();
                    } else {
                        value = insn.getIndexAsString();
                    }
                    if (value != null && pattern.matcher(value).find()) {
                        try {
                            log.write(cls.getRawName() + "#" + method.getMethodInfo().getShortId()
                                + " @" + Integer.toHexString(insn.getOffset()) + " "
                                + value.replace("\r", "\\r").replace("\n", "\\n") + "\n");
                        } catch (IOException err) {
                            throw new UncheckedIOException(err);
                        }
                    }
                });
            }
        }
    }
}
