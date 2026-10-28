from fastapi.testclient import TestClient
import app as backend
from scanner import FileScanner, file_id


def test_changed_source_does_not_mix_old_and_new_download_bytes(tmp_path, monkeypatch):
    path = tmp_path / "video.mp4"
    path.write_bytes(b"first-version")
    monkeypatch.setattr(backend, "scanner", FileScanner([str(tmp_path)]))
    client = TestClient(backend.app)
    file = client.get("/api/files").json()[0]
    url = f"/api/file/{file['id']}/stream?rev={file['size']}-{file['mtime']}"
    assert client.get(url, headers={"Range": "bytes=0-4"}).content == b"first"
    path.write_bytes(b"changed-version")
    response = client.get(url, headers={"Range": "bytes=5-"})
    assert response.status_code == 409
