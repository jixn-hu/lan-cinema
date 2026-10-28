from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1:8876"
ARTIFACTS = Path(__file__).resolve().parents[1] / ".artifacts"
with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome", headless=True)
    page = browser.new_page(viewport={"width": 1440, "height": 1000})
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(BASE)
    page.wait_for_load_state("networkidle")
    page.get_by_role("heading", name="好视频，随处可看。").wait_for()
    assert page.locator(".card").count() == 2
    page.screenshot(path=str(ARTIFACTS / "desktop.png"), full_page=True)
    page.get_by_label("搜索文件", exact=True).fill("不存在的视频")
    page.get_by_role("heading", name="没有找到这个文件").wait_for()
    page.get_by_label("搜索文件", exact=True).fill("")
    page.get_by_role("button", name="打开 测试视频.mp4", exact=True).wait_for()
    page.get_by_role("button", name="打开 测试视频.mp4", exact=True).click()
    page.wait_for_function("document.querySelector('video').readyState >= 1")
    page.get_by_label("播放倍速", exact=True).select_option("2")
    assert page.locator("video").evaluate("v=>v.playbackRate") == 2
    page.get_by_role("button", name="放大", exact=True).click()
    assert "1.25" in page.locator("video").get_attribute("style")
    page.get_by_role("button", name="展开", exact=True).click()
    assert "expanded" in page.locator(".player-drawer").get_attribute("class")
    stage = page.locator(".video-stage").bounding_box()
    page.mouse.move(stage["x"]+40, stage["y"]+30)
    page.mouse.down()
    page.wait_for_function("document.querySelector('.boost') !== null")
    page.mouse.up()
    page.wait_for_function("document.querySelector('.boost') === null")
    page.get_by_label("关闭播放器", exact=True).click()
    assert not page.locator("video").count()
    page.get_by_role("button", name="设置与共享", exact=False).click()
    page.get_by_label("设备地址", exact=True).fill(BASE)
    page.get_by_role("button", name="连接", exact=True).click()
    page.get_by_role("dialog", name="设置与共享").wait_for(state="hidden")
    assert page.locator(".card").count() == 2
    page.set_viewport_size({"width":390,"height":844})
    page.screenshot(path=str(ARTIFACTS / "mobile-web.png"), full_page=True)
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    page.close()
    native = browser.new_page(viewport={"width":390,"height":844})
    native.add_init_script("""window.calls=[];window.LanCinema={
      cacheInfo:()=>JSON.stringify({bytes:1073741824,limitGb:20,free:80000000000,lockEnabled:false}),
      downloads:()=>JSON.stringify([{id:'test',name:'离线视频.mp4',url:'http://192.168.1.9/video',bytes:3221225472,total:3221225472,complete:true,status:'已完成'}]),
      setCacheLimit:n=>window.calls.push(['cache',n]),clearCache:()=>window.calls.push(['clear']),
      manageLock:()=>window.calls.push(['lock']),pickFiles:()=>window.calls.push(['pick']),
      play:x=>window.calls.push(['play',JSON.parse(x)]),download:x=>window.calls.push(['download',JSON.parse(x)])};""")
    native.on("pageerror", lambda error: errors.append(str(error)))
    native.goto(BASE)
    native.wait_for_load_state("networkidle")
    native.get_by_role("button", name="设置", exact=True).click()
    assert native.get_by_label("缓存上限 GB", exact=True).input_value() == "20"
    native.get_by_label("缓存上限 GB", exact=True).fill("30")
    native.get_by_role("button", name="保存上限", exact=True).click()
    assert native.evaluate("window.calls.some(x=>x[0]==='cache'&&x[1]===30)")
    native.get_by_role("button", name="设置解锁密码", exact=True).click()
    assert native.evaluate("window.calls.some(x=>x[0]==='lock')")
    native.get_by_label("关闭", exact=True).click()
    native.get_by_role("button", name="↓ 下载", exact=True).click()
    native.get_by_role("button", name="离线播放", exact=True).click()
    assert native.evaluate("window.calls.some(x=>x[0]==='play'&&x[1].offline)")
    native.screenshot(path=str(ARTIFACTS / "android-interface.png"), full_page=True)
    assert not errors, errors
    browser.close()
print("Desktop, mobile, playback controls, manual connection, native cache and password bridge: passed")
