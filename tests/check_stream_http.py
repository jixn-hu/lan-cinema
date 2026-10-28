"""Real socket regression against the isolated UI server, never user media."""
from pathlib import Path
import socket
import time

import httpx

BASE = "http://127.0.0.1:8876"
MEDIA = Path(__file__).resolve().parents[1] / ".artifacts" / "ui-media"
target = MEDIA / "stream-delete-fixture.mp4"
assert not target.exists()
connection = None
try:
    with target.open("wb") as file:
        file.seek(64 * 1024 * 1024 - 1)
        file.write(b"v")
    with httpx.Client(trust_env=False, timeout=8) as client:
        video = next(f for f in client.get(BASE + "/api/files?refresh=true").json()
                     if f["name"] == target.name)
        connection = socket.create_connection(("127.0.0.1", 8876), timeout=5)
        request = f"GET /api/file/{video['id']}/stream HTTP/1.1\r\nHost: 127.0.0.1:8876\r\nRange: bytes=0-\r\n\r\n"
        connection.sendall(request.encode("ascii"))
        received = b""
        while b"\r\n\r\n" not in received or not received.split(b"\r\n\r\n", 1)[1]:
            chunk = connection.recv(8192)
            assert chunk, "Stream ended before reading any video"
            received += chunk
        assert received.startswith(b"HTTP/1.1 206")
        response = client.request("DELETE", BASE + "/api/file/" + video["id"], json=video)
        assert response.status_code == 200, response.text
        connection.close()
        connection = None
        assert client.get(BASE + "/api/file/" + video["id"]).status_code == 404
        for _ in range(30):
            if not target.exists():
                break
            time.sleep(.1)
        assert not target.exists(), "Disconnected response still holds the deleted file open"
finally:
    if connection:
        connection.close()
    target.unlink(missing_ok=True)

print("Real HTTP 64 MiB slow video reader + concurrent source deletion + disconnect cleanup: passed")
