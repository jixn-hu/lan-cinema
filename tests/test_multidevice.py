import concurrent.futures
import json
import socket

import pytest
from fastapi.testclient import TestClient

import app as backend
import storage
from discovery import DEVICE_ID, LanDiscovery, SERVICE_TYPE
from scanner import FileScanner, file_id


def test_device_protocol_and_actual_port(monkeypatch):
    monkeypatch.setattr(backend, "lan_addresses", lambda: ["192.168.1.8"])
    client = TestClient(backend.app, base_url="http://server:9123")
    device = client.get("/api/device").json()
    assert device["version"] == 1 and device["platform"] == "desktop"
    assert device["id"] == DEVICE_ID
    assert client.get("/api/access").json()["lan"] == ["http://192.168.1.8:9123"]


def test_sparse_three_gigabyte_ranges_and_head(tmp_path, monkeypatch):
    path = tmp_path / "大视频.mp4"
    offset = 3 * 1024 ** 3
    tail = b"video-tail-123456"
    with path.open("wb") as output:
        output.seek(offset)
        output.write(tail)
    monkeypatch.setattr(backend, "scanner", FileScanner([str(tmp_path)]))
    client = TestClient(backend.app)
    url = f"/api/file/{file_id(str(tmp_path), path.name)}/stream"
    head = client.head(url)
    assert head.status_code == 200 and head.content == b""
    assert int(head.headers["content-length"]) == offset + len(tail)
    response = client.get(url, headers={"Range": f"bytes={offset}-"})
    assert response.status_code == 206 and response.content == b"video-tail-123456"
    suffix = client.get(url, headers={"Range": "bytes=-6"})
    assert suffix.content == b"123456"
    invalid = client.get(url, headers={"Range": f"bytes={offset + len(tail)}-"})
    assert invalid.status_code == 416
    assert invalid.headers["content-range"].endswith(f"*/{offset + len(tail)}")


def test_parallel_ranges_reassemble_without_corruption(tmp_path, monkeypatch):
    content = bytes(range(256)) * 16384
    (tmp_path / "movie.mp4").write_bytes(content)
    monkeypatch.setattr(backend, "scanner", FileScanner([str(tmp_path)]))
    client = TestClient(backend.app)
    url = f"/api/file/{file_id(str(tmp_path), 'movie.mp4')}/stream?download=1"
    step = len(content) // 4
    def part(index):
        response = client.get(url, headers={"Range": f"bytes={index*step}-{(index+1)*step-1}"})
        assert response.status_code == 206
        return response.content
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        assert b"".join(pool.map(part, range(4))) == content


def test_range_can_be_read_directly_by_other_device(tmp_path, monkeypatch):
    (tmp_path / "movie.mp4").write_bytes(b"0123456789")
    monkeypatch.setattr(backend, "scanner", FileScanner([str(tmp_path)]))
    client = TestClient(backend.app)
    response = client.get(f"/api/file/{file_id(str(tmp_path), 'movie.mp4')}/stream", headers={"Origin": "http://192.168.1.9:8765", "Range": "bytes=2-5"})
    assert response.headers["access-control-allow-origin"] == "*"
    assert "Content-Range" in response.headers["access-control-expose-headers"]
    assert response.content == b"2345"


def test_progress_batch_and_validation(tmp_path, monkeypatch):
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "progress.db")
    client = TestClient(backend.app)
    client.put("/api/progress/first", json={"position": 17.5})
    client.put("/api/progress/second", json={"position": 32})
    assert client.get("/api/progress").json() == {"first": 17.5, "second": 32}
    assert client.put("/api/progress/first", json={"position": -1}).status_code == 422
    assert client.put("/api/progress/first", content='{"position":"Infinity"}', headers={"Content-Type": "application/json"}).status_code == 422


def test_refresh_sees_new_files_immediately(tmp_path, monkeypatch):
    monkeypatch.setattr(backend, "scanner", FileScanner([str(tmp_path)]))
    client = TestClient(backend.app)
    assert client.get("/api/files").json() == []
    (tmp_path / "new.mp4").write_bytes(b"new")
    assert len(client.get("/api/files?refresh=true").json()) == 1


def test_config_updates_preserve_other_settings(tmp_path, monkeypatch):
    cfg = tmp_path / "config.json"
    cfg.write_text(json.dumps({"dirs": [], "name": "my desktop"}), encoding="utf-8")
    monkeypatch.setattr(backend, "CONFIG_PATH", cfg)
    monkeypatch.setattr(backend, "scanner", FileScanner([]))
    client = TestClient(backend.app)
    assert client.post("/api/dirs", json={"path": str(tmp_path)}).status_code == 201
    assert json.loads(cfg.read_text(encoding="utf-8"))["name"] == "my desktop"


def test_discovery_ignores_self_and_removes_departed_devices():
    discovery = LanDiscovery()
    class Info:
        properties = {b"id": b"phone-id", b"name": "安卓手机".encode(), b"platform": b"android"}
        port = 8765
        def parsed_addresses(self): return ["192.168.1.9"]
    class ZC:
        def get_service_info(self, *args, **kwargs): return Info()
    discovery.add_service(ZC(), SERVICE_TYPE, "phone")
    assert discovery.devices()[0]["url"] == "http://192.168.1.9:8765"
    Info.properties[b"id"] = DEVICE_ID.encode()
    discovery.add_service(ZC(), SERVICE_TYPE, "self")
    assert len(discovery.devices()) == 1
    discovery.remove_service(ZC(), SERVICE_TYPE, "phone")
    assert discovery.devices() == []


def test_symlinks_cannot_escape_shared_root(tmp_path, monkeypatch):
    root = tmp_path / "shared"
    root.mkdir()
    private = tmp_path / "private.mp4"
    private.write_bytes(b"private")
    link = root / "escape.mp4"
    try:
        link.symlink_to(private)
    except OSError:
        pytest.skip("Symlink creation unavailable")
    monkeypatch.setattr(backend, "scanner", FileScanner([str(root)]))
    client = TestClient(backend.app)
    assert client.get("/api/files").json() == []
    assert client.get(f"/api/file/{file_id(str(root), link.name)}/stream").status_code == 404
