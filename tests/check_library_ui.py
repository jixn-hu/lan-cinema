"""Run against tests/ui_server.py. All deletion targets belong to the UI fixture."""
from pathlib import Path
import shutil

from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:8876"
ARTIFACTS = Path(__file__).resolve().parents[1] / ".artifacts"
MEDIA = ARTIFACTS / "ui-media"
created = [MEDIA / name for name in ("剧集2.mp4", "剧集10.mp4", "同步删除.mp4")]
for target in created:
    assert not target.exists(), f"Fixture target already exists: {target}"
    shutil.copyfile(MEDIA / "测试视频.mp4", target)

try:
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        errors = []
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.clock.install()
        page.goto(BASE)
        page.wait_for_load_state("networkidle")
        page.get_by_label("刷新文件", exact=True).click()
        page.get_by_role("button", name="打开 剧集2.mp4", exact=True).wait_for()
        requests = []
        page.on("request", lambda r: requests.append(r.url))
        page.evaluate("window.dispatchEvent(new Event('lanresume'))")
        page.wait_for_timeout(300)
        assert not any("/api/files" in url for url in requests), requests
        page.get_by_role("button", name="设置与共享", exact=False).click()
        assert page.get_by_label("自动刷新间隔").input_value() == "0"
        page.get_by_label("自动刷新间隔").select_option("60")
        page.get_by_label("关闭", exact=True).click()
        with page.expect_request(lambda r: "/api/files?" in r.url and "refresh=true" in r.url):
            page.clock.fast_forward(61000)
        page.get_by_label("刷新文件", exact=True).wait_for(state="visible")
        page.get_by_role("button", name="设置与共享", exact=False).click()
        page.get_by_label("自动刷新间隔").select_option("0")
        page.get_by_label("关闭", exact=True).click()
        page.get_by_label("搜索文件", exact=True).fill("剧集")
        page.clock.fast_forward(500)
        page.wait_for_function("document.querySelectorAll('.card').length===2")
        page.get_by_role("button", name="打开 剧集2.mp4", exact=True).click()
        page.wait_for_function("document.querySelector('video').readyState>=1")
        page.get_by_role("button", name="播放列表 1/2", exact=True).click()
        assert page.get_by_role("navigation", name="播放列表").locator("button").all_text_contents() == ["01剧集2.mp4", "02剧集10.mp4"]
        page.locator("video").evaluate("v=>v.dispatchEvent(new Event('ended'))")
        page.wait_for_function("document.querySelector('.pd-head span').textContent==='剧集10.mp4'")
        assert page.locator(".player-controls").get_by_role("button", name="下一集", exact=True).is_disabled()
        page.get_by_label("关闭播放器", exact=True).click()
        page.get_by_label("搜索文件", exact=True).fill("")
        page.clock.fast_forward(500)
        page.get_by_role("button", name="删除 同步删除.mp4", exact=True).wait_for()

        other = browser.new_page()
        other.clock.install()
        other.goto(BASE)
        other.wait_for_load_state("networkidle")
        other.get_by_role("button", name="打开 同步删除.mp4", exact=True).wait_for()
        page.get_by_role("button", name="删除 同步删除.mp4", exact=True).click()
        page.get_by_role("dialog", name="确认删除原文件").wait_for()
        page.get_by_role("button", name="确认删除原文件", exact=True).click()
        page.get_by_role("dialog", name="确认删除原文件").wait_for(state="hidden")
        assert not created[-1].exists()
        other.clock.fast_forward(16000)
        other.get_by_role("button", name="打开 同步删除.mp4", exact=True).wait_for(state="hidden")
        other.close()

        native = browser.new_page(viewport={"width": 390, "height": 844})
        native.on("pageerror", lambda e: errors.append(str(e)))
        native.add_init_script("""window.calls=[];window.LanCinema={
          cacheInfo:()=>JSON.stringify({bytes:80,limitGb:20,free:1000000000}),
          downloads:()=>JSON.stringify([
            {id:'partial',name:'缓存2.mp4',url:'http://127.0.0.1:9/2',bytes:20,total:100,temporary:true,complete:false,status:'播放缓存'},
            {id:'complete',name:'已下载10.mp4',url:'http://127.0.0.1:9/10',bytes:100,total:100,complete:true,status:'已下载'}]),
          play:x=>window.calls.push(['play',JSON.parse(x)]),
          downloadAction:(...x)=>window.calls.push(['action',...x])};""")
        native.goto(BASE)
        native.wait_for_load_state("networkidle")
        native.get_by_role("button", name="打开 剧集2.mp4", exact=True).click()
        names = native.evaluate("window.calls.find(x=>x[0]==='play')[1].playlist.map(x=>x.name)")
        assert names.index("剧集2.mp4") < names.index("剧集10.mp4")
        native.get_by_role("button", name="↓ 下载", exact=True).click()
        native.get_by_label("下载与缓存排序").select_option("progress_desc")
        assert native.locator(".download-row h3").all_text_contents() == ["已下载10.mp4", "缓存2.mp4"]
        native.get_by_label("下载与缓存排序").select_option("progress_asc")
        assert native.locator(".download-row h3").all_text_contents() == ["缓存2.mp4", "已下载10.mp4"]
        native.get_by_role("button", name="下载剩余部分", exact=True).click()
        assert native.evaluate("window.calls.some(x=>x[0]==='action'&&x[1]==='partial'&&x[2]==='resume')")
        assert native.evaluate("document.documentElement.scrollWidth<=innerWidth")
        native.screenshot(path=str(ARTIFACTS / "downloads-v1.2-mobile.png"), full_page=True)
        assert not errors, errors
        browser.close()
finally:
    for target in created:
        target.unlink(missing_ok=True)

print("Manual/interval refresh, natural episode order, auto-next, remote delete sync, cache sorting and mobile UI: passed")
