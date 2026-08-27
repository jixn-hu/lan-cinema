"""递归扫描配置目录，生成文件清单（带 15 秒 TTL 缓存）。"""
import hashlib
import os
import time
from dataclasses import dataclass

VIDEO_EXTS = {
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".rmvb", ".flv", ".wmv", ".mpeg", ".mpg",
}
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".avif"}


@dataclass
class FileInfo:
    id: str
    name: str
    size: int
    mtime: float
    type: str  # "video" / "image" / "other"
    dir: str   # 配置的根目录
    path: str  # 相对根目录的路径（含子文件夹）


def detect_type(name: str) -> str:
    ext = os.path.splitext(name)[1].lower()
    if ext in VIDEO_EXTS:
        return "video"
    if ext in IMAGE_EXTS:
        return "image"
    return "other"


def file_id(root: str, rel_path: str) -> str:
    return hashlib.sha1(os.path.join(root, rel_path).encode("utf-8")).hexdigest()[:16]


class FileScanner:
    def __init__(self, dirs: list[str]):
        self._dirs = dirs
        self._cache: list[FileInfo] | None = None
        self._cached_at: float = 0.0
        self._ttl = 15.0

    @property
    def dirs(self) -> list[str]:
        return self._dirs

    def scan(self) -> list[FileInfo]:
        now = time.time()
        if self._cache is not None and now - self._cached_at < self._ttl:
            return self._cache
        files = []
        for root in self._dirs:
            if not os.path.isdir(root):
                continue  # 目录不存在/不可读：跳过并继续其他根目录
            for dirpath, _dirnames, filenames in os.walk(root):
                for fname in filenames:
                    full = os.path.join(dirpath, fname)
                    try:
                        st = os.stat(full)
                    except OSError:
                        continue
                    rel = os.path.relpath(full, root)
                    files.append(
                        FileInfo(
                            id=file_id(root, rel),
                            name=fname,
                            size=st.st_size,
                            mtime=st.st_mtime,
                            type=detect_type(fname),
                            dir=root,
                            path=rel,
                        )
                    )
        self._cache = files
        self._cached_at = now
        return files
