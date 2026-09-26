package net.irisshaders.iris.uniforms.custom;

import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/** CPU-only microbenchmark. This is not an FPS or GPU benchmark. */
public final class CustomUniformUpdateBenchmark {
    public static void main(String[] args) {
        var input = new CustomUniformFixedInputUniformsHolder.Builder();
        float[] value = {1};
        input.uniform1f(UniformUpdateFrequency.PER_FRAME, "source", () -> value[0]);
        var builder = new CustomUniforms.Builder();
        var names = new ArrayList<String>();
        for (int i = 0; i < 64; i++) {
            String dependency = i == 0 ? "source" : "v" + (i - 1);
            builder.addVariable("float", "v" + i, dependency + " + 1", true);
            if (i > 48) names.add("v" + i);
        }
        var custom = builder.build(input.build());
        List<String> stable = List.copyOf(names);
        for (int frame = 0; frame < 30; frame++) {
            custom.beginFrame();
            for (int draw = 0; draw < 100; draw++) custom.updateFor(stable);
        }
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        mx.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();
        long allocated = mx.getThreadAllocatedBytes(id), start = System.nanoTime();
        for (int frame = 0; frame < 300; frame++) {
            value[0] = frame;
            custom.beginFrame();
            for (int draw = 0; draw < 100; draw++) custom.updateFor(stable);
        }
        long elapsed = System.nanoTime() - start, bytes = mx.getThreadAllocatedBytes(id) - allocated;
        float result = ((Number) custom.lookup("v63").orElseThrow().value()).floatValue();
        if (result != 363) throw new AssertionError("Incorrect expression value: " + result);
        System.out.println("{\"calls\":30000,\"frames\":300,\"wallMs\":" + elapsed / 1e6
            + ",\"allocatedBytes\":" + bytes + ",\"result\":" + result + "}");
    }
}
