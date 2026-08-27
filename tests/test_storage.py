import sqlite3

import storage


def test_get_default(monkeypatch, tmp_path):
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "p.db")
    assert storage.get_progress("abc") == 0.0


def test_save_then_get(monkeypatch, tmp_path):
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "p.db")
    storage.save_progress("abc", 123.5)
    assert storage.get_progress("abc") == 123.5


def test_upsert(monkeypatch, tmp_path):
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "p.db")
    storage.save_progress("abc", 10.0)
    storage.save_progress("abc", 20.0)
    assert storage.get_progress("abc") == 20.0
    conn = sqlite3.connect(tmp_path / "p.db")
    assert conn.execute("SELECT COUNT(*) FROM progress").fetchone()[0] == 1
    conn.close()


def test_save_zero_deletes(monkeypatch, tmp_path):
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "p.db")
    storage.save_progress("abc", 55.0)
    storage.save_progress("abc", 0.0)
    assert storage.get_progress("abc") == 0.0
    conn = sqlite3.connect(tmp_path / "p.db")
    assert conn.execute("SELECT COUNT(*) FROM progress").fetchone()[0] == 0
    conn.close()
