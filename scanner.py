"""递归扫描配置目录，生成文件清单（带 15 秒 TTL 缓存）。"""
import hashlib
import os
import time
import threading
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
        self._lock = threading.RLock()
        self._index: dict[str, FileInfo] = {}

    @property
    def dirs(self) -> list[str]:
        return self._dirs

    def scan(self) -> list[FileInfo]:
        with self._lock:
            return self._scan()

    def find(self, identifier: str) -> FileInfo | None:
        # Streaming/cover requests must not walk every shared directory again after TTL.
        # The HTTP handler revalidates containment, existence and the file revision.
        target = self._index.get(identifier)
        if target is not None:
            return target
        with self._lock:
            self._scan()
            return self._index.get(identifier)

    def refresh(self):
        with self._lock:
            self._cached_at = 0

    def _scan(self) -> list[FileInfo]:
        now = time.time()
        if self._cache is not None and now - self._cached_at < self._ttl:
            return self._cache
        files = []
        for root in self._dirs:
            if not os.path.isdir(root):
                continue  # 目录不存在/不可读：跳过并继续其他根目录
            real_root = os.path.realpath(root)
            for dirpath, dirnames, filenames in os.walk(root):
                # Check directories once; don't resolve the whole root three times per file.
                dirnames[:] = [name for name in dirnames if self._contained(real_root, os.path.join(dirpath, name))]
                for fname in filenames:
                    full = os.path.join(dirpath, fname)
                    try:
                        if os.path.islink(full) and not self._contained(real_root, full):
                            continue
                        st = os.stat(full)
                    except (OSError, ValueError):
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
        self._index = {f.id: f for f in files}
        self._cached_at = time.time()
        return files

    @staticmethod
    def _contained(root: str, path: str) -> bool:
        try:
            return os.path.commonpath([root, os.path.realpath(path)]) == root
        except (OSError, ValueError):
            return False
