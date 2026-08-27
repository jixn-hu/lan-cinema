import app as app_module
from fastapi.testclient import TestClient
from scanner import FileScanner, file_id


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


def test_dirs_endpoint(tmp_path):
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    r = client.get("/api/dirs")
    assert r.status_code == 200
    assert r.json()["dirs"] == [str(tmp_path)]


def _setup_scanner(tmp_path):
    (tmp_path / "movie.mp4").write_bytes(b"x" * 1000)
    (tmp_path / "photo.png").write_bytes(b"y" * 2000)
    (tmp_path / "sub").mkdir()
    (tmp_path / "sub" / "Doc.txt").write_bytes(b"z")
    app_module.scanner = FileScanner([str(tmp_path)])
    return TestClient(app_module.app)


def test_files_default_video_image_mixed(tmp_path):
    client = _setup_scanner(tmp_path)
    r = client.get("/api/files")
    assert r.status_code == 200
    data = r.json()
    assert {f["name"] for f in data} == {"movie.mp4", "photo.png"}  # Doc.txt 被排除
    assert [f["name"] for f in data] == ["movie.mp4", "photo.png"]  # 默认名称升序


def test_files_type_other(tmp_path):
    client = _setup_scanner(tmp_path)
    data = client.get("/api/files", params={"type": "other"}).json()
    assert [f["name"] for f in data] == ["Doc.txt"]
    assert data[0]["path"].endswith("Doc.txt")  # 含子文件夹的相对路径


def test_files_search_case_insensitive(tmp_path):
    client = _setup_scanner(tmp_path)
    data = client.get("/api/files", params={"search": "MOVIE"}).json()
    assert [f["name"] for f in data] == ["movie.mp4"]


def test_files_sort_size_desc(tmp_path):
    client = _setup_scanner(tmp_path)
    data = client.get("/api/files", params={"sort": "size", "order": "desc"}).json()
    assert data[0]["name"] == "photo.png"
    assert data[1]["name"] == "movie.mp4"


def test_files_filter_dir(tmp_path):
    other = tmp_path / "other"
    other.mkdir()
    (other / "alone.mp4").write_bytes(b"q")
    client = _setup_scanner(tmp_path)
    # 修正计划原文：dir 按「配置根目录」精确匹配（scanner 的 dir 字段=根目录），
    # 故把 other 也加为根目录，否则过滤结果为空
    app_module.scanner = FileScanner([str(tmp_path), str(other)])
    data = client.get("/api/files", params={"dir": str(other)}).json()
    assert [f["name"] for f in data] == ["alone.mp4"]


def test_stream_video_range(tmp_path):
    (tmp_path / "v.mp4").write_bytes(b"0123456789")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id("v.mp4")
    r = client.get(f"/api/file/{fid}/stream", headers={"Range": "bytes=0-3"})
    assert r.status_code == 206
    assert r.content == b"0123"
    assert r.headers["content-range"] == "bytes 0-3/10"


def test_stream_full_video(tmp_path):
    (tmp_path / "v.mp4").write_bytes(b"0123456789")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id("v.mp4")
    r = client.get(f"/api/file/{fid}/stream")
    assert r.status_code == 200
    assert r.content == b"0123456789"
    assert "video/mp4" in r.headers["content-type"]
    assert "content-disposition" not in r.headers  # 内嵌播放，不能是 attachment


def test_stream_download_flag(tmp_path):
    (tmp_path / "v.mp4").write_bytes(b"0123456789")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id("v.mp4")
    r = client.get(f"/api/file/{fid}/stream", params={"download": "1"})
    assert r.status_code == 200
    assert "attachment" in r.headers["content-disposition"]


def test_stream_other_attachment(tmp_path):
    (tmp_path / "d.zip").write_bytes(b"x")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id("d.zip")
    r = client.get(f"/api/file/{fid}/stream")
    assert "attachment" in r.headers["content-disposition"]


def test_stream_404(tmp_path):
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    assert client.get("/api/file/doesnotexist/stream").status_code == 404
