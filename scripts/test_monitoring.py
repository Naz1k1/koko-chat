"""验证凭据处理和邮件启用边界；不连接 Docker、SMTP 或业务数据库。"""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import monitoring


class ConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.base = self.root / 'deploy/monitoring'
        self.base.mkdir(parents=True)
        (self.root / 'deploy/.env').write_text('KOKO_OPS_TOKEN=' + 't' * 48)
        (self.base / 'alertmanager.yml').write_text((monitoring.BASE / 'alertmanager.yml').read_text())
        self.root_patch = patch.object(monitoring, 'ROOT', self.root)
        self.base_patch = patch.object(monitoring, 'BASE', self.base)
        self.root_patch.start()
        self.base_patch.start()

    def tearDown(self):
        self.base_patch.stop()
        self.root_patch.stop()
        self.temp.cleanup()

    def prepare(self, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()) as captured:
            monitoring.prepare(**kwargs)
        return captured.getvalue()

    def configure(self, password='test-$literal`text`#secret'):
        (self.base / '.env').write_text('SMTP_SMARTHOST=smtp.example.invalid:587\n'
            'SMTP_FROM=sender@example.invalid\nSMTP_TO=receiver@example.invalid\n'
            'SMTP_USERNAME=test-user\nSMTP_PASSWORD=' + password + '\n')

    def test_missing_mail_fails_before_creating_runtime(self):
        with self.assertRaisesRegex(ValueError, 'SMTP_SMARTHOST'):
            self.prepare()
        self.assertFalse((self.base / 'runtime').exists())

    def test_monitor_only_is_explicitly_disabled(self):
        self.prepare(monitor_only=True)
        runtime = self.base / 'runtime'
        self.assertFalse(json.loads((runtime / 'state.json').read_text())['email_enabled'])
        self.assertNotIn('email_configs', (runtime / 'alertmanager.yml').read_text())
        self.assertEqual((runtime / 'smtp-password').read_text(), '')

    def test_password_never_enters_config_or_output(self):
        password = 'p$(`literal`)#with:specials'
        self.configure(password)
        output = self.prepare()
        runtime = self.base / 'runtime'
        self.assertEqual((runtime / 'smtp-password').read_text(), password)
        self.assertNotIn(password, (runtime / 'alertmanager.yml').read_text())
        self.assertNotIn(password, output)
        self.assertEqual(runtime.stat().st_mode & 0o777, 0o700)
        self.assertEqual((runtime / 'smtp-password').stat().st_mode & 0o777, 0o444)
        self.assertIn('send_resolved: true', (runtime / 'alertmanager.yml').read_text())
        self.assertIn('require_tls: true', (runtime / 'alertmanager.yml').read_text())
        # 旋转凭据只替换受限文件，日志和受版本控制模板中都不出现值。
        self.configure('replacement-secret')
        self.prepare()
        self.assertEqual((runtime / 'smtp-password').read_text(), 'replacement-secret')

    def test_invalid_token_and_target_are_rejected(self):
        self.configure()
        with (self.base / '.env').open('a') as stream:
            stream.write('KOKO_MONITOR_TARGETS=https://host:8080\n')
        with self.assertRaisesRegex(ValueError, 'KOKO_MONITOR_TARGETS'):
            self.prepare()
        (self.root / 'deploy/.env').write_text('KOKO_OPS_TOKEN=short')
        with self.assertRaisesRegex(ValueError, 'KOKO_OPS_TOKEN'):
            self.prepare()

    def test_template_in_recipient_is_rejected(self):
        self.configure()
        path = self.base / '.env'
        path.write_text(path.read_text().replace('receiver@example.invalid', '{{ .Status }}@example.invalid'))
        with self.assertRaisesRegex(ValueError, 'SMTP_TO'):
            self.prepare()


if __name__ == '__main__':
    unittest.main()
