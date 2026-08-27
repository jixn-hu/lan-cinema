"""局域网文件浏览器后端：REST API + 静态页面托管。"""
import json
import mimetypes
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from scanner import FileScanner
from storage import get_progress, save_progress

BASE_DIR = Path(__file__).parent
CONFIG_PATH = BASE_DIR / "config.json"

DEFAULT_CONFIG = {"dirs": [r"D:\迅雷下载\other1"]}


def load_config() -> dict:
    if not CONFIG_PATH.exists():
        CONFIG_PATH.write_text(
            json.dumps(DEFAULT_CONFIG, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return DEFAULT_CONFIG
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


app = FastAPI(title="文件浏览器")

scanner = FileScanner(load_config().get("dirs", []))


@app.get("/api/dirs")
def list_dirs():
    return {"dirs": scanner.dirs}


_SORT_KEYS = {"name": lambda f: f.name.lower(), "size": lambda f: f.size, "mtime": lambda f: f.mtime}


@app.get("/api/files")
def list_files(
    dir: str | None = Query(None),
    search: str | None = Query(None),
    type: str = Query("all"),
    sort: str = Query("name"),
    order: str = Query("asc"),
):
    files = scanner.scan()
    if dir:
        files = [f for f in files if f.dir == dir]
    if search:
        needle = search.lower()
        files = [f for f in files if needle in f.name.lower()]
    if type == "all":
        files = [f for f in files if f.type in ("video", "image")]
    elif type in ("video", "image", "other"):
        files = [f for f in files if f.type == type]
    files.sort(key=_SORT_KEYS.get(sort, _SORT_KEYS["name"]), reverse=(order == "desc"))
    return [
        {"id": f.id, "name": f.name, "size": f.size, "mtime": f.mtime,
         "type": f.type, "dir": f.dir, "path": f.path}
        for f in files
    ]


_FALLBACK_MEDIA = {"video": "video/mp4", "image": "image/jpeg", "other": "application/octet-stream"}


@app.get("/api/file/{file_id}/stream")
def stream_file(file_id: str, download: bool = False):
    target = next((f for f in scanner.scan() if f.id == file_id), None)
    if target is None:
        raise HTTPException(status_code=404, detail="文件不存在")
    full_path = Path(target.dir) / target.path
    if not full_path.is_file():
        raise HTTPException(status_code=404, detail="文件不存在")
    headers = {}
    if download or target.type == "other":
        headers["Content-Disposition"] = f'attachment; filename="{target.name}"'
        return FileResponse(full_path, media_type="application/octet-stream", headers=headers)
    media_type = mimetypes.guess_type(target.name)[0] or _FALLBACK_MEDIA[target.type]
    return FileResponse(full_path, media_type=media_type, headers=headers)


class ProgressBody(BaseModel):
    position: float


@app.get("/api/progress/{file_id}")
def read_progress(file_id: str):
    return {"position": get_progress(file_id)}


@app.put("/api/progress/{file_id}")
def write_progress(file_id: str, body: ProgressBody):
    save_progress(file_id, body.position)
    return {"ok": True}


app.mount("/", StaticFiles(directory=BASE_DIR / "static", html=True), name="static")
