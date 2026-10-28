"""Favorite persistence, native metadata and actionable cross-device errors."""
import json
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:8876"
ARTIFACTS = Path(__file__).resolve().parents[1] / ".artifacts"

with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    errors = []
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(BASE)
    page.wait_for_load_state("networkidle")
    page.get_by_role("button", name="收藏 测试视频.mp4", exact=True).click()
    page.get_by_role("button", name="收藏视频", exact=False).click()
    assert page.locator(".favorite-card h3").inner_text() == "测试视频.mp4"
    page.reload()
    page.get_by_role("button", name="收藏视频", exact=False).click()
    page.get_by_role("button", name="播放收藏 测试视频.mp4", exact=True).click()
    page.wait_for_function("document.querySelector('video')?.readyState>=1")
    page.locator(".player-controls").get_by_role("button", name="已收藏", exact=False).click()
    page.get_by_label("关闭播放器", exact=True).click()
    assert page.locator(".favorite-card").count() == 0
    page.get_by_role("button", name="文件库", exact=False).click()
    page.get_by_role("button", name="收藏 测试视频.mp4", exact=True).click()

    # A running old server is reachable: refuse unsupported DELETE before sending it.
    device = page.request.get(BASE + "/api/device").json()
    delete_calls = []
    page.on("request", lambda r: delete_calls.append(r.url) if r.method == "DELETE" else None)
    def old_device(route):
        route.fulfill(json={**device, "features": ["range", "progress"]})
    page.route("**/api/device", old_device)
    page.get_by_role("button", name="删除 测试视频.mp4", exact=True).click()
    page.get_by_role("button", name="确认删除原文件", exact=True).click()
    page.get_by_role("status").filter(has_text="版本过旧").wait_for()
    assert not delete_calls
    page.get_by_label("取消删除", exact=True).click()
    page.unroute("**/api/device", old_device)

    def broken_delete(route):
        if route.request.method == "DELETE":
            route.abort("failed")
        else:
            route.continue_()
    page.route("**/api/file/*", broken_delete)
    page.get_by_role("button", name="删除 测试视频.mp4", exact=True).click()
    page.get_by_role("button", name="确认删除原文件", exact=True).click()
    page.get_by_role("status").filter(has_text="可以访问，但操作请求").wait_for()
    assert "无法连接源设备" not in page.locator(".toast").inner_text()
    page.get_by_label("取消删除", exact=True).click()
    page.unroute("**/api/file/*", broken_delete)

    for status, detail, expected in [
        (403, "文件权限不足", "文件权限不足"),
        (500, "源设备数据库不可用", "源设备数据库不可用"),
        (422, [{"msg": "文件版本无效"}], "文件版本无效"),
    ]:
        def rejected(route):
            route.fulfill(status=status, json={"detail": detail})
        page.route("**/api/file/*", rejected)
        page.get_by_role("button", name="删除 测试视频.mp4", exact=True).click()
        page.get_by_role("button", name="确认删除原文件", exact=True).click()
        page.get_by_role("status").filter(has_text=expected).wait_for()
        page.get_by_label("取消删除", exact=True).click()
        page.unroute("**/api/file/*", rejected)
    page.close()

    native = browser.new_page(viewport={"width": 390, "height": 844})
    native.on("pageerror", lambda e: errors.append(str(e)))
    native.add_init_script("""window.calls=[];window.nativeDownloads=[];window.LanCinema={
      cacheInfo:()=>JSON.stringify({bytes:0,limitGb:20,free:1000000000}),
      downloads:()=>JSON.stringify(window.nativeDownloads),
      favorites:()=>localStorage.getItem('test-native-favorites')||'[]',
      toggleFavorite:s=>{const b=JSON.parse(s),all=JSON.parse(window.LanCinema.favorites());
        const exists=all.some(x=>x.key===b.key),next=all.filter(x=>x.key!==b.key);if(!exists)next.unshift(b);
        localStorage.setItem('test-native-favorites',JSON.stringify(next));return JSON.stringify(next)},
      play:x=>window.calls.push(['play',JSON.parse(x)]),
      download:x=>window.calls.push(['download',JSON.parse(x)])};""")
    native.goto(BASE)
    native.wait_for_load_state("networkidle")
    native.get_by_role("button", name="收藏 测试视频.mp4", exact=True).click()
    native.get_by_role("button", name="打开 测试视频.mp4", exact=True).click()
    info = native.evaluate("window.calls.find(x=>x[0]==='play')[1]")
    assert info["favorite"]["key"].endswith(":" + info["favorite"]["file"]["id"])
    assert info["playlist"][0]["favorite"]["key"] == info["favorite"]["key"]
    native.get_by_label("下载 测试视频.mp4", exact=True).click()
    assert native.evaluate("window.calls.find(x=>x[0]==='download')[1].favorite.key") == info["favorite"]["key"]
    native.reload()
    native.get_by_role("button", name="☆ 收藏", exact=True).click()
    native.get_by_role("button", name="播放收藏 测试视频.mp4", exact=True).wait_for()
    native.get_by_label("搜索收藏").fill("找不到")
    assert native.locator(".favorite-card").count() == 0
    native.get_by_label("搜索收藏").fill("")

    # An updated discovery address replaces the saved IP. The source remains the same device.
    remote = {"key": "pc-id:episode", "deviceId": "pc-id", "deviceName": "客厅电脑",
              "source": {"id": "pc-id", "name": "客厅电脑", "url": "http://192.0.2.1:8000/?t=saved-token"},
              "url": "http://192.0.2.1:8000/api/file/episode/stream?rev=100-1&t=saved-token",
              "file": {"id": "episode", "name": "收藏剧集.mp4", "size": 100, "mtime": 1, "type": "video"}, "added": 1}
    native.evaluate("b=>window.LanCinema.toggleFavorite(JSON.stringify(b))", remote)
    def discovery(route):
        route.fulfill(json={"devices": [{"id": "pc-id", "name": "客厅电脑", "url": "http://favorite-source.test:8000"}]})
    native.route("**/api/devices", discovery)
    missing = [False]
    def remote_server(route):
        path = route.request.url
        if "/api/device" in path:
            route.fulfill(json={"id": "pc-id", "features": ["file-info", "delete"]})
        elif "/api/progress" in path:
            route.fulfill(json={"episode": 12})
        elif missing[0]:
            route.fulfill(status=404, json={"detail": "文件不存在"})
        else:
            route.fulfill(json={**remote["file"], "size": 200, "mtime": 2})
    native.route("http://favorite-source.test:8000/**", remote_server)
    native.reload()
    native.get_by_role("button", name="☆ 收藏", exact=True).click()
    native.get_by_role("button", name="播放收藏 收藏剧集.mp4", exact=True).click()
    native.wait_for_function("window.calls.length>0")
    played = native.evaluate("window.calls.at(-1)[1]")
    assert "favorite-source.test" in played["url"] and "rev=200-2" in played["url"]
    assert "t=saved-token" in played["url"] and played["position"] == 12

    missing[0] = True
    native.get_by_role("button", name="播放收藏 收藏剧集.mp4", exact=True).click()
    native.get_by_role("status").filter(has_text="已删除或停止共享").wait_for()
    assert native.locator(".favorite-card").count() == 2
    # A completed download plays without contacting the now-unavailable source.
    offline = {"id": "download", "name": "收藏剧集.mp4", "url": remote["url"], "complete": True,
               "total": 100, "bytes": 100, "type": "video", "favorite": remote}
    native.evaluate("d=>{window.nativeDownloads=[d];window.calls=[]}", offline)
    native.get_by_role("button", name="播放收藏 收藏剧集.mp4", exact=True).click()
    played = native.evaluate("window.calls.at(-1)[1]")
    assert played["offline"] and played["favorite"]["key"] == remote["key"]
    native.screenshot(path=str(ARTIFACTS / "favorites-v1.3-mobile.png"), full_page=True)
    assert native.evaluate("document.documentElement.scrollWidth<=innerWidth")
    native.get_by_label("取消收藏 收藏剧集.mp4", exact=True).click()
    native.reload()
    native.get_by_role("button", name="☆ 收藏", exact=True).click()
    assert native.locator(".favorite-card").count() == 1
    assert not errors, errors
    browser.close()

print("Favorites persistence, player/download bridge, source IP changes, offline playback, stale source and error messages: passed")
