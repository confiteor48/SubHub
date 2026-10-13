import unittest

from scripts.release_version import build_version


class CiReleaseVersionTest(unittest.TestCase):
    def test_local_and_ordinary_private_candidates_keep_source_identity(self):
        self.assertEqual((20, "0.6.4"), build_version(20, "0.6.4"))

    def test_private_replacement_candidate_can_update_installed_dev_in_place(self):
        installed, _ = build_version(21, "0.6.5", 126, True)
        candidate, name = build_version(21, "0.6.5", 128, False)
        self.assertGreater(candidate, installed)
        self.assertEqual((2_100_128, "0.6.5"), (candidate, name))

    def test_development_identity_is_unique_and_in_source_bucket(self):
        self.assertEqual((2_000_123, "0.6.4-dev.123"), build_version(20, "0.6.4", 123, True))
        self.assertNotEqual(build_version(20, "0.6.4", 123, True),
                            build_version(20, "0.6.4", 124, True))

    def test_newer_stable_can_replace_dev_without_downgrade(self):
        dev_code, _ = build_version(20, "0.6.4", 123, True)
        stable_code, name = build_version(20, "0.6.4", 124)
        self.assertGreater(stable_code, dev_code)
        self.assertEqual("0.6.4", name)

    def test_old_branch_cannot_replace_next_source_version(self):
        old_code, _ = build_version(20, "0.6.4", 99999, True)
        next_code, _ = build_version(21, "0.6.5", 1, True)
        self.assertGreater(next_code, old_code)

    def test_retries_reuse_identity_without_consuming_a_new_tag(self):
        self.assertEqual(build_version(20, "0.6.4", 123, True),
                         build_version(20, "0.6.4", 123, True))

    def test_invalid_sequences_and_android_overflow_fail_closed(self):
        for run in (0, -1, 100000):
            with self.assertRaises(ValueError):
                build_version(20, "0.6.4", run, True)
        with self.assertRaises(ValueError):
            build_version(21000, "0.6.4", 1, True)
        with self.assertRaises(ValueError):
            build_version(20, "0.6.4", dev=True)
        with self.assertRaises(ValueError):
            build_version(20, "0.6.4-beta.1", 1, True)


if __name__ == "__main__":
    unittest.main()
