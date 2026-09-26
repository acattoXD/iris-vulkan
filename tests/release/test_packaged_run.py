import unittest
from pathlib import Path

from packaged_run import classify_iris_loads


class GeneratedClassProvenance(unittest.TestCase):
    jar = Path("build/release-validation/test/mods/iris-test.jar").resolve()
    host = "net.irisshaders.iris.uniforms.custom.CustomUniforms"

    def line(self, name, source):
        return f"[1.000s][info][class,load] {name} source: {source}\n"

    def test_verified_nested_switch(self):
        owner = self.host + "$Snapshot"
        log = self.line(self.host, self.jar.as_uri()) + self.line(owner, self.jar.as_uri())
        log += self.line(owner + "$$TypeSwitch/0x0123abcdef", self.host)
        named, generated, unexpected = classify_iris_loads(log, self.jar)
        self.assertEqual(len(named), 2)
        self.assertEqual([kind for kind, _ in generated], ["TypeSwitch"])
        self.assertFalse(unexpected)

    def test_helper_without_verified_host_is_rejected(self):
        log = self.line(self.host + "$$TypeSwitch/0x0123", self.host)
        self.assertTrue(classify_iris_loads(log, self.jar)[2])

    def test_same_filename_in_another_directory_is_rejected(self):
        wrong = self.jar.parent.parent / "other/mods" / self.jar.name
        log = self.line(self.host, wrong.as_uri())
        log += self.line(self.host + "$$Lambda/0x0123", self.host)
        self.assertEqual(len(classify_iris_loads(log, self.jar)[2]), 2)

    def test_unknown_generated_kind_is_rejected(self):
        log = self.line(self.host, self.jar.as_uri())
        log += self.line(self.host + "$$UnknownHelper/0x0123", self.host)
        self.assertTrue(classify_iris_loads(log, self.jar)[2])


if __name__ == "__main__":
    unittest.main()
