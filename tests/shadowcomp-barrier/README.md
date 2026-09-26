# Shadow-to-compute barrier selection

After compiling current production classes, run `python tests/shadowcomp-barrier/run.py`. It opens no game/window and records the actual compiled `IrisVulkanFinalPassRenderer.afterShadows` callback with only compute/storage/prepare side effects substituted.

The callback measures completed `compute.dispatchCount()` before and after visiting every shadow-composite index. If a dispatch was actually recorded, its existing pre/post storage barriers already provide the dependency. If none was recorded, the existing stage barrier is retained immediately before prepare. No stage barrier is removed based merely on declared source presence. Exceptions stop before prepare. Width/height are read once for this synchronous stage loop.

The tests cover no executor, empty and invalid sources, first/late sparse slots, multiple dispatches, a recorded dispatch with zero work, and failures both before and after an earlier dispatch. Bytecode checks also verify that actual direct/indirect compute dispatch sites remain bracketed by both storage barriers and that the count advances only after dispatch returns.

Storage clears, image layouts, allocation lifetimes, and the compute barriers themselves are unchanged. This is an ordering proof and CPU call-count check, not a measured GPU or whole-game FPS improvement.
