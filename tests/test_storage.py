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


def test_read_progress_is_not_blocked_by_another_devices_uncommitted_save(monkeypatch, tmp_path):
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "p.db")
    storage.save_progress("watched", 12)
    writer = sqlite3.connect(storage.DB_PATH)
    try:
        writer.execute("UPDATE progress SET position=24 WHERE file_id='watched'")
        assert storage.get_progress("watched") == 12
        assert storage.get_progresses() == {"watched": 12}
    finally:
        writer.rollback()
        writer.close()


def test_many_devices_save_and_read_progress_concurrently(monkeypatch, tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "p.db")
    def device(index):
        for position in range(1, 6):
            storage.save_progress(str(index), position)
            storage.get_progresses()
    with ThreadPoolExecutor(max_workers=12) as executor:
        list(executor.map(device, range(24)))
    assert storage.get_progresses() == {str(i): 5 for i in range(24)}
