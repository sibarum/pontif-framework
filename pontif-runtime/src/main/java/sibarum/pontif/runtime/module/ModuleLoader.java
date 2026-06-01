package sibarum.pontif.runtime.module;

import sibarum.pontif.core.Origin;
import sibarum.pontif.ir.IrModule;
import sibarum.pontif.parser.AltParser;
import sibarum.pontif.parser.ParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers and parses every {@code .ptf} source under a project root into a
 * {@code moduleName → IrModule} map, keyed by each file's {@code module a.b}
 * declaration. File I/O lives here (pontif-runtime), keeping pontif-ir/core
 * I/O-free. Two files declaring the same module name is a hard error.
 */
public final class ModuleLoader {

    private ModuleLoader() {}

    /** Loads all modules under {@code rootDir}, in stable (sorted-path) order. */
    public static Map<String, IrModule> load(Path rootDir) throws IOException, ParseException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(rootDir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".ptf"))
                    .sorted()
                    .toList();
        }
        Map<String, IrModule> modules = new LinkedHashMap<>();
        for (Path file : files) {
            String source = Files.readString(file);
            String label = rootDir.relativize(file).toString().replace('\\', '/');
            IrModule module = AltParser.parseModule(source, label);
            if (modules.containsKey(module.name())) {
                throw new ParseException(
                        "Duplicate module '" + module.name() + "' — declared by more than one "
                                + "file under the project root (each module name must be unique).",
                        Origin.NONE);
            }
            modules.put(module.name(), module);
        }
        return modules;
    }
}
