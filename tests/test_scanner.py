import os

from scanner import FileScanner, detect_type, file_id


def test_detect_type():
    assert detect_type("a.mp4") == "video"
    assert detect_type("A.MKV") == "video"
    assert detect_type("b.png") == "image"
    assert detect_type("c.txt") == "other"


def test_file_id_stable_and_short():
    assert file_id("r1", "sub/a.mp4") == file_id("r1", "sub/a.mp4")
    assert len(file_id("r1", "sub/a.mp4")) == 16


def test_scan_recursive(tmp_path):
    (tmp_path / "sub").mkdir()
    (tmp_path / "a.mp4").write_bytes(b"123")
    (tmp_path / "sub" / "b.jpg").write_bytes(b"4567")
    (tmp_path / "c.txt").write_bytes(b"x")
    files = FileScanner([str(tmp_path)]).scan()
    assert {f.name for f in files} == {"a.mp4", "b.jpg", "c.txt"}
    b = next(f for f in files if f.name == "b.jpg")
    assert b.path == os.path.join("sub", "b.jpg")
    assert b.type == "image"
    assert b.size == 4
    assert b.dir == str(tmp_path)


def test_scan_skips_missing_dir(tmp_path):
    assert FileScanner([str(tmp_path / "nope")]).scan() == []


def test_scan_cache(tmp_path, monkeypatch):
    (tmp_path / "a.mp4").write_bytes(b"1")
    fs = FileScanner([str(tmp_path)])
    assert len(fs.scan()) == 1
    (tmp_path / "b.mp4").write_bytes(b"2")
    assert len(fs.scan()) == 1  # 命中缓存
    monkeypatch.setattr(fs, "_cached_at", 0.0)  # 强制过期
    assert len(fs.scan()) == 2
