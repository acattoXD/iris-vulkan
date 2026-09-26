package iris.diagnostics;

import com.sun.tools.attach.VirtualMachine;
import java.nio.file.Path;

/** Run only for the explicitly authorized trial/user PID. No configuration or gameplay operations. */
public final class AttachReadOnlyAgent {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].matches("[1-9][0-9]*")) throw new IllegalArgumentException("PID agent.jar absolute-output.jsonl");
        Path agent = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]);
        if (!output.isAbsolute()) throw new IllegalArgumentException("Output path must be absolute");
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(agent.toString(), output.toString()); }
        finally { vm.detach(); }
        System.out.println("Started bounded30sample read-only DynamicFPS state capture for PID " + args[0]);
    }
}
