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
