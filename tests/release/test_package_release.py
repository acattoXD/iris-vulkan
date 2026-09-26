"""Packaging trust-boundary and reproducibility checks; no network or game."""
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import package_release as package


def runtime_fixture(extra=None, missing_license=False):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("fabric.mod.json", json.dumps({"id": "iris", "version": "1.0-alpha.1", "environment": "client"}))
        for name in ("LICENSE", "LICENSE-DEPENDENCIES", "licenses/AGPL-3.0.txt", "licenses/GPL-3.0.txt", "licenses/THIRD-PARTY-NOTICES.md"):
            if not (missing_license and name.endswith("AGPL-3.0.txt")):
                archive.writestr(name, "fixture license material")
        if extra:
            archive.writestr(extra, "fixture")
    return stream.getvalue()


class ReleasePackageTest(unittest.TestCase):
    def test_alpha6_install_notes_route_to_current_presentation_scope(self):
        metadata = {"name": "Iris Vulkan (Experimental)", "version": "1.11.3-vulkan-alpha.6+mc26.2", "depends": {}}
        notes = package.install_notes(metadata, "runtime.jar", "sources.zip").decode()
        self.assertIn("docs/ALPHA6.md", notes)
        self.assertIn("no final shader", notes)
        self.assertIn("current colortex0", notes)
        self.assertIn("matching format and dimensions", notes)
        self.assertNotIn("Alpha5 targets", notes)

    def test_alpha5_install_notes_keep_portal_scope_and_existing_color(self):
        metadata = {"name": "Iris Vulkan (Experimental)", "version": "1.11.3-vulkan-alpha.5+mc26.2", "depends": {}}
        notes = package.install_notes(metadata, "runtime.jar", "sources.zip").decode()
        self.assertIn("docs/ALPHA5.md", notes)
        self.assertIn("End Portal becomes active", notes)
        self.assertIn("supplied vertex colors remain unchanged", notes)
        self.assertNotIn("26.3", notes)
        self.assertNotIn("final alpha2 JAR completed", notes)

    def test_alpha4_install_notes_name_the_removed_invocation_not_descriptor(self):
        metadata = {"name": "Iris Vulkan (Experimental)", "version": "1.11.3-vulkan-alpha.4+mc26.2", "depends": {}}
        notes = package.install_notes(metadata, "runtime.jar", "sources.zip").decode()
        self.assertIn("docs/ALPHA4.md", notes)
        self.assertIn("uploadResults overload still exists", notes)
        self.assertIn("clearAllCachedBatches invocation was removed", notes)
        self.assertIn("makes no backend-selection change", notes)
        self.assertNotIn("final alpha2 JAR completed", notes)

    def test_alpha3_install_notes_do_not_inherit_alpha2_validation(self):
        metadata = {"name": "Iris Vulkan (Experimental)", "version": "1.11.3-vulkan-alpha.3+mc26.2",
                    "depends": {"minecraft": "26.2", "java": ">=25", "fabricloader": ">=0.19.2",
                                "sodium": ["0.9.1-beta.3+mc26.2", "0.9.2+mc26.2"]}}
        notes = package.install_notes(metadata, "runtime.jar", "sources.zip").decode()
        self.assertIn("docs/ALPHA3.md", notes)
        self.assertIn("exact compiled pipelines", notes)
        self.assertIn("do not automatically validate", notes)
        self.assertNotIn("final alpha2 JAR completed", notes)
        self.assertIn("new hardware retest", notes)

    def test_probe_and_user_data_cannot_be_shipped(self):
        for path in ("net/irisshaders/iris/probe/NativeProbe.class", "shaderpacks/user.zip", "logs/latest.log", "saves/world/level.dat", ".env", "../escape"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                package.inspect_runtime(runtime_fixture(path))

    def test_license_material_is_required(self):
        with self.assertRaises(ValueError):
            package.inspect_runtime(runtime_fixture(missing_license=True))
        metadata, nested = package.inspect_runtime(runtime_fixture())
        self.assertEqual(metadata["version"], "1.0-alpha.1")
        self.assertEqual(nested, [])

    def test_source_paths_reject_runtime_artifacts(self):
        for path in ("fabric/run-user/config/iris.properties", "common/build/classes/A.class", "tests/shaderpacks/Sildur.zip", "docs/private.key", "tests/logs/latest.log", "../secret"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                package.assert_public_path(path)
        package.assert_public_path("tests/shaderpacks/native-final-check/shaders/final.fsh")
        package.assert_public_path("common/src/disabledTest/resources/shaderpacks/options/shaders/gbuffers_basic.vsh")

    def test_credentials_are_rejected_without_echoing_them(self):
        token = b"ghp_" + b"A" * 36
        with self.assertRaisesRegex(ValueError, "Credential-like content") as error:
            package.assert_no_credentials("example.json", token)
        self.assertNotIn(token.decode(), str(error.exception))

    def test_workstation_paths_are_rejected_without_echoing_them(self):
        for path in (b"C:" + b"\\Users\\someone\\shaderpacks\\pack.zip", b"/" + b"Users/someone/.minecraft", b"." + b"codex/attachments"):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "Workstation-specific path"):
                package.assert_no_private_paths("README.md", path)

    def test_unknown_nested_libraries_require_source_mapping(self):
        self.assertEqual(package.coordinate_for("META-INF/jars/glsl-transformer-3.0.0-pre3.jar"), "io.github.douira:glsl-transformer:3.0.0-pre3")
        self.assertEqual(package.coordinate_for("META-INF/jars/fabric-api-base-2.0.4+ece063239c.jar"), "net.fabricmc.fabric-api:fabric-api-base:2.0.4+ece063239c")
        with self.assertRaises(ValueError):
            package.coordinate_for("META-INF/jars/unknown-1.0.jar")

    def test_source_zip_is_deterministic_and_keeps_wrapper_executable(self):
        with tempfile.TemporaryDirectory() as temp:
            first, second = Path(temp) / "one.zip", Path(temp) / "two.zip"
            package.write_zip(first, {"z.txt": b"z", "gradlew": b"#!/bin/sh\n", "a.txt": b"a"}, "source")
            package.write_zip(second, {"a.txt": b"a", "gradlew": b"#!/bin/sh\n", "z.txt": b"z"}, "source")
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(archive.getinfo("source/gradlew").external_attr >> 16, 0o100755)


if __name__ == "__main__":
    unittest.main()
