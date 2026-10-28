from types import SimpleNamespace

import discovery


def test_explicit_lan_addresses_ignore_loopback_and_virtual_noise(monkeypatch):
    monkeypatch.setenv("FB_ADVERTISE_IP", "192.168.1.2,127.0.0.1,169.254.1.2,192.168.1.2,invalid")
    assert discovery.addresses() == ["192.168.1.2"]


def test_route_address_wins_over_hostname_virtual_adapters(monkeypatch):
    class Route:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def connect(self, target): pass
        def getsockname(self): return ("192.168.1.2", 0)

    monkeypatch.setattr(discovery, "os", SimpleNamespace(name="posix", environ={}))
    monkeypatch.setattr(discovery, "socket", SimpleNamespace(
        AF_INET=2, SOCK_DGRAM=2, socket=lambda *args: Route(),
        getaddrinfo=lambda *args: [(2, 0, 0, "", ("172.25.160.1", 0))],
    ))
    assert discovery.addresses() == ["192.168.1.2"]


def test_windows_uses_connected_gateway_addresses(monkeypatch):
    monkeypatch.setattr(discovery, "os", SimpleNamespace(name="nt", environ={}))
    monkeypatch.setattr(discovery.subprocess, "run", lambda *args, **kwargs: SimpleNamespace(stdout="192.168.1.2\r\n"))
    assert discovery.addresses() == ["192.168.1.2"]
