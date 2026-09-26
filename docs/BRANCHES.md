# Branch workflow

Development flows from `alpha` to `beta` to `release`.

- `alpha`: implement features and fixes, then run the relevant build, regression and rendering checks.
- `beta`: promote a selected alpha commit for wider compatibility testing. Keep this branch focused on release-candidate fixes.
- `release`: promote a validated beta commit for a stable release. Record the supported versions, known limitations and verification evidence with the artifact.

Fixes made on a later channel should also be brought back to the development channels so they are retained in future versions. Avoid rewriting published branch history.

Creating these branches does not itself change the maturity or version of the source they contain. Until a candidate has been explicitly versioned and validated for a channel, its existing alpha/beta designation remains authoritative. A source push does not create a GitHub Release or publish binaries.

Tag published artifacts with their exact versions and keep their matching source, dependency notices and checksums available. Record version-specific requirements and changes in release notes rather than hardcoding them in the project README.
