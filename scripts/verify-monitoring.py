#!/usr/bin/env python3
"""隔离验证真实 Prometheus → Alertmanager → STARTTLS SMTP 的故障/恢复邮件。
只向本机测试 SMTP 投递，使用随机容器、网络和数据卷，不读取真实邮箱配置。
"""
import datetime as dt
from email import policy
from email.parser import BytesParser
import http.server
import json
from pathlib import Path
import secrets
import shutil
import socketserver
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid

from monitoring import BASE, PROMETHEUS_IMAGE, ALERTMANAGER_IMAGE


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.PIPE).strip()


def request(port, path, data=None):
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}',
                                 data=None if data is None else json.dumps(data).encode(),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=5) as response:
        return response.read().decode()


def until(predicate, seconds=150):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError('等待验证条件超时')


def main():
    name = 'koko-monitor-test-' + uuid.uuid4().hex[:10]
    work = Path(tempfile.mkdtemp(prefix=name))
    work.chmod(0o755)
    containers, volumes = [], []
    servers = []
    token = secrets.token_hex(24)
    state = {'backlog': 70, 'tls': 0, 'auth': 0, 'rejected': 0, 'messages': []}
    try:
        # 自签 CA 仅用于本次 SMTP，生产仍使用系统 CA，不关闭 TLS 校验。
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN=host.docker.internal', '-addext', 'subjectAltName=DNS:host.docker.internal',
                        '-keyout', str(work / 'key.pem'), '-out', str(work / 'ca.pem')],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(work / 'ca.pem', work / 'key.pem')

        class Exporter(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get('Authorization') != 'Bearer ' + token:
                    self.send_error(401)
                    return
                body = f'koko_outbox_oldest_seconds {state["backlog"]}\n'.encode()
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain; version=0.0.4')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *_):
                pass

        class SMTP(socketserver.StreamRequestHandler):
            def handle(self):
                self.connection.settimeout(15)
                tls = False
                def reply(text):
                    self.wfile.write((text + '\r\n').encode())
                    self.wfile.flush()
                reply('220 test SMTP')
                while True:
                    line = self.rfile.readline(65536)
                    if not line:
                        return
                    verb = line.decode(errors='replace').split()[0].upper()
                    if verb in ('EHLO', 'HELO'):
                        reply('250-test')
                        reply('250-AUTH PLAIN' if tls else '250-STARTTLS')
                        reply('250 OK')
                    elif verb == 'STARTTLS':
                        reply('220 Ready for TLS')
                        self.connection = context.wrap_socket(self.connection, server_side=True)
                        self.rfile = self.connection.makefile('rb')
                        self.wfile = self.connection.makefile('wb')
                        tls = True
                        state['tls'] += 1
                    elif verb == 'AUTH':
                        assert tls, 'SMTP 认证必须在 TLS 后'
                        import base64
                        fields = line.decode().strip().split()
                        decoded = base64.b64decode(fields[2]).split(b'\x00')
                        assert decoded[-2:] == [b'test-user', b'test-password']
                        state['auth'] += 1
                        reply('235 Authenticated')
                    elif verb in ('MAIL', 'RCPT', 'RSET'):
                        reply('250 OK')
                    elif verb == 'DATA':
                        reply('354 End with dot')
                        lines = []
                        while True:
                            part = self.rfile.readline(65536)
                            if not part or part == b'.\r\n':
                                break
                            lines.append(part[1:] if part.startswith(b'..') else part)
                        if state['rejected'] == 0:
                            state['rejected'] += 1
                            reply('451 Simulated temporary delivery failure')
                        else:
                            state['messages'].append(BytesParser(policy=policy.default).parsebytes(b''.join(lines)))
                            reply('250 Message accepted')
                    elif verb == 'QUIT':
                        reply('221 Bye')
                        return
                    else:
                        reply('500 Unsupported')

        class SMTPServer(socketserver.ThreadingTCPServer):
            daemon_threads = True
        exporter = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Exporter)
        smtp = SMTPServer(('127.0.0.1', 0), SMTP)
        for server in (exporter, smtp):
            servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
        # 直接复用受版本控制的规则与邮件模板，只缩短聚合等待以加快测试。
        for source in ('alerts.yml', 'email.tmpl'):
            shutil.copy(BASE / source, work / source)
        (work / 'token').write_text(token)
        (work / 'smtp-password').write_text('test-password')
        config = (BASE / 'alertmanager.yml').read_text()
        replacements = {'SMTP_TO': 'receiver@example.invalid', 'SMTP_FROM': 'sender@example.invalid',
                        'SMTP_SMARTHOST': f'host.docker.internal:{smtp.server_address[1]}', 'SMTP_USERNAME': 'test-user'}
        for key, value in replacements.items():
            config = config.replace('__' + key + '__', json.dumps(value))
        config = config.replace('group_wait: 30s', 'group_wait: 1s').replace('group_interval: 1m', 'group_interval: 1s')
        config = config.replace('/etc/alertmanager', '/work').replace('/secrets/smtp-password', '/smtp-password')
        config = config.replace('min_version: TLS12', 'min_version: TLS12, ca_file: /work/ca.pem')
        (work / 'alertmanager.yml').write_text(config)
        prom = (BASE / 'prometheus.yml').read_text().replace('/etc/prometheus/secrets/koko-ops-token', '/work/token')
        prom = prom.replace('/etc/prometheus/targets.json', '/work/targets.json')
        (work / 'prometheus.yml').write_text(prom)
        (work / 'targets.json').write_text(json.dumps([{'targets': [f'host.docker.internal:{exporter.server_port}']}]))
        for path in work.iterdir():
            if path.name != 'key.pem':
                path.chmod(0o644)
        docker('network', 'create', name)
        for service, image, internal_port, data_path in [('alertmanager', ALERTMANAGER_IMAGE, 9093, '/alertmanager'),
                                                       ('prometheus', PROMETHEUS_IMAGE, 9090, '/prometheus')]:
            cname, volume = name + '-' + service, name + '-' + service + '-data'
            containers.append(cname)
            volumes.append(volume)
            args = ['run', '-d', '--name', cname, '--network', name, '--network-alias', service,
                    '--add-host', 'host.docker.internal:host-gateway', '-p', f'127.0.0.1::{internal_port}',
                    '-v', f'{work}:/work:ro', '-v', f'{volume}:{data_path}', image,
                    '--config.file=/work/' + service + '.yml']
            args += ['--cluster.listen-address='] if service == 'alertmanager' else ['--storage.tsdb.path=/prometheus']
            docker(*args)
        ports = {service: int(docker('port', name + '-' + service, str(port)).rsplit(':', 1)[1])
                 for service, port in [('prometheus', 9090), ('alertmanager', 9093)]}
        until(lambda: request(ports['prometheus'], '/-/ready'))
        print('隔离监控已启动，等待真实积压规则触发和 SMTP 失败重试。', flush=True)
        until(lambda: any('[告警]' in str(m['Subject']) and 'KokoOutboxBacklog' in str(m['Subject']) for m in state['messages']))
        assert state['rejected'] == 1 and state['tls'] >= 2 and state['auth'] >= 2
        state['backlog'] = 0
        print('故障邮件已被本机 SMTP 接收；等待规则恢复邮件。', flush=True)
        until(lambda: any('[恢复]' in str(m['Subject']) and 'KokoOutboxBacklog' in str(m['Subject']) for m in state['messages']))
        message = next(m for m in state['messages'] if 'KokoOutboxBacklog' in str(m['Subject']))
        assert '消息发布积压' in message.get_body(preferencelist=('plain',)).get_content()
        assert str(message['To']) == 'receiver@example.invalid'
        now = dt.datetime.now(dt.timezone.utc)
        silence = {'matchers': [{'name': 'alertname', 'value': 'TestPersistence', 'isRegex': False}],
                   'startsAt': now.isoformat(), 'endsAt': (now + dt.timedelta(hours=1)).isoformat(),
                   'createdBy': 'isolated-test', 'comment': '验证重启后静默仍然存在'}
        silence_id = json.loads(request(ports['alertmanager'], '/api/v2/silences', silence))['silenceID']
        # 优雅重启刷新通知日志和静默记录；数据卷不删除。
        docker('restart', '-t', '10', *containers)
        # Docker 自动分配的宿主机端口在重启后可能改变，重新读取映射。
        ports = {service: int(docker('port', name + '-' + service, str(port)).rsplit(':', 1)[1])
                 for service, port in [('prometheus', 9090), ('alertmanager', 9093)]}
        until(lambda: request(ports['prometheus'], '/-/ready'))
        until(lambda: request(ports['alertmanager'], '/-/ready') is not None)
        assert json.loads(request(ports['alertmanager'], '/api/v2/silence/' + silence_id))['id'] == silence_id
        from urllib.parse import quote
        samples = json.loads(request(ports['prometheus'], '/api/v1/query?query=' + quote('max_over_time(koko_outbox_oldest_seconds[10m])')))
        assert float(samples['data']['result'][0]['value'][1]) == 70
        print('通过：受保护抓取、真实规则故障/恢复、TLS+认证邮件、SMTP 临时失败重试、中文内容、重启后指标及静默保留。')
    finally:
        for cname in containers:
            subprocess.run(['docker', 'rm', '-f', cname], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for volume in volumes:
            subprocess.run(['docker', 'volume', 'rm', volume], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['docker', 'network', 'rm', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for server in servers:
            server.shutdown()
            server.server_close()
        shutil.rmtree(work)


if __name__ == '__main__':
    main()
