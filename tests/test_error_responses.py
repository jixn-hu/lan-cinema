"""Cross-device errors must remain readable by the App's WebView."""
import importlib
from pathlib import Path

from fastapi.testclient import TestClient

import app as backend
import storage
from scanner import FileScanner


def library(tmp_path, monkeypatch):
    (tmp_path / "episode.mp4").write_bytes(b"original video")
    monkeypatch.setattr(backend, "scanner", FileScanner([str(tmp_path)]))
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "progress.db")
    client = TestClient(backend.app)
    return client, client.get("/api/files").json()[0]


def test_token_gate_allows_preflight_but_rejects_unauthorized_api(monkeypatch):
    monkeypatch.setattr("sys.argv", ["serve.py"])
    serve = importlib.import_module("serve")
    monkeypatch.setattr(serve, "TOKEN", "test-token")
    client = TestClient(serve.TokenGate(backend.app))
    origin = {"Origin": "http://192.168.1.3:8765"}
    response = client.options("/api/file/missing", headers={
        **origin, "Access-Control-Request-Method": "DELETE",
        "Access-Control-Request-Headers": "content-type",
    })
    assert response.status_code == 200
    response = client.delete("/api/file/missing", headers=origin)
    assert response.status_code == 403
    assert response.headers["access-control-allow-origin"] == "*"
    assert "令牌" in response.json()["detail"]
    assert client.get("/api/device?t=test-token", headers=origin).status_code == 200


def test_unexpected_error_has_cors_and_json(monkeypatch):
    def broken():
        raise RuntimeError("fixture database failure")
    monkeypatch.setattr(backend, "get_progresses", broken)
    response = TestClient(backend.app, raise_server_exceptions=False).get(
        "/api/progress", headers={"Origin": "http://192.168.1.3:8765"})
    assert response.status_code == 500
    assert response.headers["access-control-allow-origin"] == "*"
    assert "源设备处理请求失败" in response.json()["detail"]


def test_os_error_delete_preserves_file_and_returns_readable_conflict(tmp_path, monkeypatch):
    client, video = library(tmp_path, monkeypatch)
    original = Path.unlink
    def unavailable(path, *args, **kwargs):
        if path.name == "episode.mp4":
            raise OSError("disk unavailable")
        return original(path, *args, **kwargs)
    monkeypatch.setattr(Path, "unlink", unavailable)
    response = client.request("DELETE", f"/api/file/{video['id']}", json=video,
                              headers={"Origin": "http://192.168.1.3:8765"})
    assert response.status_code == 409
    assert response.headers["access-control-allow-origin"] == "*"
    assert "磁盘" in response.json()["detail"]
    assert (tmp_path / "episode.mp4").exists()


def test_deleted_file_is_success_even_if_resume_cleanup_fails(tmp_path, monkeypatch):
    client, video = library(tmp_path, monkeypatch)
    def broken(*args):
        raise OSError("progress database locked")
    monkeypatch.setattr(backend, "save_progress", broken)
    response = client.request("DELETE", f"/api/file/{video['id']}", json=video)
    assert response.status_code == 200
    assert not (tmp_path / "episode.mp4").exists()
    assert client.get("/api/files").json() == []


def test_favorite_metadata_reads_current_revision_without_directory_rescan(tmp_path, monkeypatch):
    client, video = library(tmp_path, monkeypatch)
    path = tmp_path / "episode.mp4"
    path.write_bytes(b"a replacement with a new revision")
    monkeypatch.setattr("scanner.os.walk", lambda *args: (_ for _ in ()).throw(AssertionError("Unexpected rescan")))
    info = client.get(f"/api/file/{video['id']}").json()
    assert info["size"] == path.stat().st_size != video["size"]
    assert info["id"] == video["id"]
    assert "file-info" in client.get("/api/device").json()["features"]
    path.unlink()
    assert client.get(f"/api/file/{video['id']}").status_code == 404
