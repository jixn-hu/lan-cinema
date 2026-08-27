"""局域网文件浏览器后端：REST API + 静态页面托管。"""
import json
from pathlib import Path

from fastapi import FastAPI, Query
from fastapi.staticfiles import StaticFiles

from scanner import FileScanner

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


app.mount("/", StaticFiles(directory=BASE_DIR / "static", html=True), name="static")
