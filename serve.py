"""双栈启动：同一端口同时接受 IPv4 与 IPv6 访问。

用法：
    python serve.py            # 默认 8000
    python serve.py 8080       # 指定端口

背景：Windows 的 IPv6 套接字默认 IPV6_V6ONLY=1，
所以 uvicorn --host :: 会丢掉 IPv4、--host 0.0.0.0 又没有 IPv6。
这里手工建一个关闭 V6ONLY 的套接字交给 uvicorn，一个进程同时服务两栈。
"""
import json
import os
import socket
import subprocess
import sys
from urllib.parse import parse_qs

import uvicorn

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
os.environ["FB_PORT"] = str(PORT)
from app import app

# 设置环境变量 FB_TOKEN 后启用访问令牌校验；不设则保持原样（局域网内不受影响）
TOKEN = os.environ.get("FB_TOKEN", "").strip()
COOKIE_NAME = "fb_token"

PS_QUERY = (
    "Get-NetIPAddress | Where-Object {"
    "$_.IPAddress -notlike 'fe80*' -and "
    "$_.IPAddress -ne '127.0.0.1' -and "
    "$_.IPAddress -ne '::1' -and "
    "$_.SuffixOrigin -ne 'Random' -and "
    "$_.AddressState -eq 'Preferred' -and "
    "$_.InterfaceAlias -notmatch 'VMware|vEthernet|Loopback|Bluetooth|WSL|本地连接'"
    "} | Select-Object IPAddress,AddressFamily,PrefixLength"
    " | ConvertTo-Json -Compress"
)


def local_addresses():
    """列本机可用于局域网访问的地址，排除回环、链路本地、临时地址与虚拟网卡。"""
    v4, v6 = [], []
    try:
        raw = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", PS_QUERY],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=10,
        ).stdout.strip()
        if raw:
            data = json.loads(raw)
            if isinstance(data, dict):
                data = [data]
            for item in data:
                ip = item.get("IPAddress", "")
                fam = item.get("AddressFamily")
                if not ip or ip.startswith("169.254."):
                    continue
                if fam == 2 or "." in ip:
                    v4.append(ip)
                else:
                    # 标出前缀长度，/64 的 SLAAC 地址比 /128 的更值得优先展示
                    v6.append((0 if item.get("PrefixLength") == 64 else 1, ip))
    except Exception as e:
        print(f"[提示] 自动获取本机地址失败（{e}），请手动查看 ipconfig")

    def rank(ip):
        # 192.168/10./172.16-31 开头优先，其余排后面
        if ip.startswith(("192.168.", "10.", "172.16.", "172.17.", "172.18.")):
            return 0
        return 1

    return sorted(set(v4), key=rank), [ip for _, ip in sorted(set(v6))]


class TokenGate:
    """极简访问令牌门：首次带 ?t=令牌访问，之后靠 cookie 记住。

    没设置 FB_TOKEN 时完全透明，不改变局域网原有用法。
    """

    def __init__(self, inner):
        self.inner = inner

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not TOKEN:
            await self.inner(scope, receive, send)
            return

        query = parse_qs(scope.get("query_string", b"").decode("latin-1"))
        if TOKEN in query.get("t", []):
            cookie = f"{COOKIE_NAME}={TOKEN}; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax"

            async def send_and_set_cookie(message):
                if message["type"] == "http.response.start":
                    headers = list(message.get("headers", []))
                    headers.append((b"set-cookie", cookie.encode()))
                    message = {**message, "headers": headers}
                await send(message)

            await self.inner(scope, receive, send_and_set_cookie)
            return

        for key, value in scope.get("headers", []):
            if key == b"cookie":
                for part in value.decode("latin-1").split(";"):
                    name, _, val = part.strip().partition("=")
                    if name == COOKIE_NAME and val == TOKEN:
                        await self.inner(scope, receive, send)
                        return

        body = (
            "<meta charset='utf-8'><h2>需要访问令牌</h2>"
            "<p>请在网址后面加上 <code>?t=你的令牌</code>，只需一次，之后浏览器会记住。</p>"
        ).encode()
        await send({
            "type": "http.response.start",
            "status": 403,
            "headers": [
                (b"content-type", b"text/html; charset=utf-8"),
                (b"content-length", str(len(body)).encode()),
            ],
        })
        await send({"type": "http.response.body", "body": body})


def banner(addrs_v4, addrs_v6):
    suffix = f"?t={TOKEN}" if TOKEN else ""
    line = "-" * 58
    print(line)
    print(f"  文件浏览器已启动，端口 {PORT}")
    if TOKEN:
        print("  已启用访问令牌：链接里已带 ?t=…，首次打开后浏览器会记住")
    print(line)
    if addrs_v4:
        print("  手机 / 局域网 IPv4 访问：")
        for ip in addrs_v4:
            print(f"    http://{ip}:{PORT}/{suffix}")
    if addrs_v6:
        print("  手机 / 局域网 IPv6 访问（手机需连同一个 WiFi）：")
        for ip in addrs_v6:
            print(f"    http://[{ip}]:{PORT}/{suffix}")
    else:
        print("  未发现全局 IPv6 地址，本次只能用 IPv4 访问。")
    print(line)
    print("  本机自测： http://127.0.0.1:%d/%s" % (PORT, suffix))
    print("  手机不在同一 WiFi 时，IPv4 走不通（运营商 CGNAT），")
    print("  只能靠 IPv6，且需光猫放行入站。")
    print(line)



def main():
    addrs_v4, addrs_v6 = local_addresses()

    sock = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
    try:
        sock.bind(("::", PORT))
    except OSError as e:
        print(f"[错误] 无法监听 {PORT} 端口：{e}")
        print("       端口可能已被占用（比如还开着一个旧的 uvicorn）。")
        print("       先关掉旧窗口，或换端口：python serve.py 8080")
        sys.exit(1)
    sock.listen(2048)

    banner(addrs_v4, addrs_v6)
    served = TokenGate(app) if TOKEN else app
    config = uvicorn.Config(served, host="::", port=PORT, log_level="info")
    uvicorn.Server(config).run(sockets=[sock])


if __name__ == "__main__":
    main()
