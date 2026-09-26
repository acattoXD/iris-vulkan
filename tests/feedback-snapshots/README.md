# World feedback snapshot optimization

`python tests/feedback-snapshots/run.py --output build/feedback-snapshot-tests-alpha10` compiles the actual production planner and gbuffer-target implementation against the verified26.3 test runtime, then executes165 CPU checks. These cover logical aliases, supported custom-texture precedence, every graphics source stage, programs not yet compiled/drawn, conservative unknown contracts, producer-texture fallback, and boundary snapshots across repeated writes. GPU image comparison and performance measurement are separate gates.

The original code copied every allocated target at both world-input boundaries. The new plan conservatively scans every preprocessed gbuffer program (including sources later reached by fallback or lazy pipeline compilation), intersects possible target reads with that program's writes, and excludes supported custom textures resolved before framebuffer aliases. Target4 stays conservative whenever a world program writes it because a missing producer binding may select the existing scene-texture fallback. Unknown source/macros/output contracts retain all snapshots.

Alpha10's follow-up excludes simple unused sampler declarations from the read scan. Shared headers often declare samplers that a particular shader never references. Function arguments and texture queries still count as uses, and arrays, multiple declarators and unknown forms stay conservative. Only the planner's analysis text is changed; the actual shader source is left intact. Alpha9's recorded128 checks cover its earlier, more conservative plan.

The two boundaries, draw routing, custom/direct texture precedence, depth snapshots, shader settings, and image formats are unchanged. The mask is rebuilt on ProgramSet identity changes. Nothing samples an old skipped snapshot: `feedbackView` returns a view only for targets retained by the plan.

Runtime logs show the selected mask and reason. Read-only counters on `IrisVulkanGbufferTargets` are `feedbackSnapshotCopies()`, `feedbackSnapshotCopiesSkipped()`, and `feedbackSnapshotBoundaries()`; use deltas for a measured interval, especially across reloads.

Glass-specific invariant: an ordinary HIGH translucent program writing0/3/6 and reading gaux2/5 or gaux4/7 must retain current post-deferred inputs. Those read-only targets do not become feedback dependencies. Existing framebuffer binding selection is unchanged.
