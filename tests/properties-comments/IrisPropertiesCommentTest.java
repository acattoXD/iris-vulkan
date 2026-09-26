package net.irisshaders.iris.test;

import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

/** Executes the real properties preprocessor with no game or GPU. */
public final class IrisPropertiesCommentTest {
    private static int checks;
    private static String process(String source) {
        return PropertiesPreprocessor.preprocessSource(source, List.of(new StringPair("MC_VERSION", "12603")));
    }
    private static Properties properties(String source) throws Exception {
        Properties result = new Properties();
        result.load(new StringReader(process(source)));
        return result;
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String comment = new String("# дропнутых (на земле) предметов, которые\n".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        String fixture = "item.1=torch\n" + comment + "item.2=black_candle\n";
        if (args.length > 0 && args[0].equals("--original")) {
            check(!"black_candle".equals(properties(fixture).getProperty("item.2")), "Original control no longer reproduces truncation");
            System.out.println("PASS: original preprocessor reproduces truncated ID map");
            return;
        }
        for (String newline : List.of("\n", "\r\n", "\r")) {
            Properties result = properties(fixture.replace("\n", newline));
            check(result.size() == 2, "Comment tail became a property");
            check("torch".equals(result.getProperty("item.1")), "First mapping changed");
            check("black_candle".equals(result.getProperty("item.2")), "Mapping after comment lost");
            result = properties(("#if MC_VERSION >= 12603\nitem.3=lantern\n#else\nitem.3=stone\n#endif\nitem.4=redstone \\\t  \n torch\n").replace("\n", newline));
            check("lantern".equals(result.getProperty("item.3")), "Conditional macro changed");
            check("redstone torch".equals(result.getProperty("item.4")), "Continuation changed");
        }
        check(process("# normal comment\n#unrecognized comment\n").isBlank(), "Ordinary comment leaked");
        check("Test Test".equals(properties("option=Test \\\nTest").getProperty("option")), "Legacy continuation changed");
        if (args.length > 0) {
            try (ZipFile pack = new ZipFile(Path.of(args[0]).toFile())) {
                byte[] bytes = pack.getInputStream(pack.getEntry("shaders/item.properties")).readAllBytes();
                Properties expected = new Properties();
                expected.load(new StringReader(new String(bytes, StandardCharsets.ISO_8859_1)));
                Properties actual = properties(new String(bytes, StandardCharsets.ISO_8859_1));
                check(expected.equals(actual), "Actual pack ID map changed or was truncated");
                check("black_candle".equals(actual.getProperty("item.13145")), "Actual pack final mapping absent");
                System.out.println("Actual pack item mappings retained: " + actual.size());
            }
        }
        System.out.println("PASS: " + checks + " properties comment/line-ending/macro checks");
    }
}
