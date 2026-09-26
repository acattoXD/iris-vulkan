package net.irisshaders.iris.vulkan;

import com.google.gson.GsonBuilder;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

/** Offline experiment fixtures only: changes no production shader, shader pack or dispatch policy. */
public final class ComplementaryKernelExport {
	private static final Pattern LOCAL = Pattern.compile("layout\\s*\\(\\s*local_size_x\\s*=\\s*8\\s*,\\s*local_size_y\\s*=\\s*8\\s*,\\s*local_size_z\\s*=\\s*8\\s*\\)\\s*in\\s*;");
	private static final Pattern GROUPS = Pattern.compile("const\\s+ivec3\\s+workGroups\\s*=\\s*ivec3\\(\\s*32\\s*,\\s*16\\s*,\\s*32\\s*\\)\\s*;");
	private static final int[][] SHAPES = {{8,8,8}, {8,4,4}, {8,8,4}, {4,4,4}, {32,2,2}};
	private static final int[] EXTENT = {256,128,256};

	public static void main(String[] args) throws Exception {
		Path root = Path.of(args[0]);
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(root.resolve("expanded/preprocessed.properties"))) { properties.load(reader); }
		Map<String,String> formats = new LinkedHashMap<>();
		List<Map<String,Object>> images = new ArrayList<>();
		for (String key : new TreeSet<>(properties.stringPropertyNames())) {
			if (!key.startsWith("image.")) continue;
			String name = key.substring("image.".length());
			String[] parts = properties.getProperty(key).trim().split("\\s+");
			if (parts.length < 8) throw new IllegalStateException("Unsupported image declaration " + key);
			formats.put(name, parts[2]);
			List<Integer> dimensions = new ArrayList<>();
			for (int i = 6; i < parts.length; i++) dimensions.add(Integer.parseInt(parts[i]));
			images.add(Map.of("name",name,"sampler",parts[0],"format",parts[2],"dimensions",dimensions,
				"clearEachFrame",Boolean.parseBoolean(parts[4]),"relative",Boolean.parseBoolean(parts[5]),"declaration",properties.getProperty(key)));
		}
		List<Object> kernels = new ArrayList<>();
		for (String dimension : List.of("world0", "world-1", "world1")) {
			Path expandedPath = root.resolve("expanded/" + dimension + "/shadowcomp.csh");
			String expanded = Files.readString(expandedPath);
			String source = expanded.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\r\\n]*", "");
			if (LOCAL.matcher(source).results().count() != 1 || GROUPS.matcher(source).results().count() != 1)
				throw new IllegalStateException("Unexpected baseline grid for " + dimension);
			if (Pattern.compile("\\b(?:shared|barrier|memoryBarrier\\w*|groupMemoryBarrier|atomic\\w*|imageAtomic\\w*|gl_WorkGroupID|gl_WorkGroupSize|gl_NumWorkGroups|gl_Subgroup\\w*|gl_LocalInvocationIndex)\\b").matcher(source).find())
				throw new IllegalStateException("Kernel has workgroup-dependent side effects: " + dimension);
			// The original program has exactly one dead local-ID declaration. Never erase it
			// for the experiment; optimized SPIR-V must independently prove it disappears.
			if (Pattern.compile("\\bgl_LocalInvocationID\\b").matcher(source).results().count() != 1
				|| Pattern.compile("\\blocalPos\\b").matcher(source).results().count() != 1)
				throw new IllegalStateException("Unexpected live local-ID use");
			var baseline = IrisVulkanComputeCompiler.prepareSource("shadowcomp", expanded, formats);
			var uniforms = uniformLayout(baseline.uniforms());
			List<Map<String,Object>> variants = new ArrayList<>();
			for (int[] shape : SHAPES) variants.add(variant(root, dimension, expanded, formats, shape, false, baseline, uniforms));
			variants.add(variant(root, dimension, expanded, formats, SHAPES[0], true, baseline, uniforms));
			Map<String,Object> kernel = new LinkedHashMap<>();
			kernel.put("dimension",dimension); kernel.put("expandedSource",root.relativize(expandedPath).toString().replace('\\','/'));
			kernel.put("expandedSha256",sha(Files.readAllBytes(expandedPath))); kernel.put("globalExtent",EXTENT);
			kernel.put("invocations",8388608); kernel.put("descriptors",baseline.descriptors());
			kernel.put("uniforms",uniforms.members()); kernel.put("uniformBytes",uniforms.size());
			kernel.put("variants",variants); kernels.add(kernel);
		}
		Map<String,Object> result = new LinkedHashMap<>();
		result.put("schemaVersion",1); result.put("kernels",kernels); result.put("images",images);
		result.put("properties",new TreeMap<>(properties));
		result.put("compiler","Production IrisVulkanComputeCompiler from the recorded Alpha11 JAR, shaderc performance, Vulkan1.2/SPIR-V1.5");
		result.put("scope","CPU preprocessing/compilation/reflection only; no GPU equality or timing claim");
		Files.writeString(root.resolve("kernels.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
		System.out.println("PASS: exported 3 current kernels, 15 exact-coverage local-size variants and 3 deliberate mismatch controls");
	}

	private static Map<String,Object> variant(Path root, String dimension, String source, Map<String,String> formats,
			int[] shape, boolean negative, IrisVulkanComputeCompiler.Prepared baseline, UniformLayout uniforms) throws Exception {
		int[] groups = new int[3];
		for (int i = 0; i < 3; i++) {
			if (EXTENT[i] % shape[i] != 0) throw new AssertionError("Retile cannot pad dispatch coverage");
			groups[i] = EXTENT[i] / shape[i];
		}
		String layout = "layout(local_size_x=" + shape[0] + ",local_size_y=" + shape[1] + ",local_size_z=" + shape[2] + ") in;";
		String dispatch = "const ivec3 workGroups=ivec3(" + groups[0] + "," + groups[1] + "," + groups[2] + ");";
		String variant = LOCAL.matcher(source).replaceFirst(layout);
		variant = GROUPS.matcher(variant).replaceFirst(dispatch);
		// Keep the literal baseline source identical, including whitespace.
		if (Arrays.equals(shape, SHAPES[0])) variant = source;
		if (negative) variant = variant.replaceFirst("void\\s+main\\s*\\(\\s*\\)\\s*\\{", "void main() { if (gl_GlobalInvocationID.x == 0u) return;");
		var prepared = IrisVulkanComputeCompiler.prepareSource("shadowcomp",variant,formats);
		if (!prepared.descriptors().equals(baseline.descriptors()) || !prepared.uniforms().equals(baseline.uniforms()))
			throw new AssertionError("Retile changed descriptor or uniform ABI");
		if (!negative && !canonical(prepared.source()).equals(canonical(baseline.source())))
			throw new AssertionError("Retile changed the executable body");
		String name = negative ? "negative-skip-x0" : "local-" + shape[0] + "x" + shape[1] + "x" + shape[2];
		Path dir = root.resolve("variants/" + dimension + "/" + name);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("expanded.csh"),variant);
		Files.writeString(dir.resolve("prepared.glsl"),prepared.source());
		Map<String,Object> record = new LinkedHashMap<>();
		try (var binary = IrisVulkanComputeCompiler.compileSpirv(prepared)) {
			if (!binary.localSize().equals(new Vector3i(shape[0],shape[1],shape[2]))) throw new AssertionError("SPIR-V local size mismatch");
			byte[] bytes = new byte[binary.bytes().remaining()]; binary.bytes().duplicate().get(bytes);
			Files.write(dir.resolve("module.spv"),bytes);
			record.put("name",name); record.put("shaderSpv",root.relativize(dir.resolve("module.spv")).toString().replace('\\','/'));
			record.put("preparedSource",root.relativize(dir.resolve("prepared.glsl")).toString().replace('\\','/'));
			record.put("sha256",sha(bytes)); record.put("preparedSha256",sha(prepared.source().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			record.put("localSize",shape); record.put("groups",groups); record.put("expectedMismatch",negative);
			record.put("spirvAudit",auditSpirv(binary.bytes(), uniforms));
		}
		return record;
	}

	private static String canonical(String source) {
		return source.replaceAll("layout\\s*\\(\\s*local_size_x\\s*=\\s*\\d+\\s*,\\s*local_size_y\\s*=\\s*\\d+\\s*,\\s*local_size_z\\s*=\\s*\\d+\\s*\\)\\s*in\\s*;", "LOCAL_SIZE")
			.replaceAll("const\\s+ivec3\\s+workGroups\\s*=\\s*ivec3\\([^)]*\\)\\s*;", "WORK_GROUPS");
	}

	private record UniformLayout(List<Map<String,Object>> members, int size) { }
	private static UniformLayout uniformLayout(List<IrisVulkanUniformSnapshot.Field> fields) throws Exception {
		var method = IrisVulkanUniformSnapshot.class.getDeclaredMethod("preparedLayout", Collection.class); method.setAccessible(true);
		Object layout = method.invoke(null, fields);
		var membersMethod = layout.getClass().getDeclaredMethod("members"); membersMethod.setAccessible(true);
		var sizeMethod = layout.getClass().getDeclaredMethod("size"); sizeMethod.setAccessible(true);
		List<Map<String,Object>> members = new ArrayList<>();
		for (Object member : (List<?>) membersMethod.invoke(layout)) {
			var fieldMethod = member.getClass().getDeclaredMethod("field"); fieldMethod.setAccessible(true);
			var offsetMethod = member.getClass().getDeclaredMethod("offset"); offsetMethod.setAccessible(true);
			var memberSize = member.getClass().getDeclaredMethod("size"); memberSize.setAccessible(true);
			var field = (IrisVulkanUniformSnapshot.Field) fieldMethod.invoke(member);
			members.add(Map.of("name",field.name(),"type",field.type(),"offset",offsetMethod.invoke(member),"size",memberSize.invoke(member)));
		}
		return new UniformLayout(members,(int)sizeMethod.invoke(layout));
	}

	private static Map<String,Object> auditSpirv(ByteBuffer binary, UniformLayout uniforms) throws Exception {
		var words = binary.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
		List<Map<String,Integer>> builtins = new ArrayList<>();
		Map<Integer,String> forbiddenOpcodes = new HashMap<>();
		for (var field : org.lwjgl.util.spvc.Spv.class.getFields()) {
			if (field.getName().startsWith("SpvOpAtomic") || field.getName().equals("SpvOpControlBarrier") || field.getName().equals("SpvOpMemoryBarrier"))
				forbiddenOpcodes.put(field.getInt(null),field.getName());
		}
		for (int cursor=5; cursor<words.limit();) {
			int word=words.get(cursor), count=word>>>16, op=word&0xffff;
			if (count==0 || cursor+count>words.limit()) throw new AssertionError("Malformed SPIR-V");
			if (forbiddenOpcodes.containsKey(op)) throw new AssertionError("Workgroup/atomic opcode remains: "+forbiddenOpcodes.get(op));
			if (op==SpvOpVariable && words.get(cursor+3)==SpvStorageClassWorkgroup) throw new AssertionError("Workgroup shared memory remains");
			if (op==SpvOpDecorate && words.get(cursor+2)==SpvDecorationBuiltIn) {
				int builtin=words.get(cursor+3); builtins.add(Map.of("id",words.get(cursor+1),"builtin",builtin));
				if (builtin!=SpvBuiltInGlobalInvocationId && builtin!=SpvBuiltInWorkgroupSize) throw new AssertionError("Unexpected compiled builtin "+builtin);
			}
			cursor+=count;
		}
		List<Map<String,Object>> activeDescriptors = new ArrayList<>();
		try (MemoryStack stack=MemoryStack.stackPush()) {
			var ptr=stack.mallocPointer(1); check(spvc_context_create(ptr)); long context=ptr.get(0);
			try {
				check(spvc_context_parse_spirv(context,words,words.remaining(),ptr));long ir=ptr.get(0);
				check(spvc_context_create_compiler(context,SPVC_BACKEND_NONE,ir,SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,ptr));long compiler=ptr.get(0);
				check(spvc_compiler_create_shader_resources(compiler,ptr));long resources=ptr.get(0);var count=stack.mallocPointer(1);
				for (int kind : new int[]{SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,SPVC_RESOURCE_TYPE_STORAGE_IMAGE,SPVC_RESOURCE_TYPE_STORAGE_BUFFER}) {
					check(spvc_resources_get_resource_list_for_type(resources,kind,ptr,count)); int length=Math.toIntExact(count.get(0));
					if(length==0)continue;
					var entries=SpvcReflectedResource.create(ptr.get(0),length);
					for(int i=0;i<length;i++) {
						var entry=entries.get(i);
						activeDescriptors.add(Map.of("resourceType",kind,"binding",spvc_compiler_get_decoration(compiler,entry.id(),SpvDecorationBinding),
							"set",spvc_compiler_get_decoration(compiler,entry.id(),SpvDecorationDescriptorSet),"name",entry.nameString()));
						if(kind==SPVC_RESOURCE_TYPE_UNIFORM_BUFFER) {
							long type=spvc_compiler_get_type_handle(compiler,entry.base_type_id());
							if(spvc_type_get_num_member_types(type)!=uniforms.members().size())throw new AssertionError("UBO member count changed");
							var offset=stack.mallocInt(1);
							for(int j=0;j<uniforms.members().size();j++) {
								check(spvc_compiler_type_struct_member_offset(compiler,type,j,offset));
								if(offset.get(0)!=(int)uniforms.members().get(j).get("offset"))throw new AssertionError("UBO offset mismatch");
							}
							check(spvc_compiler_get_declared_struct_size(compiler,type,ptr));
							if(ptr.get(0)>uniforms.size())throw new AssertionError("UBO size exceeds native allocation");
						}
					}
				}
			} finally { spvc_context_destroy(context); }
		}
		return Map.of("builtins",builtins,"activeDescriptors",activeDescriptors,"workgroupMemory",false,"barriers",false,"atomics",false,"nativeUboOffsetsVerified",true);
	}

	private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
	private static void check(int result) { if(result!=SPVC_SUCCESS)throw new IllegalStateException("SPIRV-Cross result "+result); }
}
