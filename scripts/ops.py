#!/usr/bin/env python3
"""运维命令行：凭据只从环境读取，重放响应不明确时保留请求编号。"""
import argparse
import json
import os
import sys
import uuid
from urllib import request, error, parse


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # 防止重定向把运维凭据转发到其他地址。


def main():
    parser = argparse.ArgumentParser(description="koko-chat 后台运维")
    parser.add_argument("--url", default=os.getenv("KOKO_OPS_URL", "http://127.0.0.1:8080"))
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("status")
    commands.add_parser("metrics")
    listing = commands.add_parser("list")
    listing.add_argument("--after")
    listing.add_argument("--limit", type=int, default=20)
    action = commands.add_parser("action")
    action.add_argument("id")
    for name in ("replay", "ack"):
        command = commands.add_parser(name)
        command.add_argument("id")
        command.add_argument("--reason", required=True)
        command.add_argument("--request-id", default=None)
    args = parser.parse_args()
    base = parse.urlsplit(args.url)
    if base.username or base.password or base.query or base.fragment or base.path not in ("", "/"):
        parser.error("URL 必须是服务根地址")
    if base.scheme != "https" and not (base.scheme == "http" and base.hostname in ("127.0.0.1", "localhost", "::1")):
        parser.error("远程运维必须使用 HTTPS，本机回环地址可使用 HTTP")
    token = os.getenv("KOKO_OPS_TOKEN", "")
    if not token:
        parser.error("请先通过环境提供 KOKO_OPS_TOKEN")
    body = None
    request_id = None
    if args.command == "list":
        params = {"limit": args.limit}
        if args.after:
            params["after"] = args.after
        path = "dead-letters?" + parse.urlencode(params)
    elif args.command == "action":
        path = "actions/" + parse.quote(args.id, safe="")
    elif args.command in ("replay", "ack"):
        request_id = args.request_id or str(uuid.uuid4())
        path = "dead-letters/" + parse.quote(args.id, safe="") + ("/replays" if args.command == "replay" else "/acknowledgements")
        body = json.dumps({"requestId": request_id, "reason": args.reason}).encode()
        print("操作编号：" + request_id, file=sys.stderr, flush=True)
    else:
        path = args.command
    req = request.Request(args.url.rstrip("/") + "/internal/ops/" + path, data=body,
                          headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with request.build_opener(NoRedirect()).open(req, timeout=10) as response:
            content = response.read(1024 * 1024).decode()
            print(content if args.command == "metrics" else json.dumps(json.loads(content), ensure_ascii=False, indent=2))
    except error.HTTPError as failure:
        print("运维请求返回 HTTP " + str(failure.code) + "，请检查凭据、服务状态及请求参数。", file=sys.stderr)
        if request_id:
            print("需要重试时保持相同 --request-id " + request_id + "，也可先查询 action。", file=sys.stderr)
        return 1
    except (error.URLError, TimeoutError, OSError):
        print("运维连接失败。" + ("操作结果未知，请先查询 action " + request_id + "，重试时保留原编号。" if request_id else "请检查服务地址。"), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
