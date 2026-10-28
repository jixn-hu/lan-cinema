import asyncio
import os

import pytest
from fastapi.testclient import TestClient

import app as backend
import file_response
import storage
from scanner import FileScanner


@pytest.mark.parametrize("range_header", [None, "bytes=1-", "bytes=0-9,100-199"])
def test_disconnect_stops_stream_and_closes_file_even_during_cancel(tmp_path, monkeypatch, range_header):
    path = tmp_path / "large.mp4"
    path.write_bytes(b"v" * (backend.FILE_CHUNK_SIZE * 3))
    opened = []
    original = file_response.open_shared
    def track(path):
        file = original(path)
        opened.append(file)
        return file
    monkeypatch.setattr(file_response, "open_shared", track)

    async def run():
        first_chunk = asyncio.Event()
        body_messages = []
        async def send(message):
            if message["type"] == "http.response.body" and message.get("body"):
                body_messages.append(message)
                first_chunk.set()
                await asyncio.Event().wait()  # a slow client with a full socket buffer
        async def receive():
            await first_chunk.wait()
            return {"type": "http.disconnect"}
        headers = [(b"range", range_header.encode())] if range_header else []
        await asyncio.wait_for(backend._file_response(path)(
            {"type": "http", "method": "GET", "headers": headers}, receive, send), timeout=5)
        assert len(body_messages) == 1

    asyncio.run(run())
    assert len(opened) == 1
    assert opened[0].closed
    path.unlink()


@pytest.mark.parametrize("range_header", [None, "bytes=1-", "bytes=0-9,100-199"])
def test_delete_succeeds_while_our_video_response_is_open(tmp_path, monkeypatch, range_header):
    path = tmp_path / "playing.mp4"
    path.write_bytes(b"v" * (backend.FILE_CHUNK_SIZE * 2))
    monkeypatch.setattr(backend, "scanner", FileScanner([str(tmp_path)]))
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "progress.db")
    client = TestClient(backend.app)
    video = client.get("/api/files").json()[0]

    async def run():
        first_chunk, finish = asyncio.Event(), asyncio.Event()
        async def send(message):
            if message["type"] == "http.response.body" and message.get("body"):
                first_chunk.set()
                await finish.wait()
        async def receive():
            await asyncio.Event().wait()
        headers = [(b"range", range_header.encode())] if range_header else []
        task = asyncio.create_task(backend._file_response(path)(
            {"type": "http", "method": "GET", "headers": headers}, receive, send))
        try:
            await asyncio.wait_for(first_chunk.wait(), timeout=5)
            response = await asyncio.to_thread(client.request, "DELETE", f"/api/file/{video['id']}", json=video)
            assert response.status_code == 200, response.text
        finally:
            finish.set()
            await asyncio.wait_for(task, timeout=5)

    asyncio.run(run())
    assert not path.exists()
    assert client.get("/api/files").json() == []


def test_multipart_ranges_preserve_wire_length_and_bytes(tmp_path, monkeypatch):
    path = tmp_path / "video.mp4"
    path.write_bytes(bytes(range(256)))
    monkeypatch.setattr(backend, "scanner", FileScanner([str(tmp_path)]))
    client = TestClient(backend.app)
    video = client.get("/api/files").json()[0]
    response = client.get(f"/api/file/{video['id']}/stream", headers={"Range": "bytes=0-3,100-103"})
    assert response.status_code == 206
    assert len(response.content) == int(response.headers["content-length"])
    assert b"\x00\x01\x02\x03" in response.content and b"defg" in response.content


@pytest.mark.skipif(os.name != "nt", reason="Windows sharing violation")
def test_another_program_lock_is_reported_as_busy_and_file_is_kept(tmp_path, monkeypatch):
    path = tmp_path / "video.mp4"
    path.write_bytes(b"video")
    monkeypatch.setattr(backend, "scanner", FileScanner([str(tmp_path)]))
    client = TestClient(backend.app)
    video = client.get("/api/files").json()[0]
    with path.open("rb"):
        response = client.request("DELETE", f"/api/file/{video['id']}", json=video)
    assert response.status_code == 409
    assert "占用" in response.json()["detail"]
    assert "权限" not in response.json()["detail"]
    assert path.exists()
