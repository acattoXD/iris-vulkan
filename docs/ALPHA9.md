# Alpha9: reduce repeated native renderer work

Version `1.11.5-vulkan-alpha.9+mc26.3`. Tested JAR SHA-256: `369e83b533c93818a29f0280862d1fd848fe5b1115cbb3f8a169bd5344505315`.

## Changes

- Cache each immutable program uniform request's dependency order. Values are still refreshed each frame; a later program can request additional dependencies in the same frame. Provider replacement and mutable callers retain separate state, and graph optimization invalidates plans. Cache size is bounded.
- Reuse the owned uniform upload buffer cursor and serialize existing matrix values directly, avoiding temporary direct-buffer wrappers and matrix copies per member.
- Cache immutable sampler/buffer lists, preserving order, duplicates and texel-buffer types. Compiled pipeline descriptors use weak identity lookup instead of hashing whole resource structures on each draw.
- Plan possible world read/write hazards from all preprocessed shader stages before lazy compilation. Copy only those color targets at the existing boundaries. Unknown contracts keep the conservative all-target behavior; custom textures retain precedence. Depth snapshots, mipmaps, shader code and quality presets are unchanged.

## Correctness checks

The full Fabric build passes. Custom-uniform tests cover shared dependencies, late same-frame requests, mutable request lists, provider replacement and bounded cache lifetime. 768 captures match reference packing across 48 frames, provider swaps, entity/atlas changes and matrix history; layout tests cover 10,000 draws and cache eviction. Metadata tests cover weak collection, distinct identities, mutable inputs and unchanged descriptor order/types. 128 feedback-plan checks cover aliases, custom resources, all five shader stages, lazy first use, glass read-only inputs and unknown fallback.

CPU-only microbenchmarks use the actual Alpha8 source and candidate classes. For 30,000 repeated dependency requests, Alpha8 allocates about 59.5 MB versus 1,032 bytes after candidate warmup. An isolated 100,000-pair metadata lookup loop allocates 97.6 MB before the change and zero afterward. These are narrow allocation checks, not game FPS measurements.

## Runtime measurement scope

The comparison uses the immutable Alpha8 JAR (`44d688e5dc6961122a1cc5d65c89dd95e8e8a10a97f0fd66dbfaa31c8d2141f2`) and the candidate above, Minecraft26.3, Java25.0.3, RTX5070/NVIDIA616.92, the same 20 other mods, Complementary Unbound r5.9.1 HIGH and 2560x1440. Shader ZIP/options, mod files, world snapshot, render settings and probe hashes are matched. The visible test has a fixed camera, glass/panes, named entities, textured blocks and an enchanted held item. Screenshots and a camera turn occur after measurements.

GPU timing uses nonblocking timestamp-query pairs around renderLevel. CPU submission, frame intervals and full-thread CPU means are separate metrics. Windows thread CPU samples are quantized to 15.625 ms; per-frame CPU percentiles do not support fine-grained claims. Shader compilation and warmup are excluded from the measured windows. GPU coverage must exceed 90%, and the live frame limiter must stay unthrottled.

Four completed runs (two baseline, two candidate) have mismatched focus conditions. The strict comparator rejects them, including a rule declared before the second candidate that requires at least two completely unfocused measurement windows. No guard was relaxed to manufacture a speedup. Original reports remain under the parent workspace's `build/port-26.3/benchmark-alpha8-high-1440p-01`, `-02`, `benchmark-alpha9-combined-high-1440p-01` and `-02`. They establish successful rendering and preserve raw timings; they do not establish that the changes caused an FPS gain.

The candidate's actual log reports snapshot targets `[0, 3, 4, 6, 12]`, all allocated, versus ten allocated targets in Alpha8's unconditional path. This halves the snapshot target set. At this profile's mixed full/half resolutions, the omitted targets total approximately 80.9 MiB per boundary, or 161.7 MiB of logical copied pixel payload across two boundaries per frame. This is a calculation from actual formats/dimensions and the selected copy plan, not a measurement of physical memory-bus traffic or FPS.

## Completed runtime checks

The exact JAR above completed the Complementary High benchmark fixture with textured glass/panes, readable labels, falling-block faces, enchanted held item and a 60-degree camera turn. Baseline/candidate after and turn captures were reviewed; they show no obvious regression in those views. This does not prove temporal stability during continuous movement.

`custom-vulkan-18-alpha9-ultra` completed the four feature captures and Iris reload with the real Complementary r5.9.1 ULTRA preset. Its six writable images and one SSBO are retained and compute dispatch resumes after reload. `custom-vulkan-19-alpha9-mineek` completed the same feature/reload sequence. Reviewed after-reload images retain the expected named entities, textured blocks and held glint. Both runs closed normally and saved their disposable worlds. The diagnostic mods remain separate and must not ship.

Alpha8's other lifecycle/OpenGL checks remain historical evidence for that binary; they were not all repeated for Alpha9. Alpha9's inherited OpenGL uniform update method is unchanged, but that is not a new OpenGL runtime test.

## Remaining limitations

Alpha8's reported glass flicker during camera rotation is not yet reproduced or fixed. A server resource pack warning about replacing terrain shaders was present, but does not establish the cause. Still-image checks cannot rule out temporal artifacts. General FPS improvements, other drivers/devices, all packs, and complete OpenGL parity are not established. The experimental gold/red warning remains enabled.
