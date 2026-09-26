import unittest

from prepare_probe_profiles import profile_names


class ProbeProfileNamesTest(unittest.TestCase):
    def test_default_preserves_alpha2_names(self):
        self.assertEqual(profile_names(), ("alpha2-r591-enchanted-final", "alpha2-makeup-medium-final"))

    def test_alpha3_names_are_distinct(self):
        self.assertEqual(profile_names("alpha3"), ("alpha3-r591-enchanted-final", "alpha3-makeup-medium-final"))

    def test_prefix_cannot_escape_or_split_path(self):
        for prefix in ("", "..", "../alpha3", "alpha3/child", "alpha3\\child", "alpha 3", "C:alpha3"):
            with self.subTest(prefix=prefix), self.assertRaises(ValueError):
                profile_names(prefix)


if __name__ == "__main__":
    unittest.main()
