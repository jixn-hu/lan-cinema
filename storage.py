"""SQLite 播放进度存储（每次操作独立连接）。"""
import sqlite3
import time
from pathlib import Path

DB_PATH = Path(__file__).parent / "progress.db"

_SCHEMA = """
CREATE TABLE IF NOT EXISTS progress (
    file_id TEXT PRIMARY KEY,
    position REAL,
    updated_at REAL
)
"""


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=10)
    try:
        # Readers must not block behind another device saving its playback time.
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute(_SCHEMA)
        return conn
    except BaseException:
        conn.close()
        raise


def get_progress(file_id: str) -> float:
    conn = _connect()
    try:
        row = conn.execute("SELECT position FROM progress WHERE file_id = ?", (file_id,)).fetchone()
        return row[0] if row else 0.0
    finally:
        conn.close()


def save_progress(file_id: str, position: float):
    if position <= 0:
        conn = _connect()
        try:
            conn.execute("DELETE FROM progress WHERE file_id = ?", (file_id,))
            conn.commit()
        finally:
            conn.close()
        return
    conn = _connect()
    try:
        conn.execute(
            "INSERT INTO progress (file_id, position, updated_at) VALUES (?, ?, ?) "
            "ON CONFLICT(file_id) DO UPDATE SET position = excluded.position, updated_at = excluded.updated_at",
            (file_id, position, time.time()),
        )
        conn.commit()
    finally:
        conn.close()


def get_progresses() -> dict[str, float]:
    conn = _connect()
    try:
        return dict(conn.execute("SELECT file_id, position FROM progress WHERE position > 0"))
    finally:
        conn.close()
