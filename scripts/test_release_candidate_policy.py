import itertools
from pathlib import Path
import subprocess
import sys
import unittest
from scripts import release_candidate_policy as policy


class ReleaseCandidatePolicyTest(unittest.TestCase):
    def test_ordinary_public_and_private_builds_remain_allowed(self):
        for event in ('push', 'workflow_dispatch'):
            for publish in (False, True):
                policy.validate(event, publish, False, False)

    def test_manual_private_flags_are_allowed(self):
        for immediate, replacement in itertools.product((False, True), repeat=2):
            policy.validate('workflow_dispatch', False, immediate, replacement)

    def test_every_public_experimental_combination_is_rejected(self):
        for event in ('push', 'workflow_dispatch'):
            for flags in ((True, False), (False, True), (True, True)):
                with self.assertRaises(ValueError):
                    policy.validate(event, True, *flags)

    def test_automatic_private_experimental_build_is_also_rejected(self):
        for flags in ((True, False), (False, True), (True, True)):
            with self.assertRaises(ValueError):
                policy.validate('push', False, *flags)

    def test_strings_and_integers_do_not_become_booleans(self):
        for value in ('false', 'true', 0, 1, None):
            with self.assertRaises(ValueError):
                policy.validate('workflow_dispatch', value, False, False)
            with self.assertRaises(ValueError):
                policy.validate('workflow_dispatch', False, value, False)
            with self.assertRaises(ValueError):
                policy.validate('workflow_dispatch', False, False, value)

    def test_cli_spelling_is_strict(self):
        self.assertTrue(policy.boolean('true'))
        self.assertFalse(policy.boolean('false'))
        for value in ('True', 'FALSE', '1', '', ' true', 'false '):
            with self.assertRaises(ValueError):
                policy.boolean(value)

    def test_real_cli_exit_propagates_rejection(self):
        script = Path(policy.__file__)
        common = [sys.executable, str(script), '--event-name', 'workflow_dispatch',
                  '--immediate-quality-experiment', 'false', '--replacement-lab-candidate', 'true']
        accepted = subprocess.run(common + ['--publish-release', 'false'], capture_output=True)
        refused = subprocess.run(common + ['--publish-release', 'true'], capture_output=True)
        self.assertEqual(accepted.returncode, 0)
        self.assertNotEqual(refused.returncode, 0)
        self.assertNotIn(b'policy verified', refused.stdout)

    def test_workflow_wires_both_flag_and_verified_packaged_value(self):
        source = (Path(__file__).resolve().parents[1] / '.github/workflows/release.yml').read_text(encoding='utf-8')
        self.assertIn('replacement_lab_candidate:', source)
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.replacement_lab_candidate || false", source)
        self.assertIn('--replacement-lab-candidate "$REPLACEMENT_LAB_CANDIDATE"', source)
        self.assertIn('-PreplacementLabCandidate=${REPLACEMENT_LAB_CANDIDATE}', source)
        self.assertIn('REPLACEMENT_LAB_CANDIDATE = ${REPLACEMENT_LAB_CANDIDATE};', source)
        self.assertIn('"replacementLabCandidate": os.environ["REPLACEMENT_LAB_CANDIDATE"] == "true"', source)


if __name__ == '__main__':
    unittest.main()
