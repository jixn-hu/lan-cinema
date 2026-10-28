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
    video = client.get("/api/files").json()[0]
    return client, video


def test_remote_delete_removes_source_and_updates_other_client_without_rescan(tmp_path, monkeypatch):
    client, video = library(tmp_path, monkeypatch)
    other = TestClient(backend.app)
    revision = other.get("/api/changes").json()
    client.put(f"/api/progress/{video['id']}", json={"position": 12})
    monkeypatch.setattr("scanner.os.walk", lambda *args: (_ for _ in ()).throw(AssertionError("Unexpected directory scan")))
    deleted = client.request("DELETE", f"/api/file/{video['id']}", json={"size": video["size"], "mtime": video["mtime"]})
    assert deleted.status_code == 200
    assert not (tmp_path / "episode.mp4").exists()
    assert other.get("/api/changes").json() != revision
    assert other.get("/api/files").json() == []
    assert other.get(f"/api/file/{video['id']}/stream").status_code == 404
    assert video["id"] not in other.get("/api/progress").json()


def test_changed_file_cannot_be_deleted_with_old_confirmation(tmp_path, monkeypatch):
    client, video = library(tmp_path, monkeypatch)
    path = tmp_path / "episode.mp4"
    path.write_bytes(b"a new replacement video")
    response = client.request("DELETE", f"/api/file/{video['id']}", json=video)
    assert response.status_code == 409
    assert path.read_bytes() == b"a new replacement video"


def test_delete_permission_failure_preserves_entry(tmp_path, monkeypatch):
    client, video = library(tmp_path, monkeypatch)
    original = Path.unlink
    def denied(path, *args, **kwargs):
        if path.name == "episode.mp4":
            raise PermissionError("denied")
        return original(path, *args, **kwargs)
    monkeypatch.setattr(Path, "unlink", denied)
    response = client.request("DELETE", f"/api/file/{video['id']}", json=video)
    assert response.status_code == 403
    assert client.get("/api/files").json()[0]["id"] == video["id"]


def test_manual_mode_keeps_snapshot_until_explicit_refresh(tmp_path, monkeypatch):
    client, video = library(tmp_path, monkeypatch)
    (tmp_path / "new.mp4").write_bytes(b"new")
    import scanner
    now = scanner.time.time()
    monkeypatch.setattr(scanner.time, "time", lambda: now + 86400)
    assert len(client.get("/api/files").json()) == 1
    assert len(client.get("/api/files?refresh=true").json()) == 2


def test_cross_device_delete_preflight_and_version_required(tmp_path, monkeypatch):
    client, video = library(tmp_path, monkeypatch)
    response = client.options(f"/api/file/{video['id']}", headers={
        "Origin": "http://192.168.1.3:8765", "Access-Control-Request-Method": "DELETE",
        "Access-Control-Request-Headers": "content-type",
    })
    assert response.status_code == 200
    assert "DELETE" in response.headers["access-control-allow-methods"]
    assert client.request("DELETE", f"/api/file/{video['id']}", json={}).status_code == 422
