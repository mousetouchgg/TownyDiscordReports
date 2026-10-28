import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest

import public_repo as policy


class PublicationTest(unittest.TestCase):
    def test_source_and_safe_templates_are_allowed(self):
        for name in ['README.md', 'src/main/java/jp/tdr/Ranking.java',
                     'src/main/resources/config.yml', 'gradle/wrapper/gradle-wrapper.jar']:
            self.assertTrue(policy.allowed(name))
        self.assertEqual([], policy.inspect('src/main/resources/config.yml', b"webhook-url: ''\n"))

    def test_runtime_and_secret_files_are_rejected_even_if_tracked(self):
        for name in ['dev/plugins/Towny/config.yml', 'plugins.zip', 'server.properties',
                     '.env', '.aws/credentials', 'build/libs/plugin.jar', 'reports.db-wal',
                     'src/main/resources/credentials.yml', '../README.md']:
            self.assertFalse(policy.allowed(name), name)

    def test_real_shaped_webhook_is_detected_without_printing_value(self):
        secret = 'https://' + 'discord.com/api/webhooks/' + '1' * 18 + '/' + 'a' * 60
        error = io.StringIO()
        with contextlib.redirect_stderr(error):
            self.assertFalse(policy.report([('README.md', secret.encode(), False)]))
        self.assertIn('Discord Webhook', error.getvalue())
        self.assertNotIn(secret, error.getvalue())

    def test_fake_short_urls_in_java_test_fixtures_are_allowed(self):
        self.assertEqual([], policy.inspect('src/test/java/jp/tdr/WebhookTest.java',
                                          b'https://discord.com/api/webhooks/123/token_abc'))

    def test_nonempty_template_rejected_even_for_dummy_url(self):
        self.assertIn('non-empty webhook template', policy.inspect(
            'src/main/resources/config.yml', b"webhook-url: 'dummy'\n"))

    def test_private_key_and_local_user_path_are_detected(self):
        key = '-----BEGIN ' + 'PRIVATE KEY-----'
        local = '/' + 'home/' + 'private-user/project/'
        self.assertIn('private key', policy.inspect('README.md', key.encode()))
        self.assertIn('local user path', policy.inspect('README.md', local.encode()))

    def test_wrapper_tampering_and_symlinks_are_rejected(self):
        self.assertIn('unexpected Gradle Wrapper checksum', policy.inspect(
            'gradle/wrapper/gradle-wrapper.jar', b'changed'))
        self.assertIn('symbolic link', policy.inspect('README.md', b'target', True))

    def test_scans_index_instead_of_cleaned_working_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init', '--quiet', str(root)], check=True)
            secret = 'ghp_' + 'a' * 36
            (root / 'README.md').write_text(secret)
            policy.git(root, 'add', 'README.md')
            (root / 'README.md').write_text('safe working copy')
            entries = policy.staged_entries(root)
            self.assertIn('GitHub token', policy.inspect(entries[0][0], entries[0][1]))

    def test_source_export_does_not_traverse_local_data(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'README.md').write_text('safe source')
            (root / 'dev').mkdir()
            (root / 'dev' / 'README.md').write_text('private')
            (root / 'plugins.zip').write_bytes(b'private archive')
            self.assertEqual(['README.md'], [name for name, _, _ in policy.source_entries(root)])

    def test_existing_export_destination_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            marker = output / 'keep.txt'
            marker.write_text('existing data')
            with self.assertRaises(ValueError):
                policy.export(output, None)
            self.assertEqual('existing data', marker.read_text())


if __name__ == '__main__':
    unittest.main()
