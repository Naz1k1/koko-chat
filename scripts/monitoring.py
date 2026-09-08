#!/usr/bin/env python3
"""常驻监控入口：安全生成配置、校验、启动和发送明确标记的邮件演练。"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'deploy/monitoring'
PROMETHEUS_IMAGE = 'prom/prometheus:v3.14.0'
ALERTMANAGER_IMAGE = 'prom/alertmanager:v0.34.0'


def env_file(path):
    """只解析 KEY=value，不执行 shell，也不展开 $ 或反引号。"""
    result = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            key, sep, value = line.partition('=')
            if not sep or not re.fullmatch(r'[A-Z][A-Z0-9_]*', key.strip()):
                raise ValueError(f'{path.name} 包含无效配置行')
            result[key.strip()] = value.strip()
    return result


def protected_write(path, text):
    # 父目录 700 防止其他宿主机用户读取；文件只读挂入非 root 容器。
    # 替换使用原子重命名，因此更新后必须重建容器，不能仅发送 reload。
    temp = path.with_suffix(path.suffix + '.tmp')
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(text)
    temp.chmod(0o444)
    temp.replace(path)


def prepare(monitor_only=False):
    config = env_file(BASE / '.env')
    token = env_file(ROOT / 'deploy/.env').get('KOKO_OPS_TOKEN', '')
    # 兼容主项目环境文件使用的单/双引号；监控 .env 按原始值处理。
    if len(token) > 1 and token[0] == token[-1] and token[0] in "\"'":
        token = token[1:-1]
    if len(token) < 32 or any(c.isspace() for c in token):
        raise ValueError('请先在 deploy/.env 配置至少 32 字符的 KOKO_OPS_TOKEN')
    keys = ('SMTP_SMARTHOST', 'SMTP_FROM', 'SMTP_TO', 'SMTP_USERNAME', 'SMTP_PASSWORD')
    missing = [key for key in keys if not config.get(key)]
    if missing and not monitor_only:
        raise ValueError('请填写 deploy/monitoring/.env：' + ', '.join(missing))
    email_enabled = not monitor_only
    if email_enabled:
        host = config['SMTP_SMARTHOST']
        if not re.fullmatch(r'[A-Za-z0-9.-]+:[0-9]{1,5}', host) or not 1 <= int(host.rsplit(':', 1)[1]) <= 65535:
            raise ValueError('SMTP_SMARTHOST 必须是主机名:端口')
        for key in ('SMTP_FROM', 'SMTP_TO'):
            if '@' not in config[key] or '{{' in config[key]:
                raise ValueError(f'{key} 需填写邮箱地址，不能包含模板')
    targets = config.get('KOKO_MONITOR_TARGETS', 'host.docker.internal:8080').split(',')
    targets = [target.strip() for target in targets]
    if not targets or any(not re.fullmatch(r'[A-Za-z0-9.-]+:[0-9]{1,5}', target) or not 1 <= int(target.rsplit(':', 1)[1]) <= 65535 for target in targets):
        raise ValueError('KOKO_MONITOR_TARGETS 必须为逗号分隔的主机名:端口')
    runtime = BASE / 'runtime'
    runtime.mkdir(mode=0o700, exist_ok=True)
    runtime.chmod(0o700)
    if email_enabled:
        rendered = (BASE / 'alertmanager.yml').read_text()
        for key in keys[:-1]:
            rendered = rendered.replace('__' + key + '__', json.dumps(config[key], ensure_ascii=False))
    else:
        # 用户未提供邮箱配置时仍可抓取和显示告警，但明确标记为未启用邮件。
        rendered = json.dumps({'route': {'receiver': 'email-not-configured'},
                               'receivers': [{'name': 'email-not-configured'}]})
    protected_write(runtime / 'alertmanager.yml', rendered)
    protected_write(runtime / 'smtp-password', config.get('SMTP_PASSWORD', '') if email_enabled else '')
    protected_write(runtime / 'koko-ops-token', token)
    protected_write(runtime / 'targets.json', json.dumps([{'targets': targets}]))
    protected_write(runtime / 'state.json', json.dumps({'email_enabled': email_enabled}))
    print('配置已生成；邮件' + ('已启用，实际投递仍需验证。' if email_enabled else '未配置（仅监控模式）。'))


def compose(*args, capture=False):
    return subprocess.run(['docker', 'compose', '-f', str(BASE / 'compose.yaml'), *args],
                          check=True, text=True, capture_output=capture)


def check():
    # 不打印展开后的配置；其中可能包含收件地址。
    compose('config', '--quiet')
    compose('run', '--rm', '--no-deps', '--entrypoint', '/bin/promtool', 'prometheus',
            'check', 'config', '/etc/prometheus/prometheus.yml')
    compose('run', '--rm', '--no-deps', '--entrypoint', '/bin/amtool', 'alertmanager',
            'check-config', '/etc/alertmanager/alertmanager.yml')
    subprocess.run(['docker', 'run', '--rm', '--entrypoint', '/bin/promtool',
                    '-v', f'{BASE}:/work:ro', PROMETHEUS_IMAGE,
                    'test', 'rules', '/work/alerts.test.yml'], check=True)


def http(port, path, data=None):
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}',
                                 data=None if data is None else json.dumps(data).encode(),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=5) as response:
        return response.read().decode()


def status():
    compose('ps')
    state = json.loads((BASE / 'runtime/state.json').read_text())
    print('邮件配置：' + ('启用；不等同于收件箱已送达' if state['email_enabled'] else '未启用'))
    payload = json.loads(http(9090, '/api/v1/targets'))
    for target in payload['data']['activeTargets']:
        print(f"{target['labels'].get('job')}: {target['health']}")
    print('指标 http://127.0.0.1:9090 ｜ 告警 http://127.0.0.1:9093')


def email_counters():
    values = {'total': 0.0, 'failed': 0.0}
    for line in http(9093, '/metrics').splitlines():
        if 'integration="email"' not in line or line.startswith('#'):
            continue
        for key, metric in [('total', 'alertmanager_notifications_total{'),
                            ('failed', 'alertmanager_notifications_failed_total{')]:
            if line.startswith(metric):
                values[key] += float(line.rsplit(' ', 1)[1])
    return values


def smoke():
    """发送合成故障及恢复，不修改业务依赖或制造真实聊天故障。"""
    if not json.loads((BASE / 'runtime/state.json').read_text())['email_enabled']:
        raise ValueError('邮件尚未配置；填写后运行 up，再发送演练邮件')
    start = dt.datetime.now(dt.timezone.utc)
    alert = {'labels': {'alertname': 'KokoEmailSmokeTest', 'severity': 'warning',
                        'instance': 'mail-test-' + uuid.uuid4().hex[:8],
                        'environment': 'local', 'project': 'koko-chat'},
             'annotations': {'summary': '邮件链路演练，无业务故障；随后发送恢复通知'},
             'startsAt': start.isoformat(), 'endsAt': (start + dt.timedelta(minutes=5)).isoformat()}
    before = email_counters()
    http(9093, '/api/v2/alerts', [alert])
    print('已注入演练告警，等待 Alertmanager 邮件发送；不会停止后端。', flush=True)
    try:
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            current = email_counters()
            if current['failed'] > before['failed']:
                raise ValueError('SMTP 投递失败，请检查本地 Alertmanager 日志（不要公开密码）')
            if current['total'] > before['total']:
                break
            time.sleep(2)
        else:
            raise ValueError('等待故障邮件超时，检查 Alertmanager 状态')
    finally:
        # 无论发送成功或失败都显式结束演练；请求失败时最多 5 分钟自然过期。
        alert['endsAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        http(9093, '/api/v2/alerts', [alert])
    print('已结束演练，等待恢复邮件。', flush=True)
    first = current
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        current = email_counters()
        if current['failed'] > first['failed']:
            raise ValueError('恢复邮件投递失败，请检查 SMTP')
        if current['total'] > first['total']:
            print('观察到邮件发送计数增加且无新增失败；请核对收件箱中的演练故障/恢复两封邮件。')
            return
        time.sleep(2)
    raise ValueError('等待恢复邮件超时；收件箱是否到达仍需核对')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'check', 'up', 'status', 'stop', 'test-email'])
    parser.add_argument('--monitor-only', action='store_true', help='只启用监控，明确关闭邮件配置')
    args = parser.parse_args()
    if args.monitor_only and args.command not in ('prepare', 'up'):
        parser.error('--monitor-only 只适用于 prepare/up')
    try:
        if args.command in ('prepare', 'up'):
            prepare(args.monitor_only)
        if args.command in ('check', 'up'):
            check()
        if args.command == 'up':
            compose('up', '-d', '--force-recreate', '--wait')
            status()
        elif args.command == 'status':
            status()
        elif args.command == 'stop':
            compose('stop')
        elif args.command == 'test-email':
            smoke()
    except (ValueError, OSError, urllib.error.URLError) as exc:
        # 网络异常只输出类型，防止 SMTP/HTTP 回显凭据或配置。
        print(str(exc) if isinstance(exc, ValueError) else f'操作失败：{type(exc).__name__}', file=sys.stderr)
        return 1
    except subprocess.CalledProcessError:
        print('Docker 校验或操作失败，请检查上方输出。', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
