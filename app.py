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
