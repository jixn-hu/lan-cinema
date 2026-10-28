"""DNS-SD discovery shared with Android NsdManager; never proxies file data."""
import logging
import socket
import threading
import time
import uuid

SERVICE_TYPE = "_lanvideo._tcp.local."
DEVICE_ID = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"lanvideo:{uuid.getnode()}"))


def addresses():
    try:
        return sorted({entry[4][0] for entry in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
                       if not entry[4][0].startswith(("127.", "169.254."))})
    except OSError:
        return []


class LanDiscovery:
    def __init__(self):
        self.peers = {}
        self.lock = threading.Lock()
        self.zeroconf = None
        self.browser = None
        self.info = None
        self.error = ""

    def start(self, port):
        try:
            from zeroconf import IPVersion, ServiceBrowser, ServiceInfo, Zeroconf
            self.zeroconf = Zeroconf(ip_version=IPVersion.V4Only)
            self.info = ServiceInfo(
                SERVICE_TYPE, f"LanVideo-{DEVICE_ID[:8]}.{SERVICE_TYPE}",
                addresses=[socket.inet_aton(ip) for ip in addresses()] or [socket.inet_aton("127.0.0.1")],
                port=port, properties={"id": DEVICE_ID, "name": socket.gethostname(), "platform": "desktop", "version": "1"},
                server=f"lanvideo-{DEVICE_ID[:8]}.local.",
            )
            self.zeroconf.register_service(self.info)
            self.browser = ServiceBrowser(self.zeroconf, SERVICE_TYPE, self)
            self.error = ""
        except Exception as exc:
            self.error = f"自动发现不可用：{exc}"
            logging.warning(self.error)
            self.stop()

    def add_service(self, zc, service_type, name):
        info = zc.get_service_info(service_type, name, timeout=2000)
        if not info:
            return
        props = {key.decode(): value.decode(errors="replace") for key, value in info.properties.items() if value}
        if props.get("id") == DEVICE_ID:
            return
        ips = info.parsed_addresses()
        if not ips:
            return
        with self.lock:
            self.peers[name] = {"id": props.get("id", name), "name": props.get("name", name),
                                "platform": props.get("platform", "android"),
                                "url": f"http://{ips[0]}:{info.port}", "seen": time.time()}

    def update_service(self, zc, service_type, name):
        self.add_service(zc, service_type, name)

    def remove_service(self, zc, service_type, name):
        with self.lock:
            self.peers.pop(name, None)

    def devices(self):
        with self.lock:
            return list(self.peers.values())

    def stop(self):
        if self.browser:
            self.browser.cancel()
            self.browser = None
        if self.zeroconf:
            self.zeroconf.close()
            self.zeroconf = None


discovery = LanDiscovery()
