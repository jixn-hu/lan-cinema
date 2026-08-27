# 局域网文件浏览器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一个 FastAPI + 单页 Vue(CDN) 的局域网文件浏览器：递归扫描可配置目录，视频/图片混合网格展示，支持搜索、排序、筛选、在线播放（带续播）与下载。

**Architecture:** 单个 FastAPI 应用（`app.py`）同时提供 REST API 与静态页面托管。文件列表由 `scanner.py` 递归扫描（`os.walk`）+ 15 秒 TTL 内存缓存；播放进度由 `storage.py` 存 SQLite。前端为 `static/index.html` 单文件，Vue 3 CDN 全局构建 + 原生 CSS，零构建步骤。

**Tech Stack:** FastAPI、uvicorn、SQLite（标准库 sqlite3）、Vue 3（CDN）、pytest + httpx（TestClient）。

## Global Constraints

- Python 3.10+（使用 `str | None` 联合类型语法）
- 前端零构建：只用 Vue 3 CDN + 原生 CSS/JS，不引入 npm、打包器、第三方 UI 库
- API 响应字段名与规格一致：`id`/`name`/`size`/`mtime`/`type`/`dir`/`path`；类型值为 `video`/`image`/`other`；`/api/files` 默认 `type=all`（= 视频+图片）
- 配置示例目录 `D:\迅雷下载\other1`，首次运行自动生成 `config.json`
- 文件 id = 相对根目录路径的 SHA1 前 16 位（`scanner.py: file_id()`）
- SQLite 只存播放进度一张表（`file_id TEXT PK, position REAL, updated_at REAL`）
- 流式传输必须支持 HTTP Range（视频拖动进度条依赖），用 `FileResponse`
- 服务监听 `0.0.0.0:8000`
- Windows 环境；提交用 git

---

### Task 1: 项目脚手架（requirements / .gitignore / 配置加载 / /api/dirs / 静态挂载）

**Files:**
- Create: `requirements.txt`
- Create: `.gitignore`
- Create: `README.md`
- Create: `app.py`
- Create: `static/index.html`（占位）
- Test: `tests/test_api.py`

**Interfaces:**
- Consumes: 无（项目为空目录起步）
- Produces:
  - `app.py` 导出模块级 `app: FastAPI`、`BASE_DIR: Path`、`CONFIG_PATH: Path`、`load_config() -> dict`
  - 端点 `GET /api/dirs` → `{"dirs": [...]}`
  - 后续任务的测试会 `import app as app_module` 并 monkeypatch 其模块级属性，故 `load_config` 必须**在调用时**读取 `CONFIG_PATH`（模块级变量），不得把路径写死在函数内

- [ ] **Step 1: 写失败测试**

`tests/test_api.py`：

```python
import app as app_module
from fastapi.testclient import TestClient


def test_load_config_creates_default(tmp_path, monkeypatch):
    cfg_path = tmp_path / "config.json"
    monkeypatch.setattr(app_module, "CONFIG_PATH", cfg_path)
    cfg = app_module.load_config()
    assert cfg == {"dirs": [r"D:\迅雷下载\other1"]}
    assert cfg_path.exists()


def test_load_config_reads_existing(tmp_path, monkeypatch):
    cfg_path = tmp_path / "config.json"
    cfg_path.write_text('{"dirs": ["C:\\\\tmp\\\\a"]}', encoding="utf-8")
    monkeypatch.setattr(app_module, "CONFIG_PATH", cfg_path)
    assert app_module.load_config() == {"dirs": ["C:\\tmp\\a"]}


def test_dirs_endpoint(tmp_path, monkeypatch):
    monkeypatch.setattr(app_module, "CONFIG_PATH", tmp_path / "config.json")
    client = TestClient(app_module.app)
    r = client.get("/api/dirs")
    assert r.status_code == 200
    assert r.json()["dirs"] == [r"D:\迅雷下载\other1"]
```

- [ ] **Step 2: 运行确认失败**

Run: `python -m pytest tests/test_api.py -v`
Expected: FAIL —— `ModuleNotFoundError: No module named 'app'`

- [ ] **Step 3: 写最小实现**

`requirements.txt`：

```
fastapi>=0.110
uvicorn>=0.29
pytest>=8.0
httpx>=0.27
```

`.gitignore`：

```
__pycache__/
*.pyc
.venv/
progress.db
```

`app.py`：

```python
"""局域网文件浏览器后端：REST API + 静态页面托管。"""
import json
from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

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


@app.get("/api/dirs")
def list_dirs():
    return {"dirs": load_config().get("dirs", [])}


app.mount("/", StaticFiles(directory=BASE_DIR / "static", html=True), name="static")
```

`static/index.html`（占位，Task 6 替换为完整页面）：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>文件浏览器</title>
</head>
<body style="font-family:sans-serif;padding:40px;color:#333">
  <h1>🎬 文件浏览器</h1>
  <p>页面构建中…</p>
</body>
</html>
```

`README.md`：

```markdown
# 局域网文件浏览器

递归扫描 config.json 中配置的目录，局域网内网页浏览/播放视频图片/下载文件。

## 启动

```bash
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt   # Windows
.venv\Scripts\uvicorn app:app --host 0.0.0.0 --port 8000
```

浏览器打开 `http://localhost:8000`（局域网设备访问 `http://<电脑IP>:8000`）。

## 配置

`config.json`：`{"dirs": ["D:\\迅雷下载\\other1"]}`，首次运行自动生成，修改后重启生效。
```

- [ ] **Step 4: 安装依赖并运行测试**

Run: `python -m pip install -r requirements.txt` 然后 `python -m pytest tests/test_api.py -v`
Expected: 3 个测试全部 PASS

- [ ] **Step 5: 提交**

```bash
git add requirements.txt .gitignore README.md app.py static/index.html tests/test_api.py
git commit -m "feat: 项目脚手架、配置加载与 /api/dirs"
```

---

### Task 2: 文件扫描器（递归扫描 + 类型识别 + TTL 缓存）

**Files:**
- Create: `scanner.py`
- Modify: `app.py`（创建全局 `scanner` 实例、`/api/dirs` 改读 `scanner.dirs`）
- Test: `tests/test_scanner.py`

**Interfaces:**
- Consumes: 无
- Produces:
  - `FileScanner(dirs: list[str])`，方法 `scan() -> list[FileInfo]`（15 秒 TTL 缓存）、属性 `dirs: list[str]`
  - `detect_type(name: str) -> str`（"video"/"image"/"other"）
  - `file_id(rel_path: str) -> str`（SHA1 前 16 位）
  - `FileInfo` 数据类字段：`id, name, size, mtime, type, dir, path`（`path` 为相对根目录路径，含子文件夹；Windows 反斜杠分隔）
  - `app.py` 模块级 `scanner: FileScanner`（后续任务的 API 路由在调用时读 `app_module.scanner`，测试通过替换该属性注入临时目录）
  - `VIDEO_EXTS`/`IMAGE_EXTS` 模块级常量集合

- [ ] **Step 1: 写失败测试**

`tests/test_scanner.py`：

```python
import os

from scanner import FileScanner, detect_type, file_id


def test_detect_type():
    assert detect_type("a.mp4") == "video"
    assert detect_type("A.MKV") == "video"
    assert detect_type("b.png") == "image"
    assert detect_type("c.txt") == "other"


def test_file_id_stable_and_short():
    assert file_id("sub/a.mp4") == file_id("sub/a.mp4")
    assert len(file_id("sub/a.mp4")) == 16


def test_scan_recursive(tmp_path):
    (tmp_path / "sub").mkdir()
    (tmp_path / "a.mp4").write_bytes(b"123")
    (tmp_path / "sub" / "b.jpg").write_bytes(b"4567")
    (tmp_path / "c.txt").write_bytes(b"x")
    files = FileScanner([str(tmp_path)]).scan()
    assert {f.name for f in files} == {"a.mp4", "b.jpg", "c.txt"}
    b = next(f for f in files if f.name == "b.jpg")
    assert b.path == os.path.join("sub", "b.jpg")
    assert b.type == "image"
    assert b.size == 4
    assert b.dir == str(tmp_path)


def test_scan_skips_missing_dir(tmp_path):
    assert FileScanner([str(tmp_path / "nope")]).scan() == []


def test_scan_cache(tmp_path, monkeypatch):
    (tmp_path / "a.mp4").write_bytes(b"1")
    fs = FileScanner([str(tmp_path)])
    assert len(fs.scan()) == 1
    (tmp_path / "b.mp4").write_bytes(b"2")
    assert len(fs.scan()) == 1  # 命中缓存
    monkeypatch.setattr(fs, "_cached_at", 0.0)  # 强制过期
    assert len(fs.scan()) == 2
```

- [ ] **Step 2: 运行确认失败**

Run: `python -m pytest tests/test_scanner.py -v`
Expected: FAIL —— `ModuleNotFoundError: No module named 'scanner'`

- [ ] **Step 3: 写实现**

`scanner.py`：

```python
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


def file_id(rel_path: str) -> str:
    return hashlib.sha1(rel_path.encode("utf-8")).hexdigest()[:16]


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
                            id=file_id(rel),
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
```

`app.py` 修改（保留 load_config 与 /api/dirs 之外的原有内容）：

```python
import json
from pathlib import Path

from fastapi import FastAPI
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


app.mount("/", StaticFiles(directory=BASE_DIR / "static", html=True), name="static")
```

- [ ] **Step 4: 运行测试**

Run: `python -m pytest tests/ -v`
Expected: 全部 PASS（Task 1 的 3 个 + 本任务 5 个；`/api/dirs` 测试仍通过是因为 `list_dirs` 现在读 `scanner.dirs`，而 `scanner` 在 import 时用真实 config 创建——若本机已有 config.json，目录即其中配置）

- [ ] **Step 5: 提交**

```bash
git add scanner.py app.py tests/test_scanner.py
git commit -m "feat: 递归文件扫描器（类型识别 + TTL 缓存）"
```

---

### Task 3: 文件列表 API（GET /api/files）

**Files:**
- Modify: `app.py`（新增 `list_files` 端点）
- Modify: `tests/test_api.py`（新增文件列表测试）
- Test: `tests/test_api.py`

**Interfaces:**
- Consumes: `app_module.scanner`（测试注入 `FileScanner`）、`FileInfo` 字段
- Produces:
  - 端点 `GET /api/files`，查询参数：`dir`（可选，根目录精确匹配）、`search`（可选，文件名不区分大小写子串）、`type`（`all`/`video`/`image`/`other`，默认 `all`=视频+图片）、`sort`（`name`/`size`/`mtime`，默认 `name`）、`order`（`asc`/`desc`，默认 `asc`）
  - 响应：`list[dict]`，每项 `{"id","name","size","mtime","type","dir","path"}`
  - `name` 排序按小写文件名；跨目录统一排序、不分组

- [ ] **Step 1: 写失败测试**

在 `tests/test_api.py` 追加：

```python
def _setup_scanner(tmp_path):
    (tmp_path / "movie.mp4").write_bytes(b"x" * 1000)
    (tmp_path / "photo.png").write_bytes(b"y" * 2000)
    (tmp_path / "sub").mkdir()
    (tmp_path / "sub" / "Doc.txt").write_bytes(b"z")
    app_module.scanner = FileScanner([str(tmp_path)])
    return TestClient(app_module.app)


def test_files_default_video_image_mixed(tmp_path):
    client = _setup_scanner(tmp_path)
    r = client.get("/api/files")
    assert r.status_code == 200
    data = r.json()
    assert {f["name"] for f in data} == {"movie.mp4", "photo.png"}  # Doc.txt 被排除
    assert [f["name"] for f in data] == ["movie.mp4", "photo.png"]  # 默认名称升序


def test_files_type_other(tmp_path):
    client = _setup_scanner(tmp_path)
    data = client.get("/api/files", params={"type": "other"}).json()
    assert [f["name"] for f in data] == ["Doc.txt"]
    assert data[0]["path"].endswith("Doc.txt")  # 含子文件夹的相对路径


def test_files_search_case_insensitive(tmp_path):
    client = _setup_scanner(tmp_path)
    data = client.get("/api/files", params={"search": "MOVIE"}).json()
    assert [f["name"] for f in data] == ["movie.mp4"]


def test_files_sort_size_desc(tmp_path):
    client = _setup_scanner(tmp_path)
    data = client.get("/api/files", params={"sort": "size", "order": "desc"}).json()
    assert data[0]["name"] == "photo.png"
    assert data[1]["name"] == "movie.mp4"


def test_files_filter_dir(tmp_path):
    other = tmp_path / "other"
    other.mkdir()
    (other / "alone.mp4").write_bytes(b"q")
    client = _setup_scanner(tmp_path)
    data = client.get("/api/files", params={"dir": str(other)}).json()
    assert [f["name"] for f in data] == ["alone.mp4"]
```

同时给 `tests/test_api.py` 顶部补充 import（现有内容保留）：

```python
from scanner import FileScanner
```

- [ ] **Step 2: 运行确认失败**

Run: `python -m pytest tests/test_api.py -v`
Expected: FAIL —— 新增 5 个测试报 `404 Not Found`（`/api/files` 不存在）

- [ ] **Step 3: 写实现**

在 `app.py` 中 `list_dirs` 之后追加：

```python
from fastapi import Query

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
```

（`Query` import 放在文件顶部 import 区更整洁，二选一均可，测试只看行为。）

- [ ] **Step 4: 运行测试**

Run: `python -m pytest tests/ -v`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app.py tests/test_api.py
git commit -m "feat: /api/files 列表接口（筛选/搜索/排序）"
```

---

### Task 4: 文件流式传输（GET /api/file/{id}/stream）

**Files:**
- Modify: `app.py`（新增 `stream_file` 端点）
- Modify: `tests/test_api.py`（新增流式传输测试）
- Test: `tests/test_api.py`

**Interfaces:**
- Consumes: `scanner.scan()` 返回的 `FileInfo`（含 `id`/`dir`/`path`/`type`/`name`）
- Produces:
  - 端点 `GET /api/file/{file_id}/stream`，查询参数 `download: bool = False`
  - 视频/图片 → 按扩展名推断 `Content-Type`，浏览器内播放/显示
  - 其他类型或 `download=1` → `Content-Disposition: attachment`
  - 支持 HTTP Range（`FileResponse` 自动处理，返回 206 + `Content-Range`）
  - 文件不存在 → `HTTPException(404)`
  - 文件实路径 = `Path(f.dir) / f.path`；id 不存在时**不**依赖磁盘，直接从扫描结果判断

- [ ] **Step 1: 写失败测试**

在 `tests/test_api.py` 追加（顶部 import 扩展为 `from scanner import FileScanner, file_id`）：

```python
def test_stream_video_range(tmp_path):
    (tmp_path / "v.mp4").write_bytes(b"0123456789")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id("v.mp4")
    r = client.get(f"/api/file/{fid}/stream", headers={"Range": "bytes=0-3"})
    assert r.status_code == 206
    assert r.content == b"0123"
    assert r.headers["content-range"] == "bytes 0-3/10"


def test_stream_full_video(tmp_path):
    (tmp_path / "v.mp4").write_bytes(b"0123456789")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id("v.mp4")
    r = client.get(f"/api/file/{fid}/stream")
    assert r.status_code == 200
    assert r.content == b"0123456789"
    assert "video/mp4" in r.headers["content-type"]
    assert "content-disposition" not in r.headers  # 内嵌播放，不能是 attachment


def test_stream_download_flag(tmp_path):
    (tmp_path / "v.mp4").write_bytes(b"0123456789")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id("v.mp4")
    r = client.get(f"/api/file/{fid}/stream", params={"download": "1"})
    assert r.status_code == 200
    assert "attachment" in r.headers["content-disposition"]


def test_stream_other_attachment(tmp_path):
    (tmp_path / "d.zip").write_bytes(b"x")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    fid = file_id("d.zip")
    r = client.get(f"/api/file/{fid}/stream")
    assert "attachment" in r.headers["content-disposition"]


def test_stream_404(tmp_path):
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    assert client.get("/api/file/doesnotexist/stream").status_code == 404
```

- [ ] **Step 2: 运行确认失败**

Run: `python -m pytest tests/test_api.py -v`
Expected: FAIL —— 新增 5 个测试 404

- [ ] **Step 3: 写实现**

在 `app.py` 中追加：

```python
import mimetypes

from fastapi import HTTPException
from fastapi.responses import FileResponse

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
```

（`Path` 已在 Task 2 的 import 中。**注意：不要给 FileResponse 传 `filename=`**——Starlette 会因此自动加 `Content-Disposition: attachment`，导致视频无法内嵌播放。）

- [ ] **Step 4: 运行测试**

Run: `python -m pytest tests/ -v`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app.py tests/test_api.py
git commit -m "feat: /api/file/{id}/stream 流式传输（Range/下载）"
```

---

### Task 5: 播放进度存储与接口（SQLite + /api/progress）

**Files:**
- Create: `storage.py`
- Modify: `app.py`（新增进度端点）
- Create: `tests/test_storage.py`
- Modify: `tests/test_api.py`（新增进度 API 测试）
- Test: `tests/test_storage.py`, `tests/test_api.py`

**Interfaces:**
- Consumes: 无
- Produces:
  - `storage.get_progress(file_id: str) -> float`（无记录返回 0.0）
  - `storage.save_progress(file_id: str, position: float)`（upsert；`position <= 0` 时删除该行 = 清空续播）
  - `storage.DB_PATH: Path`（模块级，测试 monkeypatch 用）
  - 端点 `GET /api/progress/{file_id}` → `{"position": float}`
  - 端点 `PUT /api/progress/{file_id}`，请求体 `{"position": float}`；`position <= 0` 视为清空
  - 每次操作独立打开/关闭 SQLite 连接（`check_same_thread=False` 不需要——连接不跨线程复用）

- [ ] **Step 1: 写失败测试**

`tests/test_storage.py`：

```python
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
```

在 `tests/test_api.py` 追加（顶部再补 `import storage`）：

```python
def test_progress_api(monkeypatch, tmp_path):
    monkeypatch.setattr(storage, "DB_PATH", tmp_path / "p.db")
    app_module.scanner = FileScanner([str(tmp_path)])
    client = TestClient(app_module.app)
    assert client.get("/api/progress/x").json() == {"position": 0.0}
    r = client.put("/api/progress/x", json={"position": 42.5})
    assert r.status_code == 200
    assert client.get("/api/progress/x").json() == {"position": 42.5}
    client.put("/api/progress/x", json={"position": 0})
    assert client.get("/api/progress/x").json() == {"position": 0.0}
```

- [ ] **Step 2: 运行确认失败**

Run: `python -m pytest tests/test_storage.py tests/test_api.py -v`
Expected: FAIL —— `ModuleNotFoundError: No module named 'storage'`

- [ ] **Step 3: 写实现**

`storage.py`：

```python
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
    conn = sqlite3.connect(DB_PATH)
    conn.execute(_SCHEMA)
    return conn


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
```

`app.py` 追加：

```python
from fastapi import Query
from pydantic import BaseModel

from storage import get_progress, save_progress


class ProgressBody(BaseModel):
    position: float


@app.get("/api/progress/{file_id}")
def read_progress(file_id: str):
    return {"position": get_progress(file_id)}


@app.put("/api/progress/{file_id}")
def write_progress(file_id: str, body: ProgressBody):
    save_progress(file_id, body.position)
    return {"ok": True}
```

（Task 3 若已 import Query 则不重复。）

- [ ] **Step 4: 运行测试**

Run: `python -m pytest tests/ -v`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add storage.py app.py tests/test_storage.py tests/test_api.py
git commit -m "feat: SQLite 播放进度存储与 /api/progress"
```

---

### Task 6: 前端页面（static/index.html 完整实现）

**Files:**
- Modify: `static/index.html`（整体替换 Task 1 的占位）
- Test: 手动验证（见 Step 3）

**Interfaces:**
- Consumes: `/api/dirs`、`/api/files`、`/api/file/{id}/stream`、`/api/progress/{id}`（GET/PUT）
- Produces: 无（终端任务）

- [ ] **Step 1: 本地化 Vue 依赖**

局域网设备可能没有外网，Vue 必须本地化，不引 CDN。

Run（PowerShell）:
```powershell
Invoke-WebRequest -Uri https://unpkg.com/vue@3/dist/vue.global.prod.js -OutFile static\vue.global.prod.js
```

验证：`Get-Item static\vue.global.prod.js` 大小约 130KB+（不是 HTML 错误页）。
若机器无外网，跳过本步，改用 CDN 地址 `https://unpkg.com/vue@3/dist/vue.global.prod.js`（页面在局域网内依赖外网加载 Vue，仅作降级方案）。

- [ ] **Step 2: 写完整页面**

`static/index.html`（完整替换）：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>🎬 文件浏览器</title>
<script src="./vue.global.prod.js"></script>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { background: #14161a; color: #e6e8eb; font-family: "Segoe UI", "Microsoft YaHei", sans-serif; }
header { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; padding: 18px 0; position: sticky; top: 0; background: #14161a; z-index: 5; }
.app { max-width: 1200px; margin: 0 auto; padding: 0 20px 60px; }
h1 { font-size: 20px; margin-right: auto; }
select, input[type="text"] { background: #1e2229; color: #e6e8eb; border: 1px solid #333a45; border-radius: 8px; padding: 8px 12px; font-size: 14px; outline: none; }
input[type="text"] { width: 220px; }
.tabs { display: flex; gap: 6px; margin: 6px 0 16px; }
.tab { padding: 6px 14px; border-radius: 999px; border: 1px solid #333a45; cursor: pointer; font-size: 13px; color: #8b929c; background: none; }
.tab.active { background: #4f8cff; border-color: #4f8cff; color: #fff; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 14px; }
.card { background: #1e2229; border-radius: 10px; overflow: hidden; cursor: pointer; position: relative; transition: transform .12s, background .12s; }
.card:hover { transform: translateY(-2px); background: #262b34; }
.cover { height: 110px; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #2a3040, #1e2229); font-size: 40px; }
.meta { padding: 10px 12px 12px; }
.meta .name { font-size: 13px; line-height: 1.4; word-break: break-all; }
.meta .sub { font-size: 11px; color: #8b929c; margin-top: 4px; word-break: break-all; }
.badge { position: absolute; top: 8px; left: 8px; background: #4f8cff; color: #fff; font-size: 11px; padding: 2px 8px; border-radius: 999px; }
.badge.unplayable { background: #8b929c; }
.btn-download { position: absolute; top: 8px; right: 8px; background: rgba(0,0,0,.55); border: none; color: #fff; font-size: 13px; width: 30px; height: 30px; border-radius: 8px; cursor: pointer; }
.btn-download:hover { background: #4f8cff; }
.rows .row { display: flex; align-items: center; gap: 10px; background: #1e2229; border-radius: 8px; padding: 10px 14px; margin-bottom: 8px; }
.rows .row .name { flex: 1; font-size: 13px; word-break: break-all; }
.rows .row .btn { background: #4f8cff; border: none; color: #fff; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; }
.empty { text-align: center; color: #8b929c; padding: 60px 0; }
.spinner { text-align: center; padding: 40px; color: #8b929c; }
.mask { position: fixed; inset: 0; background: rgba(0,0,0,.5); z-index: 40; }
.player-drawer { position: fixed; top: 0; right: 0; width: min(560px, 100%); height: 100vh; background: #0d0f12; z-index: 50; display: flex; flex-direction: column; }
.player-drawer video { width: 100%; max-height: 45vh; background: #000; }
.pd-head { display: flex; align-items: center; padding: 12px 16px; gap: 10px; }
.pd-head .t { flex: 1; font-size: 14px; word-break: break-all; }
.pd-x { background: none; border: none; color: #8b929c; font-size: 22px; cursor: pointer; }
.pd-body { flex: 1; overflow-y: auto; padding: 8px 16px; color: #8b929c; font-size: 13px; line-height: 1.8; }
.pd-body .err { color: #ff5f56; }
.lightbox { position: fixed; inset: 0; background: rgba(0,0,0,.92); z-index: 60; display: flex; align-items: center; justify-content: center; }
.lightbox img { max-width: 92vw; max-height: 92vh; }
.lb-btn { position: fixed; top: 50%; transform: translateY(-50%); background: rgba(255,255,255,.1); border: none; color: #fff; font-size: 30px; width: 50px; height: 60px; cursor: pointer; border-radius: 8px; }
.lb-prev { left: 16px; }
.lb-next { right: 16px; }
.lb-close { position: fixed; top: 16px; right: 20px; background: none; border: none; color: #fff; font-size: 28px; cursor: pointer; }
</style>
</head>
<body>
<div id="app">
  <div class="app">
    <header>
      <h1>🎬 文件浏览器</h1>
      <select v-model="currentDir" @change="load">
        <option value="">全部目录</option>
        <option v-for="d in dirs" :key="d" :value="d">{{ d }}</option>
      </select>
      <input type="text" placeholder="搜索文件名…" v-model="search" @input="debouncedLoad">
      <select v-model="sortKey" @change="load">
        <option value="name_asc">名称 ↑</option>
        <option value="name_desc">名称 ↓</option>
        <option value="size_desc">大小 ↓</option>
        <option value="size_asc">大小 ↑</option>
        <option value="mtime_desc">时间 ↓</option>
        <option value="mtime_asc">时间 ↑</option>
      </select>
    </header>

    <div class="tabs">
      <button v-for="t in tabs" :key="t.value" class="tab" :class="{ active: type === t.value }"
              @click="switchType(t.value)">{{ t.label }}</button>
    </div>

    <div v-if="loading" class="spinner">加载中…</div>
    <div v-else-if="!files.length" class="empty">{{ search ? '没有匹配的文件' : '没有文件' }}</div>

    <div v-else-if="type !== 'other'" class="grid">
      <div v-for="f in files" :key="f.id" class="card" @click="openItem(f)">
        <span v-if="progressMap[f.id]" class="badge">继续观看</span>
        <span v-if="f.type === 'video' && !playable(f.name)" class="badge unplayable">需下载后播放</span>
        <button class="btn-download" title="下载" @click.stop="download(f)">⬇</button>
        <div class="cover">{{ f.type === 'video' ? '🎞️' : '🖼️' }}</div>
        <div class="meta">
          <div class="name">{{ f.name }}</div>
          <div class="sub">{{ f.path }} · {{ fmtSize(f.size) }}</div>
        </div>
      </div>
    </div>

    <div v-else class="rows">
      <div v-for="f in files" :key="f.id" class="row">
        <span>📄</span>
        <span class="name">{{ f.name }}</span>
        <span style="color:#8b929c;font-size:12px">{{ fmtSize(f.size) }}</span>
        <button class="btn" @click="download(f)">下载</button>
      </div>
    </div>
  </div>

  <template v-if="player.file">
    <div class="mask" @click="closePlayer"></div>
    <div class="player-drawer">
      <div class="pd-head">
        <span class="t">{{ player.file.name }}</span>
        <button class="pd-x" @click="closePlayer">✕</button>
      </div>
      <video ref="video" controls autoplay @ended="onEnded"></video>
      <div class="pd-body">
        <p>路径：{{ player.file.path }}</p>
        <p>大小：{{ fmtSize(player.file.size) }}　修改：{{ fmtDate(player.file.mtime) }}</p>
        <p v-if="player.error" class="err">播放失败：{{ player.error }}</p>
        <p v-else>提示：拖动进度条可跳转播放，续播进度自动保存。</p>
      </div>
    </div>
  </template>

  <div v-if="lightbox.show" class="lightbox">
    <button class="lb-close" @click="closeLightbox">✕</button>
    <button v-if="lightbox.hasPrev" class="lb-btn lb-prev" @click="lbMove(-1)">‹</button>
    <img :src="lightbox.src" alt="">
    <button v-if="lightbox.hasNext" class="lb-btn lb-next" @click="lbMove(1)">›</button>
  </div>
</div>

<script>
const { createApp } = Vue;

createApp({
  data() {
    return {
      dirs: [], currentDir: '', search: '', type: 'all', sortKey: 'name_asc',
      files: [], loading: false, progressMap: {},
      tabs: [
        { value: 'all', label: '全部' },
        { value: 'video', label: '视频' },
        { value: 'image', label: '图片' },
        { value: 'other', label: '其他' },
      ],
      player: { file: null, error: '' },
      lightbox: { show: false, src: '', hasPrev: false, hasNext: false },
      saveTimer: null, _lbIndex: 0, _debounceTimer: null,
    };
  },
  computed: {
    sort() { return this.sortKey.split('_')[0]; },
    order() { return this.sortKey.split('_')[1]; },
  },
  methods: {
    fmtSize(n) {
      if (n < 1024) return n + ' B';
      const u = ['KB', 'MB', 'GB', 'TB'];
      let i = -1;
      do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
      return n.toFixed(1) + ' ' + u[i];
    },
    fmtDate(ts) { return new Date(ts * 1000).toLocaleString('zh-CN', { hour12: false }); },
    playable(name) { return /\.(mp4|webm|ogv|mov|m4v)$/i.test(name); },

    async load() {
      this.loading = true;
      try {
        const params = new URLSearchParams({ type: this.type, sort: this.sort, order: this.order });
        if (this.currentDir) params.set('dir', this.currentDir);
        if (this.search) params.set('search', this.search);
        const r = await fetch('/api/files?' + params);
        this.files = await r.json();
        await this.loadProgresses();
      } finally {
        this.loading = false;
      }
    },
    async loadProgresses() {
      this.progressMap = {};
      const vids = this.files.filter(f => f.type === 'video');
      const results = await Promise.all(
        vids.map(f => fetch('/api/progress/' + f.id).then(r => r.json()).catch(() => ({ position: 0 })))
      );
      vids.forEach((f, i) => { if (results[i].position > 0) this.progressMap[f.id] = results[i].position; });
    },
    debouncedLoad() {
      clearTimeout(this._debounceTimer);
      this._debounceTimer = setTimeout(() => this.load(), 300);
    },
    switchType(t) { this.type = t; this.load(); },
    download(f) { location.href = '/api/file/' + f.id + '/stream?download=1'; },

    openItem(f) {
      if (f.type === 'video') this.openVideo(f);
      else if (f.type === 'image') this.openLightbox(f);
    },

    // ---- 播放器 ----
    openVideo(f) {
      this.saveProgress();  // 先保存上一个文件
      this.player = { file: f, error: '' };
    },
    initVideo(f) {
      const v = this.$refs.video;
      v.src = '/api/file/' + f.id + '/stream';
      v.onerror = () => {
        this.player.error = '该格式浏览器无法播放，请下载后观看';
        this.saveProgress(true);
      };
      v.addEventListener('loadedmetadata', () => {
        const pos = this.progressMap[f.id] || 0;
        if (pos > 0) v.currentTime = pos;
      }, { once: true });
      clearInterval(this.saveTimer);
      this.saveTimer = setInterval(() => this.saveProgress(), 5000);
      v.play().catch(() => {});
    },
    onEnded() { this.saveProgress(true); },
    async saveProgress(clear) {
      if (!this.player.file || !this.$refs.video) return;
      const v = this.$refs.video;
      const watched = !!(v.duration && v.currentTime >= v.duration * 0.95);
      const pos = (clear || watched) ? 0 : (v.currentTime || 0);
      try {
        await fetch('/api/progress/' + this.player.file.id, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ position: pos }),
        });
        if (pos <= 0) delete this.progressMap[this.player.file.id];
        else this.progressMap[this.player.file.id] = pos;
      } catch (e) { /* 静默失败，不打断播放 */ }
    },
    closePlayer() {
      this.saveProgress();
      clearInterval(this.saveTimer);
      this.saveTimer = null;
      this.player = { file: null, error: '' };
    },

    // ---- 灯箱 ----
    imageList() { return this.files.filter(f => f.type === 'image'); },
    openLightbox(f) {
      const imgs = this.imageList();
      this._lbIndex = imgs.indexOf(f);
      this.showLightbox(imgs);
      window.addEventListener('keydown', this.onLbKey);
    },
    showLightbox(imgs) {
      this.lightbox = {
        show: true, src: '/api/file/' + imgs[this._lbIndex].id + '/stream',
        hasPrev: this._lbIndex > 0, hasNext: this._lbIndex < imgs.length - 1,
      };
    },
    lbMove(d) {
      const imgs = this.imageList();
      this._lbIndex = (this._lbIndex + d + imgs.length) % imgs.length;
      this.showLightbox(imgs);
    },
    onLbKey(e) {
      if (e.key === 'Escape') this.closeLightbox();
      if (e.key === 'ArrowLeft') this.lbMove(-1);
      if (e.key === 'ArrowRight') this.lbMove(1);
    },
    closeLightbox() {
      this.lightbox.show = false;
      window.removeEventListener('keydown', this.onLbKey);
    },
  },
  watch: {
    'player.file'(f) {
      if (f) this.$nextTick(() => this.initVideo(f));
    },
  },
  mounted() {
    fetch('/api/dirs').then(r => r.json()).then(d => { this.dirs = d.dirs || []; });
    this.load();
  },
}).mount('#app');
</script>
</body>
</html>
```

- [ ] **Step 3: 语法自检**

在浏览器打开前先做静态检查：
Run: `python -m pytest tests/ -v`（确认后端不受影响），然后用浏览器打开 `http://localhost:8000`。
若页面空白，按 F12 看 Console 报错（常见：`vue.global.prod.js` 未下载成功、JS 语法错误）。

- [ ] **Step 4: 手动验证清单**

启动：`.venv\Scripts\uvicorn app:app --host 0.0.0.0 --port 8000`，浏览器打开 `http://localhost:8000`，对照检查：

- [ ] 页面加载：标题「🎬 文件浏览器」，深色主题
- [ ] 列表：`D:\迅雷下载\other1`（及子文件夹）里的视频、图片混合显示在网格中，名称+相对路径+大小
- [ ] 目录下拉：可切「全部目录」/ 各根目录
- [ ] 搜索：输入关键词 300ms 后过滤，大小写不敏感；清空恢复
- [ ] 类型 Tab：全部/视频/图片/其他 切换正确；「其他」显示为行列表
- [ ] 排序：名称↑↓、大小↓↑、时间↓↑ 作用于整个列表
- [ ] 视频播放：点击 mp4 卡片 → 右侧播放器出现并播放；拖动进度条跳转（Range 生效）
- [ ] 续播：播放几秒后关闭，再次打开该视频自动从上次位置继续；卡片出现「继续观看」角标
- [ ] 看完清空：把视频拖到接近结尾再等 5 秒，进度被清空
- [ ] 不支持格式：mkv 卡片显示「需下载后播放」角标，点击后播放器提示下载
- [ ] 图片：点击图片卡片 → 灯箱全屏预览，‹ › 翻页（按当前筛选列表顺序），Esc 关闭
- [ ] 下载：卡片 ⬇ 按钮、「其他」行「下载」按钮均能下载文件
- [ ] 局域网：同一 Wi-Fi 下手机访问 `http://<电脑IP>:8000` 正常打开
- [ ] 删除文件：删掉一个文件后刷新，列表消失，播放不报错（404 有提示）
- [ ] 并发：手机和电脑同时浏览、播放互不影响

- [ ] **Step 5: 提交**

```bash
git add static/index.html static/vue.global.prod.js
git commit -m "feat: 前端单页（网格展示/播放器续播/灯箱/下载）"
```

---

## Self-Review 结果

（写完计划后的自查，见下方总结；无需执行动作，仅记录。）

- **规格覆盖**：递归扫描（Task 2）✓、混合展示+全局排序（Task 3+6）✓、Range 流式传输（Task 4）✓、播放进度+清空（Task 5+6）✓、图片灯箱（Task 6）✓、其他文件下载（Task 4+6）✓、TTL 缓存（Task 2）✓、config 自动生成（Task 1）✓、错误处理 404/跳过不可读目录（Task 2/4）✓
- **占位符扫描**：无 TBD/TODO；每步含完整代码与预期输出
- **类型一致性**：`FileInfo` 字段（id/name/size/mtime/type/dir/path）在 scanner → API → 前端三处一致；`file_id()` 在 Task 2 定义、Task 4/6 复用；`storage.save_progress(file_id, position)` 签名在 Task 5 定义、Task 6 前端调用语义一致
- **自查修正记录**：
  1. Task 4 `stream_file`：不给 FileResponse 传 `filename=`（Starlette 会强制 attachment，视频无法内嵌播放）；显式 `media_type`（mimetypes 推断 + 按类型兜底）
  2. Task 4 测试：去掉 `__import__` 写法，改用顶部 `from scanner import FileScanner, file_id`；新增断言内嵌播放时无 `content-disposition` 头
  3. Task 5 测试：`__import__("storage")` 改为正常 `import storage`
  4. Task 6：Vue 本地化（`static/vue.global.prod.js`，下载失败降级 CDN）；修复 `saveProgress` 中 95% 清空的无效代码（`pos_` 未定义），改为 `watched` 判定
