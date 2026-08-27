import app as app_module
from fastapi.testclient import TestClient


def test_load_config_creates_default(tmp_path, monkeypatch):
    cfg_path = tmp_path / "config.json"
    monkeypatch.setattr(app_module, "CONFIG_PATH", cfg_path)
    cfg = app_module.load_config()
    assert cfg == {"dirs": [r"D:\迅雷下载\other1"]}
    assert cfg_path.exists()


def test_load_config_reads_existing(tmp_path, monkeypatch):
    cfg_path = tmp_path / "config.json"
    cfg_path.write_text('{"dirs": ["C:\\\\tmp\\\\a"]}', encoding="utf-8")
    monkeypatch.setattr(app_module, "CONFIG_PATH", cfg_path)
    assert app_module.load_config() == {"dirs": ["C:\\tmp\\a"]}


def test_dirs_endpoint(tmp_path, monkeypatch):
    monkeypatch.setattr(app_module, "CONFIG_PATH", tmp_path / "config.json")
    client = TestClient(app_module.app)
    r = client.get("/api/dirs")
    assert r.status_code == 200
    assert r.json()["dirs"] == [r"D:\迅雷下载\other1"]
