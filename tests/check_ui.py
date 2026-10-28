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
    page.get_by_label("播放倍速", exact=True).select_option("0.75")
    page.locator("video").evaluate("v=>{v.loop=true;v.play()}")
    page.mouse.down()
    page.wait_for_function("document.querySelector('video').playbackRate === 2")
    page.mouse.up()
    page.wait_for_function("document.querySelector('video').playbackRate === 0.75")
    page.mouse.down()
    page.mouse.move(stage["x"]+100,stage["y"]+40)
    page.wait_for_timeout(500)
    assert page.locator("video").evaluate("v=>v.playbackRate") == 0.75
    page.mouse.up()
    # Drive real browser touch input: the same picture point must stay under the midpoint.
    cdp = page.context.new_cdp_session(page)
    cx,cy=stage["x"]+stage["width"]*.3,stage["y"]+stage["height"]*.4
    def touches(radius):
        return [{"x":cx-radius,"y":cy,"id":1},{"x":cx+radius,"y":cy,"id":2}]
    cdp.send("Input.dispatchTouchEvent", {"type":"touchStart","touchPoints":touches(40)})
    cdp.send("Input.dispatchTouchEvent", {"type":"touchMove","touchPoints":touches(80)})
    page.wait_for_function("new DOMMatrix(getComputedStyle(document.querySelector('video')).transform).a > 1.8")
    matrix=page.locator("video").evaluate("v=>{const m=new DOMMatrix(getComputedStyle(v).transform);return {scale:m.a,x:m.e,y:m.f}}")
    assert abs((cx-stage["x"])*matrix["scale"]+matrix["x"]-(cx-stage["x"]))<2
    assert abs((cy-stage["y"])*matrix["scale"]+matrix["y"]-(cy-stage["y"]))<2
    cdp.send("Input.dispatchTouchEvent", {"type":"touchEnd","touchPoints":[]})
    assert page.locator("video").evaluate("v=>v.playbackRate") == 0.75
    page.get_by_role("button", name="填满", exact=True).click()
    page.locator("video").evaluate("v=>{v.pause();v.currentTime=2}")
    page.mouse.move(stage["x"]+stage["width"]*.3,stage["y"]+stage["height"]*.4)
    page.mouse.down()
    page.mouse.move(stage["x"]+stage["width"]*.5,stage["y"]+stage["height"]*.4,steps=5)
    page.locator(".cinema-seek-hint").wait_for()
    assert abs(page.locator("video").evaluate("v=>v.currentTime")-2)<.1
    page.mouse.up()
    assert page.locator("video").evaluate("v=>v.currentTime")>3
    page.screenshot(path=str(ARTIFACTS / "player-desktop.png"))
    page.get_by_label("关闭播放器", exact=True).click()
    assert not page.locator("video").count()
    page.get_by_role("button", name="设置与共享", exact=False).click()
    page.get_by_label("设备地址", exact=True).fill(BASE)
    page.get_by_role("button", name="连接", exact=True).click()
    page.get_by_role("dialog", name="设置与共享").wait_for(state="hidden")
    page.wait_for_function("document.querySelectorAll('.card').length === 2")
    page.set_viewport_size({"width":390,"height":844})
    page.screenshot(path=str(ARTIFACTS / "mobile-web.png"), full_page=True)
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    page.close()
    native = browser.new_page(viewport={"width":390,"height":844})
    native.add_init_script("""window.calls=[];window.LanCinema={
      cacheInfo:()=>JSON.stringify({bytes:1073741824,limitGb:20,free:80000000000,lockEnabled:false}),
      downloads:()=>JSON.stringify([{id:'test',name:'离线视频.mp4',url:'http://192.168.1.9/video',bytes:3221225472,total:3221225472,complete:true,status:'已完成'},{id:'active',name:'正在下载.mp4',bytes:1073741824,total:3221225472,downloading:true,speed:12582912,status:'下载中'}]),
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
    assert native.locator(".download-speed").inner_text() == "12.0 MB/s"
    assert native.get_by_text("33.3%", exact=False).is_visible()
    native.get_by_role("button", name="离线播放", exact=True).click()
    assert native.evaluate("window.calls.some(x=>x[0]==='play'&&x[1].offline)")
    native.screenshot(path=str(ARTIFACTS / "android-interface.png"), full_page=True)
    assert not errors, errors
    browser.close()
print("Desktop, mobile, playback controls, manual connection, native cache and password bridge: passed")
