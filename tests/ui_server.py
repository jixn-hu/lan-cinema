"""Isolated UI fixture: never changes the user's config or media library."""
import os
from contextlib import asynccontextmanager
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app as backend
import storage
from scanner import FileScanner

fixture = Path(__file__).resolve().parents[1] / ".artifacts" / "ui-media"
fixture.mkdir(parents=True, exist_ok=True)
backend.scanner = FileScanner([str(fixture)])
backend.CONFIG_PATH = fixture.parent / "ui-config.json"
backend.THUMBNAIL_DIR = fixture.parent / "ui-covers"
storage.DB_PATH = fixture.parent / "ui-progress.db"
os.environ["FB_PORT"] = "8876"


@asynccontextmanager
async def fixture_lifespan(application):
    # UI fixtures must never advertise themselves as the user's real desktop.
    yield


backend.app.router.lifespan_context = fixture_lifespan

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(backend.app, host="127.0.0.1", port=8876, log_level="warning")
