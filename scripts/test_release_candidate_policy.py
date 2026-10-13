import itertools
import os
from pathlib import Path
import subprocess
import sys
import textwrap
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

    def test_workflow_private_provenance_records_actual_ci_identity(self):
        source = (Path(__file__).resolve().parents[1] / '.github/workflows/release.yml').read_text(encoding='utf-8')
        private = source.split('      - name: Record private signed-build provenance\n', 1)[1].split('      - name: Upload', 1)[0]
        self.assertIn('VERSION_CODE: ${{ steps.version.outputs.version_code }}', private)
        self.assertIn('"versionCode": int(os.environ["VERSION_CODE"])', private)
        self.assertIn('"ciRunNumber": int(os.environ["CI_RUN_NUMBER"])', private)
        self.assertIn('"sharedCiVersion": os.environ["REPLACEMENT_LAB_CANDIDATE"] == "true"', private)

    def test_actual_workflow_shell_keeps_metadata_and_gradle_ci_identity_aligned(self):
        # Exercise the actual shell blocks, replacing only external commands with inert functions.
        if sys.platform == 'win32':
            bash = Path('C:/Program Files/Git/bin/bash.exe')
            self.assertTrue(bash.is_file(), 'Git Bash is required for the workflow shell contract')
        else:
            bash = Path('/bin/bash')
        source = (Path(__file__).resolve().parents[1] / '.github/workflows/release.yml').read_text(encoding='utf-8')
        version = source.split('          if [[ "$DEV_BUILD" == "true" ]]; then\n', 1)[1].split('      - name: Resolve publication identity', 1)[0]
        version = textwrap.dedent('          if [[ "$DEV_BUILD" == "true" ]]; then\n' + version)
        build = source.split('          build_args=(', 1)[1].split('          ./gradlew', 1)[0]
        build = textwrap.dedent('          build_args=(' + build)
        for dev, publish, replacement, expected_ci in (
                (True, True, False, True), (False, True, False, True),
                (False, False, True, True), (False, False, False, False)):
            with self.subTest(dev=dev, publish=publish, replacement=replacement):
                environment = dict(os.environ, DEV_BUILD=str(dev).lower(), PUBLISH_RELEASE=str(publish).lower(),
                    REPLACEMENT_LAB_CANDIDATE=str(replacement).lower(), CI_RUN_NUMBER='128',
                    RELEASE_TAG='v0.6.5', GITHUB_OUTPUT='unused', IMMEDIATE_QUALITY_EXPERIMENT='false')
                code = 'set -euo pipefail\npython3() { printf "%s\\n" "$*"; }\ngit() { return 0; }\n' + version
                actual = subprocess.run([str(bash), '--noprofile', '--norc', '-c', code], env=environment, capture_output=True, text=True)
                self.assertEqual(actual.returncode, 0, actual.stderr)
                self.assertEqual('--ci-run-number 128' in actual.stdout, expected_ci)
                self.assertEqual('--dev-build' in actual.stdout, dev)
                actual = subprocess.run([str(bash), '--noprofile', '--norc', '-c',
                    'set -euo pipefail\n' + build + '\nprintf "%s\\n" "${build_args[@]}"'],
                    env=environment, capture_output=True, text=True)
                self.assertEqual(actual.returncode, 0, actual.stderr)
                self.assertEqual('-PciRunNumber=128' in actual.stdout.splitlines(), expected_ci)


if __name__ == '__main__':
    unittest.main()
