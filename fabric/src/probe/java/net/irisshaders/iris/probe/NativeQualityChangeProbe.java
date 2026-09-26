package net.irisshaders.iris.probe;

import net.irisshaders.iris.Iris;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

/** Exercises the same queued-options reload used by the profile UI. */
public final class NativeQualityChangeProbe {
    private NativeQualityChangeProbe() {}
    public static void run(Path run) throws Exception {
        var previousPack = Iris.getCurrentPack().orElseThrow();
        var previousPipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!previousPack.getProfileInfo().contains("HIGH")) throw new IllegalStateException("Quality regression must start on High");
        Path options = Iris.getShaderpacksDirectory().resolve(Iris.getCurrentPackName() + ".txt");
        byte[] previousOptions = Files.exists(options) ? Files.readAllBytes(options) : null;
        Properties ultra = new Properties();
        ultra.setProperty("ANISOTROPIC_FILTER", "8");
        ultra.setProperty("CLOUD_QUALITY", "3");
        ultra.setProperty("COLORED_LIGHTING", "512");
        ultra.setProperty("DETAIL_QUALITY", "3");
        ultra.setProperty("LIGHTSHAFT_QUALI_DEFINE", "3");
        ultra.setProperty("SHADOW_QUALITY", "3");
        ultra.setProperty("WORLD_SPACE_REFLECTIONS", "1");
        ultra.setProperty("shadowDistance", "256.0");
        Iris.queueShaderPackOptionsFromProperties(ultra);
        Iris.reload();
        if (Iris.getCurrentPack().orElseThrow() != previousPack) throw new AssertionError("Rejected profile replaced the running pack");
        if (Iris.getPipelineManager().getPipelineNullable() != previousPipeline) throw new AssertionError("Rejected profile destroyed the running pipeline");
        if (!Iris.getShaderPackOptionQueue().isEmpty()) throw new AssertionError("Rejected changes remained queued");
        if (Iris.getNativeSettingsRejection().isEmpty()) throw new AssertionError("No explanation for rejected Ultra settings");
        byte[] after = Files.exists(options) ? Files.readAllBytes(options) : null;
        if (!Arrays.equals(previousOptions, after)) throw new AssertionError("Rejected profile changed saved options");
        Files.writeString(run.resolve("quality-change-result.txt"), "PASS\nUltra rejected before replacing High\nPack and pipeline identities unchanged\nSaved options unchanged\n");
    }
}
