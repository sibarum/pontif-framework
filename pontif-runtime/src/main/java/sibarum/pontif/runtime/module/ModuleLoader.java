package sibarum.pontif.runtime.module;

import sibarum.pontif.ir.CompileException;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.PontifParser;
import sibarum.pontif.parser.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers and parses every {@code .ptf} source under a project root into a
 * {@code moduleName → IrModule} map, keyed by each file's {@code module a.b}
 * declaration. File I/O lives here (pontif-runtime), keeping pontif-ir/core
 * I/O-free. Several files may declare the same namespace — they are folded into
 * one module by {@link NamespaceAssembler} (the shared merge policy).
 */
public final class ModuleLoader {

    private ModuleLoader() {}

    /** Loads all modules under {@code rootDir}, in stable (sorted-path) order. */
    public static Map<String, IrModule> load(Path rootDir)
            throws IOException, ParseException, CompileException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(rootDir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".ptf"))
                    .sorted()
                    .toList();
        }
        // Group parsed files by declared namespace (sorted-path order preserved), then
        // fold each group into one module — the same rule the resolver uses per entry.
        Map<String, List<IrModule>> byName = new LinkedHashMap<>();
        for (Path file : files) {
            String source = Files.readString(file);
            String label = rootDir.relativize(file).toString().replace('\\', '/');
            IrModule module = PontifParser.parseModule(source, label);
            byName.computeIfAbsent(module.name(), k -> new ArrayList<>()).add(module);
        }
        Map<String, IrModule> modules = new LinkedHashMap<>();
        for (Map.Entry<String, List<IrModule>> e : byName.entrySet()) {
            modules.put(e.getKey(), NamespaceAssembler.merge(e.getKey(), e.getValue()));
        }
        return modules;
    }
}
