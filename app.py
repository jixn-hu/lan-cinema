"""局域网文件浏览器后端：REST API + 静态页面托管。"""
import json
import mimetypes
import os
import socket
import subprocess
import tempfile
from contextlib import asynccontextmanager
import asyncio
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from scanner import FileScanner
from storage import get_progress, get_progresses, save_progress
from discovery import DEVICE_ID, addresses, discovery

BASE_DIR = Path(__file__).parent
CONFIG_PATH = BASE_DIR / "config.json"
THUMBNAIL_DIR = BASE_DIR / ".thumbnail_cache"
FILE_CHUNK_SIZE = 1024 * 1024

DEFAULT_CONFIG = {"dirs": []}


def load_config() -> dict:
    if not CONFIG_PATH.exists():
        CONFIG_PATH.write_text(
            json.dumps(DEFAULT_CONFIG, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return dict(DEFAULT_CONFIG)
    try:
        cfg = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"[警告] config.json 解析失败（{e}），回退默认配置 {DEFAULT_CONFIG}")
        return dict(DEFAULT_CONFIG)
    if not isinstance(cfg, dict) or not isinstance(cfg.get("dirs"), list):
        print(f"[警告] config.json 格式无效，回退默认配置 {DEFAULT_CONFIG}")
        return dict(DEFAULT_CONFIG)
    return cfg


@asynccontextmanager
async def lifespan(application):
    await asyncio.to_thread(discovery.start, int(os.environ.get("FB_PORT", "8000")))
    yield
    await asyncio.to_thread(discovery.stop)


app = FastAPI(title="局域影库", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["GET", "HEAD", "PUT", "OPTIONS"],
                   allow_headers=["Range", "Content-Type", "If-Range"],
                   expose_headers=["Accept-Ranges", "Content-Range", "Content-Length", "ETag", "Last-Modified"])

scanner = FileScanner(load_config().get("dirs", []))


class DirectoryBody(BaseModel):
    path: str


def save_dirs(dirs: list[str]) -> None:
    global scanner
    config = load_config()
    config["dirs"] = dirs
    CONFIG_PATH.write_text(
        json.dumps(config, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    scanner = FileScanner(dirs)


def lan_addresses() -> list[str]:
    return addresses()


@app.get("/api/access")
def access_urls(request: Request):
    port = request.url.port or 80
    return {
        "local": f"http://127.0.0.1:{port}",
        "lan": [f"http://{address}:{port}" for address in lan_addresses()],
    }


@app.get("/api/device")
def device_info():
    return {"id": DEVICE_ID, "name": socket.gethostname(), "platform": "desktop", "version": 1,
            "features": ["range", "progress", "download", "discovery"]}


@app.get("/api/devices")
def devices():
    return {"devices": discovery.devices(), "error": discovery.error}


@app.get("/api/dirs")
def list_dirs():
    return {"dirs": scanner.dirs}


@app.post("/api/dirs", status_code=201)
def add_dir(body: DirectoryBody):
    path = Path(body.path.strip()).expanduser()
    if not body.path.strip() or not path.is_dir():
        raise HTTPException(status_code=422, detail="目录不存在或无法访问")
    path_str = str(path.resolve())
    if any(os.path.normcase(d) == os.path.normcase(path_str) for d in scanner.dirs):
        raise HTTPException(status_code=409, detail="目录已添加")
    dirs = [*scanner.dirs, path_str]
    save_dirs(dirs)
    return {"dirs": dirs}


@app.delete("/api/dirs")
def remove_dir(body: DirectoryBody):
    target = os.path.normcase(os.path.normpath(body.path))
    dirs = [d for d in scanner.dirs if os.path.normcase(os.path.normpath(d)) != target]
    if len(dirs) == len(scanner.dirs):
        raise HTTPException(status_code=404, detail="目录未配置")
    save_dirs(dirs)
    return {"dirs": dirs}


_SORT_KEYS = {"name": lambda f: f.name.lower(), "size": lambda f: f.size, "mtime": lambda f: f.mtime}


@app.get("/api/files")
def list_files(
    dir: str | None = Query(None),
    search: str | None = Query(None),
    type: str = Query("all"),
    sort: str = Query("name"),
    order: str = Query("asc"),
    refresh: bool = False,
):
    if refresh:
        scanner.refresh()
    files = list(scanner.scan())
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


def _find_file(file_id: str):
    target = scanner.find(file_id)
    if target is None:
        raise HTTPException(status_code=404, detail="文件不存在")
    full_path = (Path(target.dir) / target.path).resolve()
    if not full_path.is_relative_to(Path(target.dir).resolve()) or not full_path.is_file():
        raise HTTPException(status_code=404, detail="文件不存在")
    return target, full_path


def _file_response(path: Path, **kwargs) -> FileResponse:
    response = FileResponse(path, **kwargs)
    response.chunk_size = FILE_CHUNK_SIZE
    return response


def _generate_video_cover(source: Path, output: Path) -> None:
    filters = "scale=640:360:force_original_aspect_ratio=increase,crop=640:360"
    last_error = None
    for seek in ("1", "0"):
        command = [
            "ffmpeg", "-loglevel", "error", "-nostdin", "-y", "-ss", seek,
            "-i", str(source), "-frames:v", "1", "-vf", filters, "-q:v", "4", str(output),
        ]
        try:
            subprocess.run(
                command,
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                timeout=30,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            if output.is_file() and output.stat().st_size > 0:
                return
        except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            last_error = exc
    raise RuntimeError("ffmpeg could not generate a cover") from last_error


@app.get("/api/file/{file_id}/cover")
def cover_file(file_id: str):
    target, full_path = _find_file(file_id)
    headers = {"Cache-Control": "public, max-age=86400"}
    if target.type == "image":
        media_type = mimetypes.guess_type(target.name)[0] or "image/jpeg"
        return _file_response(full_path, media_type=media_type, headers=headers)
    if target.type != "video":
        raise HTTPException(status_code=415, detail="该文件没有封面")

    THUMBNAIL_DIR.mkdir(parents=True, exist_ok=True)
    cache_key = f"{target.id}-{target.size}-{int(target.mtime)}.jpg"
    cover_path = THUMBNAIL_DIR / cache_key
    if not cover_path.is_file():
        with tempfile.NamedTemporaryFile(dir=THUMBNAIL_DIR, suffix=".jpg", delete=False) as temp:
            temp_path = Path(temp.name)
        try:
            _generate_video_cover(full_path, temp_path)
            os.replace(temp_path, cover_path)
            for stale in THUMBNAIL_DIR.glob(f"{target.id}-*.jpg"):
                if stale != cover_path:
                    stale.unlink(missing_ok=True)
        except (OSError, RuntimeError):
            temp_path.unlink(missing_ok=True)
            raise HTTPException(status_code=503, detail="视频封面生成失败，请确认已安装 ffmpeg")
    return _file_response(cover_path, media_type="image/jpeg", headers=headers)


@app.api_route("/api/file/{file_id}/stream", methods=["GET", "HEAD"])
def stream_file(file_id: str, download: bool = False, rev: str | None = None):
    target, full_path = _find_file(file_id)
    if rev is not None:
        try:
            size, _, modified = rev.partition("-")
            stat = full_path.stat()
            if int(size) != stat.st_size or float(modified) != stat.st_mtime:
                raise ValueError("changed")
        except ValueError:
            raise HTTPException(status_code=409, detail="源文件已修改，请刷新文件列表并重新下载")
    headers = {}
    if download or target.type == "other":
        # Starlette 的 filename= 会生成 RFC 5987 filename*=utf-8''... 且自动加 attachment
        return _file_response(full_path, media_type="application/octet-stream", filename=target.name)
    media_type = mimetypes.guess_type(target.name)[0] or _FALLBACK_MEDIA[target.type]
    return _file_response(full_path, media_type=media_type, headers=headers)


class ProgressBody(BaseModel):
    position: float = Field(ge=0, allow_inf_nan=False)


@app.get("/api/progress")
def read_progresses():
    return get_progresses()


@app.get("/api/progress/{file_id}")
def read_progress(file_id: str):
    return {"position": get_progress(file_id)}


@app.put("/api/progress/{file_id}")
def write_progress(file_id: str, body: ProgressBody):
    save_progress(file_id, body.position)
    return {"ok": True}


# 所有 API 路由必须先于此处注册，否则会被根挂载吞掉（Starlette 按注册顺序匹配）
app.mount("/", StaticFiles(directory=BASE_DIR / "static", html=True), name="static")
