package net.irisshaders.iris.uniforms.custom;

import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exercise shared dependencies, late requests and changing providers without a GL context. */
public final class CustomUniformUpdatePlanTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        float[] value = {2};
        int[] calls = new int[3];
        var inputs = new CustomUniformFixedInputUniformsHolder.Builder();
        inputs.uniform1f(UniformUpdateFrequency.PER_FRAME, "source", () -> { calls[0]++; return value[0]; });
        inputs.uniform1f(UniformUpdateFrequency.PER_FRAME, "later", () -> { calls[1]++; return value[0] * 10; });
        inputs.uniform1f(UniformUpdateFrequency.PER_FRAME, "unused", () -> { calls[2]++; return 99f; });
        var builder = new CustomUniforms.Builder();
        builder.addVariable("float", "left", "source + 1", false);
        builder.addVariable("float", "right", "source * 3", false);
        builder.addVariable("float", "joined", "left + right", true);
        builder.addVariable("float", "extended", "joined + later", true);
        var provider = builder.build(inputs.build());
        List<String> first = List.of("joined", "unknownNativeField");
        provider.beginFrame();
        provider.updateFor(first);
        check(number(provider, "joined") == 9, "Shared graph is evaluated in dependency order");
        for (int draw = 0; draw < 1000; draw++) provider.updateFor(first);
        check(calls[0] == 1 && calls[1] == 0 && calls[2] == 0, "Same-frame draws neither reevaluate nor request unrelated suppliers");
        provider.updateFor(List.of("extended"));
        check(number(provider, "extended") == 29 && calls[0] == 1 && calls[1] == 1, "Late program requests evaluate only new dependencies");
        value[0] = 5;
        provider.beginFrame();
        provider.updateFor(first);
        check(number(provider, "joined") == 21 && calls[0] == 2 && calls[1] == 1, "The next frame refreshes values without eager unrelated work");
        var mutable = new ArrayList<>(List.of("joined"));
        provider.updateFor(mutable);
        mutable.set(0, "extended");
        provider.updateFor(mutable);
        check(number(provider, "extended") == 71 && calls[1] == 2, "Mutating a request list cannot reuse stale dependencies");
        var otherInputs = new CustomUniformFixedInputUniformsHolder.Builder();
        otherInputs.uniform1f(UniformUpdateFrequency.PER_FRAME, "joined", () -> 400f);
        var other = new CustomUniforms.Builder().build(otherInputs.build());
        other.beginFrame(); other.updateFor(first);
        check(number(other, "joined") == 400 && number(provider, "joined") == 21, "Providers keep independent graphs and frame state");
        var cacheField = CustomUniforms.class.getDeclaredField("updatePlans");
        cacheField.setAccessible(true);
        Map<?, ?> plans = (Map<?, ?>) cacheField.get(provider);
        int retained = plans.size();
        for (int draw = 0; draw < 1000; draw++) provider.updateFor(first);
        check(plans.size() == retained, "Stable native program lists reuse their graph plans");
        for (int i = 0; i < 1200; i++) provider.updateFor(List.of("missing" + i));
        check(plans.size() <= 512, "Diagnostic and mutable request sequences remain bounded");
        provider.optimise();
        check(plans.isEmpty(), "Changing uniformOrder invalidates compiled plans");
        System.out.println("PASS: " + checks + " custom-uniform dependency and lifetime checks");
    }
    private static float number(CustomUniforms provider, String name) {
        return ((Number) provider.lookup(name).orElseThrow().value()).floatValue();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
