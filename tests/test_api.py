import os

import app as app_module
import storage
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
    fid = file_id(str(tmp_path), "v.mp4")
    r = client.get(f"/api/file/{fid}/stream", headers={"Range": "bytes=0-3"})
    assert r.status_code == 206
    assert r.content == b"0123"
    assert r.headers["content-range"] == "bytes 0-3/10"


def test_stream_full_video(tmp_path):
    (tmp_path / "v.mp4").write_bytes(b"0123456789")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id(str(tmp_path), "v.mp4")
    r = client.get(f"/api/file/{fid}/stream")
    assert r.status_code == 200
    assert r.content == b"0123456789"
    assert "video/mp4" in r.headers["content-type"]
    assert "content-disposition" not in r.headers  # 内嵌播放，不能是 attachment


def test_stream_download_flag(tmp_path):
    (tmp_path / "v.mp4").write_bytes(b"0123456789")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id(str(tmp_path), "v.mp4")
    r = client.get(f"/api/file/{fid}/stream", params={"download": "1"})
    assert r.status_code == 200
    assert "attachment" in r.headers["content-disposition"]


def test_stream_other_attachment(tmp_path):
    (tmp_path / "d.zip").write_bytes(b"x")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id(str(tmp_path), "d.zip")
    r = client.get(f"/api/file/{fid}/stream")
    assert "attachment" in r.headers["content-disposition"]


def test_stream_404(tmp_path):
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    assert client.get("/api/file/doesnotexist/stream").status_code == 404


def test_progress_api(monkeypatch, tmp_path):
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "p.db")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    assert client.get("/api/progress/x").json() == {"position": 0.0}
    r = client.put("/api/progress/x", json={"position": 42.5})
    assert r.status_code == 200
    assert client.get("/api/progress/x").json() == {"position": 42.5}
    client.put("/api/progress/x", json={"position": 0})
    assert client.get("/api/progress/x").json() == {"position": 0.0}


def test_stream_cjk_filename_attachment(tmp_path):
    (tmp_path / "中文文档.txt").write_bytes(b"abc")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id(str(tmp_path), "中文文档.txt")
    r = client.get(f"/api/file/{fid}/stream")
    assert r.status_code == 200
    assert "attachment" in r.headers["content-disposition"]
    assert "filename" in r.headers["content-disposition"]  # RFC 5987 filename*= 或引号形式


def test_stream_two_roots_no_collision(tmp_path):
    r1 = tmp_path / "r1"; r2 = tmp_path / "r2"
    r1.mkdir(); r2.mkdir()
    (r1 / "same.mp4").write_bytes(b"AAAA")
    (r2 / "same.mp4").write_bytes(b"BBBB")
    app_module.scanner = FileScanner([str(r1), str(r2)])
    client = TestClient(app_module.app)
    data = client.get("/api/files").json()
    assert len(data) == 2
    assert data[0]["id"] != data[1]["id"]
    f1 = client.get(f"/api/file/{data[0]['id']}/stream")
    f2 = client.get(f"/api/file/{data[1]['id']}/stream")
    assert (f1.content, f2.content) == (b"AAAA", b"BBBB")


def test_files_search_empty_result(tmp_path):
    (tmp_path / "a.mp4").write_bytes(b"x")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    assert client.get("/api/files", params={"search": "zzz"}).json() == []


def test_files_type_video_and_image(tmp_path):
    (tmp_path / "a.mp4").write_bytes(b"x")
    (tmp_path / "b.png").write_bytes(b"y")
    (tmp_path / "c.txt").write_bytes(b"z")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    assert [f["name"] for f in client.get("/api/files", params={"type": "video"}).json()] == ["a.mp4"]
    assert [f["name"] for f in client.get("/api/files", params={"type": "image"}).json()] == ["b.png"]


def test_files_sort_mtime(tmp_path):
    (tmp_path / "old.mp4").write_bytes(b"x")
    (tmp_path / "new.mp4").write_bytes(b"y")
    os.utime(tmp_path / "old.mp4", (1000000000, 1000000000))
    os.utime(tmp_path / "new.mp4", (2000000000, 2000000000))
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    data = client.get("/api/files", params={"sort": "mtime", "order": "desc"}).json()
    assert [f["name"] for f in data] == ["new.mp4", "old.mp4"]


def test_stream_file_deleted_after_scan_404(tmp_path):
    (tmp_path / "g.mp4").write_bytes(b"x")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id(str(tmp_path), "g.mp4")
    assert client.get(f"/api/file/{fid}/stream").status_code == 200  # 入缓存
    (tmp_path / "g.mp4").unlink()
    assert client.get(f"/api/file/{fid}/stream").status_code == 404  # is_file() 兜底


def test_stream_image_inline(tmp_path):
    (tmp_path / "p.png").write_bytes(b"123")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id(str(tmp_path), "p.png")
    r = client.get(f"/api/file/{fid}/stream")
    assert "image/png" in r.headers["content-type"]
    assert "content-disposition" not in r.headers


def test_stream_fallback_media_rmvb(tmp_path):
    (tmp_path / "v.rmvb").write_bytes(b"x")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id(str(tmp_path), "v.rmvb")
    r = client.get(f"/api/file/{fid}/stream")
    assert "video/mp4" in r.headers["content-type"]  # 类型兜底
